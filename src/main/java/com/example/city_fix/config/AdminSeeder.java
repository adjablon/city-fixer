package com.example.city_fix.config;

import com.example.city_fix.user.Role;
import com.example.city_fix.user.User;
import com.example.city_fix.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.seed.email:}")
    private String adminEmail;

    @Value("${admin.seed.password:}")
    private String adminPassword;

    public AdminSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            log.debug("Admin seed skipped: ADMIN_EMAIL or ADMIN_PASSWORD not set");
            return;
        }

        if (userRepository.existsByEmail(adminEmail)) {
            log.info("Admin seed skipped: user with email '{}' already exists", adminEmail);
            return;
        }

        User admin = new User(adminEmail, passwordEncoder.encode(adminPassword), Role.ADMIN);
        userRepository.save(admin);
        log.info("Admin account seeded for '{}'", adminEmail);
    }
}
