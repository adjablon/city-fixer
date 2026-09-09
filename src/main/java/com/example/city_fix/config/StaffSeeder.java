package com.example.city_fix.config;

import com.example.city_fix.user.Role;
import com.example.city_fix.user.User;
import com.example.city_fix.user.UserRepository;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Bootstraps a STAFF account so report triage can be used and tested before S-03 adds
 * admin-managed staff accounts. Deliberately a sibling of {@link AdminSeeder} rather than a
 * refactor of it: the duplication is small and this class is short-lived.
 */
@Component
public class StaffSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String staffEmail;
    private final String staffPassword;

    public StaffSeeder(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${staff.seed.email:}") String staffEmail,
            @Value("${staff.seed.password:}") String staffPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.staffEmail = staffEmail;
        this.staffPassword = staffPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (staffEmail.isBlank() || staffPassword.isBlank()) {
            log.debug("Staff seed skipped: STAFF_EMAIL or STAFF_PASSWORD not set");
            return;
        }

        // CustomUserDetailsService lowercases the submitted email before lookup, so a
        // mixed-case seeded address would never match at login.
        String email = staffEmail.trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(email)) {
            log.info("Staff seed skipped: user with email '{}' already exists", email);
            return;
        }

        User staff = new User(email, passwordEncoder.encode(staffPassword), Role.STAFF);
        try {
            userRepository.save(staff);
            log.info("Staff account seeded for '{}'", email);
        } catch (DataIntegrityViolationException e) {
            // Another instance starting concurrently seeded the same account between the
            // existsByEmail check and the insert — the account exists, so startup can proceed.
            log.warn("Staff seed skipped: concurrent seed detected for '{}' ({})", email, e.getMessage());
        }
    }
}
