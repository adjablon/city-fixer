package com.example.city_fix.user;

import com.example.city_fix.TestcontainersConfig;
import com.example.city_fix.auth.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private UserSessionService userSessionService;

    @Autowired
    private ApplicationContext applicationContext;

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

    @Test
    void apiLogin_registersTheSessionWithTheRegistry() throws Exception {
        // The gap this phase exists to close: formLogin runs
        // RegisterSessionAuthenticationStrategy, manual authentication does not. Mocked unit
        // tests cannot catch a missing registration — only the real registry can.
        String email = persistUser("registry-wiring@example.com", Role.STAFF, true);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
            .andExpect(status().isOk())
            .andReturn();

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(sessionRegistry.getAllSessions(new CustomUserDetails(user), false))
            .as("the API login path must register its session, or eviction silently no-ops")
            .hasSize(1)
            .extracting(SessionInformation::getSessionId)
            .containsExactly(result.getRequest().getSession().getId());
    }

    @Test
    void expiringSessions_endsALiveApiSession() throws Exception {
        String email = persistUser("evicted-api@example.com", Role.STAFF, true);

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();

        mockMvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isOk());

        User user = userRepository.findByEmail(email).orElseThrow();
        user.deactivate();
        userRepository.save(user);
        userSessionService.expireSessions(user);

        mockMvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void expiringOneUsersSessions_leavesAnotherUsersSessionAlone() throws Exception {
        String victimEmail = persistUser("evict-victim@example.com", Role.STAFF, true);
        String bystanderEmail = persistUser("evict-bystander@example.com", Role.STAFF, true);

        MvcResult bystanderLogin = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(bystanderEmail)))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession bystanderSession =
            (MockHttpSession) bystanderLogin.getRequest().getSession();

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(victimEmail)))
            .andExpect(status().isOk());

        userSessionService.expireSessions(userRepository.findByEmail(victimEmail).orElseThrow());

        mockMvc.perform(get("/api/auth/me").session(bystanderSession))
            .andExpect(status().isOk());
    }

    @Test
    void expiringSessions_endsALiveBrowserSessionAndSendsItToTheLoginPage() throws Exception {
        // The web chain is what real users are on; it has its own sessionManagement block
        // and its own expiredUrl, so the API-path test above does not cover it.
        String email = persistUser("evicted-web@example.com", Role.STAFF, true);

        MvcResult login = mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", PASSWORD)
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();

        mockMvc.perform(get("/").session(session))
            .andExpect(status().isOk());

        User user = userRepository.findByEmail(email).orElseThrow();
        user.deactivate();
        userRepository.save(user);
        userSessionService.expireSessions(user);

        mockMvc.perform(get("/").session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void formLogin_registersTheSessionWithTheRegistry() throws Exception {
        // formLogin registers via RegisterSessionAuthenticationStrategy rather than the
        // explicit call in AuthController; pinned so a config change cannot drop it.
        String email = persistUser("registry-web@example.com", Role.STAFF, true);

        mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", PASSWORD)
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(sessionRegistry.getAllSessions(new CustomUserDetails(user), false))
            .hasSize(1);
    }

    @Test
    void httpSessionEventPublisher_isRegistered() {
        // Registry cleanup on logout depends on this listener firing sessionDestroyed.
        // MockMvc cannot exercise that end-to-end: MockHttpSession.invalidate() does not
        // publish container lifecycle events, so after a MockMvc logout the registry entry
        // survives. Verified by probe during implementation. The best this harness can do is
        // pin that the listener bean exists; the behaviour itself needs a real container and
        // stays on the deploy-time checklist.
        assertThat(applicationContext.getBeansOfType(HttpSessionEventPublisher.class))
            .as("without this listener the registry leaks an entry per logout")
            .isNotEmpty();
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
