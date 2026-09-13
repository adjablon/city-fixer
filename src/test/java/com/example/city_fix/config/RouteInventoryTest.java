package com.example.city_fix.config;

import com.example.city_fix.IntegrationTest;
import com.example.city_fix.config.RouteAuthorizationTable.Route;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Makes it impossible to add a route without an authorization decision.
 *
 * <p>The route list is derived from Spring's own handler mapping, so it cannot drift out of date
 * the way a hand-written checklist does. The expected outcomes are authored separately in
 * {@link RouteAuthorizationTable} — Spring exposes no way to enumerate the configured
 * authorization rules, and deriving the expectations from the configuration under test would
 * produce a mirror test.
 */
class RouteInventoryTest extends IntegrationTest {

    // Actuator's mapping is a sibling type rather than a subtype, so qualifying by bean name
    // keeps this to the application's own handler methods.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyDerivedRouteCarriesAnAuthorizationDecision() {
        Set<Route> undecided = new TreeSet<>(
            (left, right) -> left.toString().compareTo(right.toString()));
        undecided.addAll(derivedRoutes());
        undecided.removeAll(RouteAuthorizationTable.authenticatedRoutes());
        undecided.removeAll(RouteAuthorizationTable.PUBLIC_ROUTES);
        undecided.removeIf(route -> RouteAuthorizationTable.ERROR_PATTERN.equals(route.pattern()));

        assertThat(undecided)
            .as("""
                These routes are exposed by the application but carry no authorization decision.

                Add each one to RouteAuthorizationTable: either an expect(...) row naming what \
                every identity may do with it, or PUBLIC_ROUTES if the PRD makes it public. \
                Do not delete this assertion to make the build pass — an undecided route is \
                exactly the gap this test exists to catch.""")
            .isEmpty();
    }

    @Test
    void derivationSeesMappingsThatRestrictNoMethod() {
        // Boot's error controller maps every method. Reading an empty methods condition as
        // "no methods" rather than "all methods" would silently drop it, and the same mistake
        // would drop any future mapping written without a method restriction.
        assertThat(derivedRoutes())
            .extracting(Route::pattern)
            .contains(RouteAuthorizationTable.ERROR_PATTERN);
    }

    private Set<Route> derivedRoutes() {
        Set<Route> routes = new LinkedHashSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
            Set<RequestMethod> effective = declared.isEmpty()
                ? Set.of(RequestMethod.values())
                : declared;
            for (String pattern : info.getPatternValues()) {
                for (RequestMethod method : effective) {
                    routes.add(new Route(method, pattern));
                }
            }
        }
        return routes;
    }

    @Test
    void filterLevelRoutesAreRecordedEvenThoughTheyCannotBeDerived() {
        // POST /login, POST /logout and POST /api/auth/logout are handled by filters inside the
        // security chain and never reach DispatcherServlet, so the derivation cannot see them.
        // This pins the blind spot rather than leaving a reader to discover it.
        Set<Route> derived = derivedRoutes();

        assertThat(RouteAuthorizationTable.FILTER_LEVEL_ROUTES)
            .as("the hand-maintained supplement must stay non-empty")
            .isNotEmpty()
            .allSatisfy(route -> assertThat(derived)
                .as("%s became derivable; move it out of the supplement", route)
                .doesNotContain(route));
    }

    @Test
    void loginIsDerivedAsGetOnlyEvenThoughTheApplicationAlsoAcceptsPost() {
        // The sharpest statement of why the supplement exists: the handler map reports /login as
        // GET-only because the form-login filter owns the POST. Anyone trusting the derivation
        // alone would conclude that POST /login does not exist.
        Set<Route> derived = derivedRoutes();

        assertThat(derived).contains(new Route(RequestMethod.GET, "/login"));
        assertThat(derived).doesNotContain(new Route(RequestMethod.POST, "/login"));
    }
}
