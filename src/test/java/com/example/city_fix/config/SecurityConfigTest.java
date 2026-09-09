package com.example.city_fix.config;

import com.example.city_fix.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest extends TestcontainersConfig {

    @Autowired
    private MockMvc mockMvc;

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
