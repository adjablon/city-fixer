package com.example.city_fix.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * The authored expectation table: what each identity may reach, for every route behind
 * authentication.
 *
 * <p>This is the oracle and it is written from the PRD's access-control section, never from
 * SecurityConfig. Deriving the expectations from the configuration under test would produce a
 * mirror that ratifies whatever that configuration currently says, including a mistake.
 *
 * <p>The PRD grants: residents create reports and read their own; office staff read every report
 * and change statuses; admins manage accounts plus everything staff can do. Admin is a superset
 * of <em>staff</em>, not of resident, so admins may not file reports. Reads of the resident
 * surface stay reporter-scoped for every role, which for staff and admin means an empty list —
 * their view of every report is the staff triage surface.
 */
public final class RouteAuthorizationTable {

    private RouteAuthorizationTable() {
    }

    public enum Identity { ANONYMOUS, RESIDENT, STAFF, ADMIN }

    /**
     * What the authorization layer did, not what the handler returned. A reporter-scoped 404 is
     * {@code GRANTED}: the request was authorized and the handler ran. Behavioural depth belongs
     * in the focused tests, not here.
     */
    public enum Outcome {
        GRANTED,
        FORBIDDEN,
        LOGIN_REDIRECT,
        UNAUTHORIZED
    }

    public record Route(RequestMethod method, String pattern) {
        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    public record Expectation(Route route, Identity identity, Outcome outcome) {
    }

    private static Route get(String pattern) {
        return new Route(RequestMethod.GET, pattern);
    }

    private static Route post(String pattern) {
        return new Route(RequestMethod.POST, pattern);
    }

    /**
     * Routes that never reach DispatcherServlet, so {@code getHandlerMethods()} cannot see them:
     * they are handled by filters inside the security chain. The handler map reports {@code /login}
     * as GET-only while the deployed application also accepts POST there.
     *
     * <p>This list is hand-maintained and that is a real limitation: a fourth filter-level route
     * added later would not be caught by the completeness assertion. Nothing in the framework
     * enumerates them.
     */
    public static final Set<Route> FILTER_LEVEL_ROUTES = Set.of(
        post("/login"),
        post("/logout"),
        post("/api/auth/logout")
    );

    /**
     * Derived routes that carry no per-identity expectation because the PRD makes them public.
     * They stay in the completeness check so that granting one a rule later forces a decision.
     */
    public static final Set<Route> PUBLIC_ROUTES = Set.of(
        get("/login"),
        get("/register"),
        post("/register"),
        post("/api/auth/login"),
        post("/api/auth/register")
    );

    /** Boot's error mapping. It accepts every method, so it is matched on pattern alone. */
    public static final String ERROR_PATTERN = "/error";

    private static final List<Expectation> EXPECTATIONS = new ArrayList<>();

    private static void expect(Route route, Outcome anonymous, Outcome resident, Outcome staff, Outcome admin) {
        EXPECTATIONS.add(new Expectation(route, Identity.ANONYMOUS, anonymous));
        EXPECTATIONS.add(new Expectation(route, Identity.RESIDENT, resident));
        EXPECTATIONS.add(new Expectation(route, Identity.STAFF, staff));
        EXPECTATIONS.add(new Expectation(route, Identity.ADMIN, admin));
    }

    static {
        Outcome granted = Outcome.GRANTED;
        Outcome forbidden = Outcome.FORBIDDEN;
        Outcome loginRedirect = Outcome.LOGIN_REDIRECT;

        // Home. Authenticated-only; no role distinction.
        expect(get("/"), loginRedirect, granted, granted, granted);

        // Reporting is a resident capability. Admin is a superset of staff, and staff cannot file.
        expect(get("/reports/new"), loginRedirect, granted, forbidden, forbidden);
        expect(post("/reports"), loginRedirect, granted, forbidden, forbidden);

        // Reading own reports. Reporter-scoped for every role, so staff and admin see their own
        // (none) rather than being refused.
        expect(get("/reports"), loginRedirect, granted, granted, granted);
        expect(get("/reports/{id}"), loginRedirect, granted, granted, granted);
        expect(get("/reports/{id}/photo"), loginRedirect, granted, granted, granted);

        // Triage surface. Staff and admin only. The matcher backing these rows must stay ahead of
        // anyRequest().authenticated() in SecurityConfig, which would otherwise shadow it and
        // admit every logged-in user.
        expect(get("/staff/reports"), loginRedirect, forbidden, granted, granted);
        expect(get("/staff/reports/{id}"), loginRedirect, forbidden, granted, granted);
        expect(get("/staff/reports/{id}/photo"), loginRedirect, forbidden, granted, granted);
        expect(post("/staff/reports/{id}/status"), loginRedirect, forbidden, granted, granted);

        // Account management. Admin only, and deliberately NOT mirroring the staff surface, which
        // admits STAFF and ADMIN alike — these rows are what catches someone "fixing" the matcher
        // for consistency. AdminUserController carries no @PreAuthorize by design, so the matcher
        // is the only thing protecting the two POST rows: narrowing it to GET would open account
        // creation and deactivation to staff.
        expect(get("/admin/users"), loginRedirect, forbidden, forbidden, granted);
        expect(get("/admin/users/new"), loginRedirect, forbidden, forbidden, granted);
        expect(post("/admin/users"), loginRedirect, forbidden, forbidden, granted);
        expect(post("/admin/users/{id}/active"), loginRedirect, forbidden, forbidden, granted);

        // The API chain answers 401 rather than redirecting.
        expect(get("/api/auth/me"), Outcome.UNAUTHORIZED, granted, granted, granted);
    }

    public static List<Expectation> expectations() {
        return List.copyOf(EXPECTATIONS);
    }

    /** Every route carrying a per-identity expectation. */
    public static Set<Route> authenticatedRoutes() {
        Set<Route> routes = new LinkedHashSet<>();
        EXPECTATIONS.forEach(expectation -> routes.add(expectation.route()));
        return routes;
    }
}
