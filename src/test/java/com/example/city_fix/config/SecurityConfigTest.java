package com.example.city_fix.config;

import com.example.city_fix.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityConfigTest extends IntegrationTest {

    @Test
    void publicApiPaths_accessibleWithoutAuth() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                    {"email": "x@x.com", "password": "wrong"}
                    """))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("""
                    {"email": "invalid", "password": "short"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void publicWebPaths_accessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/register"))
            .andExpect(status().isOk());
    }

    @Test
    void vendoredMapAssets_accessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/css/leaflet.css"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/js/leaflet.js"))
            .andExpect(status().isOk());
    }

    @Test
    void actuatorHealth_accessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    @Test
    void protectedApiPath_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void protectedWebPath_redirectsToLogin_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/login"));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void protectedWebPath_accessible_whenAuthenticated() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void adminPath_forbiddenToResident() throws Exception {
        mockMvc.perform(get("/admin/users"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void adminPath_forbiddenToStaff() throws Exception {
        // The important one: /admin/** is ADMIN-only and deliberately does NOT mirror
        // /staff/**, which admits STAFF and ADMIN alike. This is what would catch someone
        // "fixing" the matcher for consistency.
        mockMvc.perform(get("/admin/users"))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminPath_redirectsToLoginWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/admin/users"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/login"));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void adminWriteRoutes_forbiddenToStaff() throws Exception {
        // The matcher is the ONLY thing protecting these — AdminUserController carries no
        // @PreAuthorize by design. Narrowing /admin/** to GET would open account creation and
        // deactivation to staff, and without these two cases the suite would stay green.
        mockMvc.perform(post("/admin/users")
                .param("email", "intruder@example.com")
                .param("password", "password123")
                .with(csrf()))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/users/1/active")
                .param("active", "false")
                .with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminWriteRoute_withoutCsrfToken_isRejected() throws Exception {
        // Catches anyone adding /admin/** to a CSRF ignore list: the whole state-changing
        // surface of the admin feature is form POSTs on the web chain.
        mockMvc.perform(post("/admin/users/1/active")
                .param("active", "false"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void staffPath_forbiddenToResident() throws Exception {
        // Pinned at the config level, not only through the controller: the /staff/** matcher
        // must stay ahead of anyRequest().authenticated(), which would otherwise shadow it.
        mockMvc.perform(get("/staff/reports"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    void staffPath_allowedToStaff() throws Exception {
        mockMvc.perform(get("/staff/reports"))
            .andExpect(status().isOk());
    }

    @Test
    void staffPath_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/staff/reports"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/login"));
    }

    @Test
    void csrfEnforced_onNonAuthApiPath_returns403() throws Exception {
        mockMvc.perform(post("/api/something")
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void csrfNotEnforced_onAuthApiPath() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                    {"email": "x@x.com", "password": "wrong"}
                    """))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void csrfSpaCookie_isSetOnResponse() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(cookie().exists("XSRF-TOKEN"))
            .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }
}
