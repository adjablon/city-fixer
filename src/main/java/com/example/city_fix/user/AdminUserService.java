package com.example.city_fix.user;

import com.example.city_fix.auth.AuthService;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin account-management operations, and the single owner of the rule that ADMIN rows
 * are untouchable. That rule lives here rather than in the controller or the template so no
 * future route can reach a state this service would refuse.
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    /**
     * Admins are deliberately absent: an admin can neither be listed nor deactivated, which
     * makes self-lockout and admin-vs-admin lockout structurally impossible and removes the
     * need for a last-active-admin guard. Admin provisioning stays on admin.seed.* env vars.
     */
    private static final Set<Role> MANAGEABLE_ROLES = EnumSet.of(Role.RESIDENT, Role.STAFF);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionService userSessionService;

    public AdminUserService(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            UserSessionService userSessionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userSessionService = userSessionService;
    }

    public Page<AccountRow> listManageableAccounts(Pageable pageable) {
        return userRepository.findByRoleIn(MANAGEABLE_ROLES, pageable).map(AccountRow::from);
    }

    /**
     * @throws AuthService.EmailAlreadyExistsException if the address is already taken
     */
    public User createStaff(String rawEmail, String rawPassword) {
        // CustomUserDetailsService lowercases before lookup, so a mixed-case row could never
        // authenticate. Normalise before the uniqueness check and before the write, not just
        // one of the two (lessons.md).
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(email)) {
            throw new AuthService.EmailAlreadyExistsException(email);
        }

        User staff = new User(email, passwordEncoder.encode(rawPassword), Role.STAFF);
        try {
            User saved = userRepository.save(staff);
            log.info("Admin created staff account '{}'", email);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // The unique constraint caught a registration that landed between the check
            // above and this insert — the address is taken either way.
            log.warn("Staff account creation lost a race for '{}' ({})", email, e.getMessage());
            throw new AuthService.EmailAlreadyExistsException(email);
        }
    }

    /**
     * @throws UserNotFoundException      if no such account exists
     * @throws AdminAccountNotManageableException if the target is an ADMIN
     */
    // Transactional because this is a read-modify-write: the loaded user stays managed so the
    // flag change is flushed by dirty checking rather than a detached merge, which would
    // re-write every column from values read earlier and lose a concurrent update.
    @Transactional
    public void setActive(Long userId, boolean active) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        if (!MANAGEABLE_ROLES.contains(user.getRole())) {
            throw new AdminAccountNotManageableException(userId);
        }

        if (active) {
            user.activate();
        } else {
            user.deactivate();
        }

        if (!active) {
            // Order is load-bearing: the flag is set on the managed entity before the
            // eviction, so a rollback takes both back together rather than leaving a user
            // evicted from a deactivation that never committed.
            userSessionService.expireSessions(user);
        }

        log.info("Admin set account '{}' to {}", user.getEmail(), active ? "active" : "deactivated");
    }

    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(Long userId) {
            super("No account with id " + userId);
        }
    }

    public static class AdminAccountNotManageableException extends RuntimeException {
        public AdminAccountNotManageableException(Long userId) {
            super("Account " + userId + " is an admin and cannot be managed here");
        }
    }
}
