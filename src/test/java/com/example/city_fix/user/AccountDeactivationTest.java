package com.example.city_fix.user;

import com.example.city_fix.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Evidence of record for the deactivation contract. Phase 1 covers the login-blocking half;
 * Phase 5 extends this class with session-eviction and cross-role cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountDeactivationTest extends TestcontainersConfig {

    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void deactivatedAccount_apiLogin_isRejectedWithTheGenericMessage() throws Exception {
        String email = persistUser("deactivated-api@example.com", Role.STAFF, false);

        // A 500 here is the regression this test exists to catch: DisabledException is not a
        // BadCredentialsException, so a narrow catch in AuthController would let it escape.
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void deactivatedAccount_formLogin_redirectsToTheGenericError() throws Exception {
        String email = persistUser("deactivated-form@example.com", Role.STAFF, false);

        mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", PASSWORD)
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void activeAccount_apiLogin_succeeds() throws Exception {
        // Control: proves the two rejections above are caused by the flag and not by a
        // broken fixture, a wrong password, or the email normalisation path.
        String email = persistUser("active-control@example.com", Role.STAFF, true);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void reactivatedAccount_canLogInAgain() throws Exception {
        String email = persistUser("reactivated@example.com", Role.STAFF, false);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
            .andExpect(status().isUnauthorized());

        User user = userRepository.findByEmail(email).orElseThrow();
        user.activate();
        userRepository.save(user);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
            .andExpect(status().isOk());
    }

    /** Nothing is transactional and the container is shared, so every test owns its email. */
    private String persistUser(String email, Role role, boolean active) {
        User user = new User(email, passwordEncoder.encode(PASSWORD), role);
        if (!active) {
            user.deactivate();
        }
        userRepository.save(user);
        return email;
    }

    private static String credentials(String email) {
        return "{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}";
    }
}
