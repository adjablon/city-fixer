package com.example.city_fix.config;

import com.example.city_fix.user.Role;
import com.example.city_fix.user.User;
import com.example.city_fix.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffSeederTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private StaffSeeder seeder(String email, String password) {
        return new StaffSeeder(userRepository, passwordEncoder, email, password);
    }

    @Test
    void seedsStaff_whenVarsSetAndEmailAbsent() {
        when(userRepository.existsByEmail("staff@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret-password")).thenReturn("encoded-hash");

        seeder("staff@example.com", "secret-password").run(null);

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getEmail()).isEqualTo("staff@example.com");
        assertThat(savedUser.getValue().getPassword()).isEqualTo("encoded-hash");
        assertThat(savedUser.getValue().getRole()).isEqualTo(Role.STAFF);
    }

    @Test
    void normalisesEmail_soTheSeededAccountMatchesLoginLookup() {
        when(userRepository.existsByEmail("staff@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret-password")).thenReturn("encoded-hash");

        seeder("  Staff@Example.COM  ", "secret-password").run(null);

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getEmail()).isEqualTo("staff@example.com");
    }

    @Test
    void skipsSeeding_whenUserWithSeedEmailExists() {
        when(userRepository.existsByEmail("staff@example.com")).thenReturn(true);

        seeder("staff@example.com", "secret-password").run(null);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void skipsSeeding_whenEnvVarsBlank() {
        seeder("", "").run(null);

        verifyNoInteractions(userRepository, passwordEncoder);
    }
}
