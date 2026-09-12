package com.example.city_fix.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.city_fix.auth.AuthService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminUserServiceTest {

    private static final String PASSWORD = "password123";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserSessionService userSessionService;

    private AdminUserService adminUserService;

    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserService(userRepository, passwordEncoder, userSessionService);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-hash");
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createStaff_normalisesTheEmailBeforeTheUniquenessCheckAndBeforeTheWrite() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        adminUserService.createStaff("  Mixed.Case@Example.COM  ", PASSWORD);

        // Both call sites matter: normalising only before the write still lets a duplicate
        // slip past the check, and normalising only before the check writes an
        // unauthenticatable row (lessons.md).
        verify(userRepository).existsByEmail("mixed.case@example.com");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("mixed.case@example.com");
    }

    @Test
    void createStaff_savesWithStaffRoleAnEncodedPasswordAndActive() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);

        adminUserService.createStaff("new-staff@example.com", PASSWORD);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.STAFF);
        assertThat(saved.getValue().getPassword()).isEqualTo("encoded-hash");
        assertThat(saved.getValue().isActive()).isTrue();
    }

    @Test
    void createStaff_withATakenEmail_isRejectedAndWritesNothing() {
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> adminUserService.createStaff("taken@example.com", PASSWORD))
            .isInstanceOf(AuthService.EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createStaff_losingTheInsertRace_isReportedAsATakenEmail() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> adminUserService.createStaff("racy@example.com", PASSWORD))
            .isInstanceOf(AuthService.EmailAlreadyExistsException.class);
    }

    @Test
    void setActive_false_persistsTheFlagAndThenEvictsSessions() {
        User staff = persistedUser(7L, Role.STAFF);
        when(userRepository.findById(7L)).thenReturn(Optional.of(staff));

        adminUserService.setActive(7L, false);

        assertThat(staff.isActive()).isFalse();
        // The flag is the durable guarantee, so it must be written before the eviction.
        var inOrder = org.mockito.Mockito.inOrder(userRepository, userSessionService);
        inOrder.verify(userRepository).save(staff);
        inOrder.verify(userSessionService).expireSessions(staff);
    }

    @Test
    void setActive_true_persistsTheFlagAndDoesNotEvict() {
        User staff = persistedUser(8L, Role.STAFF);
        staff.deactivate();
        when(userRepository.findById(8L)).thenReturn(Optional.of(staff));

        adminUserService.setActive(8L, true);

        assertThat(staff.isActive()).isTrue();
        verify(userRepository).save(staff);
        verify(userSessionService, never()).expireSessions(any());
    }

    @Test
    void setActive_onAResident_isAllowed() {
        User resident = persistedUser(9L, Role.RESIDENT);
        when(userRepository.findById(9L)).thenReturn(Optional.of(resident));

        adminUserService.setActive(9L, false);

        assertThat(resident.isActive()).isFalse();
        verify(userSessionService).expireSessions(resident);
    }

    @Test
    void setActive_onAnAdmin_isRefusedInBothDirectionsAndChangesNothing() {
        User admin = persistedUser(1L, Role.ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> adminUserService.setActive(1L, false))
            .isInstanceOf(AdminUserService.AdminAccountNotManageableException.class);
        assertThatThrownBy(() -> adminUserService.setActive(1L, true))
            .isInstanceOf(AdminUserService.AdminAccountNotManageableException.class);

        assertThat(admin.isActive()).isTrue();
        verify(userRepository, never()).save(any());
        verify(userSessionService, never()).expireSessions(any());
    }

    @Test
    void setActive_onAnUnknownId_isReportedAsNotFound() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.setActive(404L, false))
            .isInstanceOf(AdminUserService.UserNotFoundException.class);

        verify(userSessionService, never()).expireSessions(any());
    }

    @Test
    void listManageableAccounts_asksOnlyForResidentsAndStaff() {
        when(userRepository.findByRoleInOrderByCreatedAtAsc(any())).thenReturn(java.util.List.of());

        adminUserService.listManageableAccounts();

        ArgumentCaptor<java.util.Collection<Role>> roles = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(userRepository).findByRoleInOrderByCreatedAtAsc(roles.capture());
        assertThat(roles.getValue()).containsExactlyInAnyOrder(Role.RESIDENT, Role.STAFF);
    }

    private static User persistedUser(Long id, Role role) {
        User user = new User("user" + id + "@example.com", "hash", role);
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not seed the test user id", e);
        }
        return user;
    }
}
