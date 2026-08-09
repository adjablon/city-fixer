package com.example.city_fix.auth;

import com.example.city_fix.user.User;
import com.example.city_fix.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @Test
    void registerWhenConcurrentDuplicateHitsUniqueConstraint_throwsEmailAlreadyExists() {
        when(userRepository.existsByEmail("race@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save(any(User.class)))
            .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        assertThatThrownBy(() -> authService.register("race@example.com", "password123"))
            .isInstanceOf(AuthService.EmailAlreadyExistsException.class)
            .hasMessageContaining("race@example.com");
    }
}
