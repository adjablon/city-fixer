package com.example.city_fix.auth;

import com.example.city_fix.user.Role;
import com.example.city_fix.user.User;
import com.example.city_fix.user.UserRepository;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User register(String email, String password) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Invalid email format");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }

        User user = new User(email, passwordEncoder.encode(password), Role.RESIDENT);
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // existsByEmail() + save() is not atomic: a concurrent registration with the
            // same email can slip between the check and the insert, hitting the DB unique
            // constraint instead. Translate to the same 409 as the pre-check path.
            throw new EmailAlreadyExistsException(email);
        }
    }

    public static class EmailAlreadyExistsException extends RuntimeException {
        public EmailAlreadyExistsException(String email) {
            super("Email already registered: " + email);
        }
    }
}