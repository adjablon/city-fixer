package com.example.city_fix.config;

import com.example.city_fix.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Config-level cases that assert more than authorization: response bodies, explicit 200s, and
 * CSRF enforcement. Plain route-versus-identity denial now lives in
 * {@link RouteAuthorizationMatrixTest}, driven by {@link RouteAuthorizationTable}.
 */
class SecurityConfigTest extends IntegrationTest {

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Test
    void everyFilterChainInstallsSessionExpiryEnforcement() {
        // Account state is not re-checked per request: the principal is a snapshot taken at
        // login. Revoking a live session depends entirely on ConcurrentSessionFilter reading the
        // registry, and that filter is installed only by a sessionManagement block. A chain added
        // without one silently stops evicting deactivated accounts for everything it serves, and
        // nothing else in the suite would notice.
        assertThat(filterChainProxy.getFilterChains())
            .isNotEmpty()
            .allSatisfy(chain -> assertThat(chain.getFilters())
                .as("chain %s installs no ConcurrentSessionFilter, so deactivation cannot evict "
                    + "the sessions it serves", chain)
                .anySatisfy(filter -> assertThat(filter).isInstanceOf(ConcurrentSessionFilter.class)));
    }

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
    @WithMockUser(roles = "RESIDENT")
    void protectedWebPath_accessible_whenAuthenticated() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk());
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
    @WithMockUser(roles = "STAFF")
    void staffPath_allowedToStaff() throws Exception {
        mockMvc.perform(get("/staff/reports"))
            .andExpect(status().isOk());
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
