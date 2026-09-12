package com.example.city_fix.config;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Tracks live sessions so deactivating an account can end them. In-memory and
     * per-instance: on a scaled-out deployment an eviction only reaches sessions held by
     * the instance serving the request. Single-instance today — see the plan's Migration
     * Notes before scaling out.
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Without this listener {@code sessionDestroyed} never fires and the registry keeps an
     * entry for every session that has already been logged out or timed out.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    objectMapper.writeValue(response.getOutputStream(), Map.of(
                        "message", "Authentication required"
                    ));
                })
            )
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> {
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType("application/json;charset=UTF-8");
                    objectMapper.writeValue(response.getOutputStream(), Map.of(
                        "message", "Logged out"
                    ));
                })
            )
            .csrf(csrf -> csrf
                .spa()
                .ignoringRequestMatchers("/api/auth/**")
            )
            // maximumSessions(-1) is unlimited: this is not concurrency control. It is the
            // supported way to install ConcurrentSessionFilter, which is what notices a
            // session the admin surface has expired.
            .sessionManagement(session -> session
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry())
                .expiredSessionStrategy(event -> {
                    // Match this chain's 401 JSON entry point rather than redirecting.
                    HttpServletResponse response = event.getResponse();
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    objectMapper.writeValue(response.getOutputStream(), Map.of(
                        "message", "Authentication required"
                    ));
                })
            );

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/register", "/css/**", "/js/**", "/error").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // Gates the whole staff surface in one place, so no future /staff route can
                // be added unprotected. ADMIN is included: the PRD grants admin everything
                // staff can do. Must stay above anyRequest(), which would otherwise shadow it.
                .requestMatchers("/staff/**").hasAnyRole("STAFF", "ADMIN")
                // ADMIN only — deliberately NOT mirroring /staff/**. Admin is a superset of
                // staff for triage, but account management is not a staff capability.
                // Must stay above anyRequest(), which would otherwise shadow it.
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .usernameParameter("email")
                .passwordParameter("password")
                .defaultSuccessUrl("/", true)
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
            )
            // See the API chain for why maximumSessions is -1. An evicted browser lands on
            // the ordinary login form, which says nothing about deactivation.
            .sessionManagement(session -> session
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry())
                .expiredUrl("/login?expired")
            );

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}