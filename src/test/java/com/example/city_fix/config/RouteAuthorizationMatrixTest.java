package com.example.city_fix.config;

import com.example.city_fix.IntegrationTest;
import com.example.city_fix.config.RouteAuthorizationTable.Expectation;
import com.example.city_fix.config.RouteAuthorizationTable.Identity;
import com.example.city_fix.user.Role;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Exercises every cell of {@link RouteAuthorizationTable}: what each identity may reach, for
 * every route behind authentication.
 *
 * <p>Allow cells are asserted as well as deny cells, which is what makes a denial mean anything.
 * A rule that denies <em>everyone</em> passes a suite of denial-only tests; here it fails its
 * allow cells immediately.
 *
 * <p>Every case targets an id that does not exist and sends no request body, so an authorized
 * request reaches its handler and stops there. That keeps the matrix about authorization — which
 * is all {@code GRANTED} claims — and means no request writes a report or account row. The
 * three cached sessions do each persist one user, once per run.
 */
class RouteAuthorizationMatrixTest extends IntegrationTest {

    private static final String ABSENT_ID = "999999";

    // One session per role for the whole class. Building a fresh account per cell would add
    // sixty accounts to a database that is shared and never rolled back.
    private static MockHttpSession residentSession;
    private static MockHttpSession staffSession;
    private static MockHttpSession adminSession;

    static List<Expectation> expectations() {
        return RouteAuthorizationTable.expectations();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expectations")
    void identityMeetsTheAuthoredOutcome(Expectation expectation) throws Exception {
        String path = expectation.route().pattern().replace("{id}", ABSENT_ID);
        MockHttpServletRequestBuilder request = expectation.route().method() == RequestMethod.POST
            ? post(path).with(csrf())
            : get(path);

        MockHttpSession session = sessionFor(expectation.identity());
        if (session != null) {
            request = request.session(session);
        }

        MvcResult result = mockMvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        String location = result.getResponse().getHeader("Location");
        String describe = expectation.identity() + " -> " + expectation.route();

        switch (expectation.outcome()) {
            case FORBIDDEN -> assertThat(status)
                .as("%s must be refused with 403", describe)
                .isEqualTo(403);
            case UNAUTHORIZED -> assertThat(status)
                .as("%s must be refused with 401", describe)
                .isEqualTo(401);
            case LOGIN_REDIRECT -> {
                assertThat(status).as("%s must redirect", describe).isBetween(300, 399);
                // endsWith, not contains: the entry point emits no query string, so /login?expired
                // or /loginXyz would mean something else went wrong.
                assertThat(location).as("%s must be sent to the login page", describe)
                    .isNotNull()
                    .endsWith("/login");
            }
            case GRANTED -> {
                // GRANTED is a claim about the authorization layer only: the request was allowed
                // through. What the handler then did — 200, a 404 for an absent id, a validation
                // failure — is the business of the focused tests, not of this matrix.
                assertThat(status)
                    .as("%s must not be refused by the authorization layer", describe)
                    .isNotIn(401, 403)
                    // A 5xx is not a handler outcome, it is a broken one — an allow cell must not
                    // stay green on a route that throws on every request.
                    .isLessThan(500);
                if (status >= 300 && status < 400) {
                    // No null-guard: a redirect without a Location header cannot be shown to be
                    // authorized, so it must fail rather than slip through this branch.
                    assertThat(location)
                        .as("%s was redirected to the login page, so it was not authorized", describe)
                        .isNotNull()
                        .doesNotContain("/login");
                }
            }
        }
    }

    @Test
    void everyAuthenticatedRouteInTheTableProducesCases() {
        // A lower bound, not an identity: authenticatedRoutes() is derived FROM the expectations,
        // so asserting that each of its routes appears in them can never fail. Only a floor on the
        // table's size catches rows being dropped, and only the size check below catches a route
        // that was given fewer than four identities.
        assertThat(RouteAuthorizationTable.authenticatedRoutes())
            .as("the table must still cover the application's authenticated surface")
            .hasSizeGreaterThanOrEqualTo(15);

        assertThat(expectations())
            .as("every route must carry an expectation for all four identities")
            .hasSize(RouteAuthorizationTable.authenticatedRoutes().size() * Identity.values().length);
    }

    private MockHttpSession sessionFor(Identity identity) throws Exception {
        return switch (identity) {
            case ANONYMOUS -> null;
            case RESIDENT -> residentSession = residentSession != null
                ? residentSession
                : registerResident("matrix-resident@example.com");
            case STAFF -> staffSession = staffSession != null
                ? staffSession
                : authenticateAs("matrix-staff@example.com", Role.STAFF);
            case ADMIN -> adminSession = adminSession != null
                ? adminSession
                : authenticateAs("matrix-admin@example.com", Role.ADMIN);
        };
    }
}
