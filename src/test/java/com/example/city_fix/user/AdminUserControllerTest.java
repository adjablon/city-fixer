package com.example.city_fix.user;

import com.example.city_fix.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminUserControllerTest extends TestcontainersConfig {

    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @WithMockUser(roles = "ADMIN")
    void home_offersTheAccountManagementLinkToAnAdmin() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("/admin/users")));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void home_hidesTheAccountManagementLinkFromStaff() throws Exception {
        // The S-02 failure mode: a nav link that survives a new authorization rule and sends
        // the wrong role straight into a 403. The guard expression here must stay identical
        // to the /admin/** matcher.
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("/admin/users"))));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void home_hidesTheAccountManagementLinkFromResidents() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("/admin/users"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void accountList_rendersManageableAccountsAndNeverAnAdminRow() throws Exception {
        String staffEmail = persist("list-staff@example.com", Role.STAFF);
        String residentEmail = persist("list-resident@example.com", Role.RESIDENT);
        String adminEmail = persist("list-admin@example.com", Role.ADMIN);

        // Walk every page: the container is shared and non-transactional, so this test's rows
        // can sit on any page. Checking all of them also makes the admin-exclusion assertion
        // stronger than before — no admin row on ANY page, not merely the first.
        String allPages = allPagesHtml();
        assertThat(allPages).contains(staffEmail).contains(residentEmail);
        // Admins are excluded at the query, so an admin row cannot appear even if a template
        // change later forgets to filter.
        assertThat(allPages).doesNotContain(adminEmail);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void accountList_isPagedSoItCannotGrowWithoutBound() throws Exception {
        for (int i = 0; i < 30; i++) {
            persist("paging-" + i + "-" + System.nanoTime() + "@example.com", Role.RESIDENT);
        }

        String firstPage = mockMvc.perform(get("/admin/users"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // The page size is the point: without it this table grows with the resident count.
        assertThat(countOccurrences(firstPage, "/active")).isEqualTo(25);
        assertThat(firstPage).contains("Next");

        String secondPage = mockMvc.perform(get("/admin/users").param("page", "1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(secondPage).contains("Previous");
        // Pages must not overlap — the sort has an id tiebreak so the ordering is total.
        assertThat(firstRowEmail(firstPage)).isNotEqualTo(firstRowEmail(secondPage));
    }

    /** Concatenates every page of the account list; the container is shared across tests. */
    private String allPagesHtml() throws Exception {
        StringBuilder all = new StringBuilder();
        for (int page = 0; page < 50; page++) {
            String html = mockMvc.perform(get("/admin/users").param("page", String.valueOf(page)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            all.append(html);
            if (!html.contains("Next")) {
                break;
            }
        }
        return all.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static String firstRowEmail(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<td>([^<]+@[^<]+)</td>").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createStaffForm_renders() throws Exception {
        mockMvc.perform(get("/admin/users/new"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("password")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createStaff_withAnInvalidEmail_reRendersTheFormAndPersistsNothing() throws Exception {
        mockMvc.perform(post("/admin/users")
                .param("email", "not-an-email")
                .param("password", PASSWORD)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Invalid email format")));

        assertThat(userRepository.findByEmail("not-an-email")).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createStaff_withAShortPassword_reRendersTheFormAndPersistsNothing() throws Exception {
        mockMvc.perform(post("/admin/users")
                .param("email", "short-pw@example.com")
                .param("password", "short")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Password must be at least 8 characters")));

        assertThat(userRepository.findByEmail("short-pw@example.com")).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createStaff_withATakenEmail_reportsItRatherThanFailing() throws Exception {
        String existing = persist("already-there@example.com", Role.RESIDENT);

        mockMvc.perform(post("/admin/users")
                .param("email", existing)
                .param("password", PASSWORD)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("already in use")));

        // The rejected create must not have touched the existing account: a bug that
        // reported the conflict but still overwrote the row's role would otherwise pass.
        assertThat(userRepository.findByEmail(existing).orElseThrow().getRole())
            .isEqualTo(Role.RESIDENT);
    }

    // The two routes below were implemented in Phase 3 and redirect rather than render, so
    // these are regression tests over existing behaviour, not test-first cycles.

    @Test
    @WithMockUser(roles = "ADMIN")
    void createStaff_persistsALowercasedStaffAccountAndRedirects() throws Exception {
        mockMvc.perform(post("/admin/users")
                .param("email", "  New.Staff@Example.COM  ")
                .param("password", PASSWORD)
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/users"));

        // Normalisation must reach this new write path too, or the account could never
        // authenticate — CustomUserDetailsService lowercases before lookup (lessons.md).
        assertThat(userRepository.findByEmail("new.staff@example.com"))
            .get()
            .satisfies(user -> assertThat(user.getRole()).isEqualTo(Role.STAFF));

        // Assert the column, not isActive(): the accessor treats null as active, so a row
        // written with active IS NULL would satisfy an accessor-based check and be
        // indistinguishable from a pre-backfill row.
        assertThat(jdbcTemplate.queryForObject(
                "select active from users where email = ?", Boolean.class, "new.staff@example.com"))
            .isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void setActive_flipsTheFlagAndRedirects() throws Exception {
        String email = persist("toggle-me@example.com", Role.STAFF);
        Long id = userRepository.findByEmail(email).orElseThrow().getId();

        mockMvc.perform(post("/admin/users/{id}/active", id)
                .param("active", "false")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/users"));
        assertThat(userRepository.findByEmail(email).orElseThrow().isActive()).isFalse();

        mockMvc.perform(post("/admin/users/{id}/active", id)
                .param("active", "true")
                .with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findByEmail(email).orElseThrow().isActive()).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void setActive_onAnAdminRow_isRefused() throws Exception {
        String adminEmail = persist("untouchable-admin@example.com", Role.ADMIN);
        Long id = userRepository.findByEmail(adminEmail).orElseThrow().getId();

        // Admin rows are never listed, so reaching this route means a hand-crafted request.
        mockMvc.perform(post("/admin/users/{id}/active", id)
                .param("active", "false")
                .with(csrf()))
            .andExpect(status().isForbidden());

        assertThat(userRepository.findByEmail(adminEmail).orElseThrow().isActive()).isTrue();
        // The flag surviving is only half the claim — the account must still work. Without
        // this, a bug that refused the request but corrupted the row would pass.
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(adminEmail)))
            .andExpect(status().isOk());
    }

    @Test
    void adminFlow_createStaffThenDeactivateThenReactivate() throws Exception {
        // The whole slice in one walk, driven entirely through the admin surface rather than
        // the service: create → the new account logs in → deactivate → its live session dies
        // and re-login is refused → reactivate → it logs in again.
        persist("flow-admin@example.com", Role.ADMIN);
        MockHttpSession adminSession = login("flow-admin@example.com");

        mockMvc.perform(post("/admin/users")
                .session(adminSession)
                .param("email", "Flow.Staff@Example.com")
                .param("password", PASSWORD)
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        // 4.7: a mixed-case address must end up authenticatable, not just persisted.
        MockHttpSession staffSession = login("flow.staff@example.com");
        mockMvc.perform(get("/staff/reports").session(staffSession))
            .andExpect(status().isOk());

        Long staffId = userRepository.findByEmail("flow.staff@example.com").orElseThrow().getId();
        mockMvc.perform(post("/admin/users/{id}/active", staffId)
                .session(adminSession)
                .param("active", "false")
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        // 4.8: the live session is gone, and the account cannot get a new one.
        mockMvc.perform(get("/staff/reports").session(staffSession))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?expired"));
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials("flow.staff@example.com")))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/admin/users/{id}/active", staffId)
                .session(adminSession)
                .param("active", "true")
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        // 4.9: reactivation restores access.
        mockMvc.perform(get("/staff/reports").session(login("flow.staff@example.com")))
            .andExpect(status().isOk());
    }

    private MockHttpSession login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
            .andExpect(status().isOk())
            .andReturn();
        return (MockHttpSession) result.getRequest().getSession();
    }

    private static String credentials(String email) {
        return "{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}";
    }

    private String persist(String email, Role role) {
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), role));
        return email;
    }
}
