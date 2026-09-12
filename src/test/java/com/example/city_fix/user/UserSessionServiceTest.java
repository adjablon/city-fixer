package com.example.city_fix.user;

import static com.example.city_fix.user.UserFixtures.persistedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.city_fix.auth.CustomUserDetails;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

/**
 * Unit-level coverage of the eviction call. The registry is mocked here, so these tests
 * cannot prove that a real {@code SessionRegistry} resolves the principal — that is what
 * {@code AccountDeactivationTest} does against the actual registry, and it is the stronger
 * evidence. What these pin is that the service asks for the right principal and expires
 * everything it gets back.
 */
@ExtendWith(MockitoExtension.class)
class UserSessionServiceTest {

    @Mock
    private SessionRegistry sessionRegistry;

    @Test
    void expireSessions_expiresEveryLiveSessionOfThatUser() {
        User user = persistedUser(7L, "staff@example.com", Role.STAFF);
        // Stubbed on the EXACT principal, not any(): a lookup for the wrong user must fall
        // through to Mockito's empty default and fail the assertions below, rather than
        // being handed these sessions regardless.
        SessionInformation first = liveSession("session-1", user);
        SessionInformation second = liveSession("session-2", user);
        when(sessionRegistry.getAllSessions(eq(new CustomUserDetails(user)), eq(false)))
            .thenReturn(List.of(first, second));

        new UserSessionService(sessionRegistry).expireSessions(user);

        assertThat(first.isExpired()).isTrue();
        assertThat(second.isExpired()).isTrue();
    }

    @Test
    void expireSessions_looksUpTheRegistryByThatUsersPrincipalOnly() {
        User user = persistedUser(7L, "staff@example.com", Role.STAFF);
        User otherUser = persistedUser(8L, "other@example.com", Role.STAFF);
        when(sessionRegistry.getAllSessions(eq(new CustomUserDetails(user)), eq(false)))
            .thenReturn(List.of());

        new UserSessionService(sessionRegistry).expireSessions(user);

        // Positive and negative halves together are what give this teeth. Without the
        // negative, any id-less fixture would satisfy the equality and a wrong-user lookup
        // would go unnoticed — the whole eviction mechanism rests on this equality holding.
        verify(sessionRegistry).getAllSessions(eq(new CustomUserDetails(user)), eq(false));
        verify(sessionRegistry, org.mockito.Mockito.never())
            .getAllSessions(eq(new CustomUserDetails(otherUser)), eq(false));
        // Expired sessions are excluded at the query, so they cannot be re-expired.
        verifyNoMoreInteractions(sessionRegistry);
    }

    @Test
    void expireSessions_withNoLiveSessions_touchesNothingElse() {
        User user = persistedUser(9L, "never-logged-in@example.com", Role.RESIDENT);
        when(sessionRegistry.getAllSessions(eq(new CustomUserDetails(user)), eq(false)))
            .thenReturn(List.of());

        new UserSessionService(sessionRegistry).expireSessions(user);

        // A user who was never logged in is the normal case, not an error: one lookup,
        // no further calls, no throw.
        verify(sessionRegistry).getAllSessions(eq(new CustomUserDetails(user)), eq(false));
        verifyNoMoreInteractions(sessionRegistry);
    }

    private static SessionInformation liveSession(String sessionId, User user) {
        return new SessionInformation(new CustomUserDetails(user), sessionId, new Date());
    }
}
