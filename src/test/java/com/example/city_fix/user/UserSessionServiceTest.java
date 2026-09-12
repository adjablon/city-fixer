package com.example.city_fix.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.city_fix.auth.CustomUserDetails;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

@ExtendWith(MockitoExtension.class)
class UserSessionServiceTest {

    @Mock
    private SessionRegistry sessionRegistry;

    @Test
    void expireSessions_expiresEveryLiveSessionOfThatUser() {
        User user = new User("staff@example.com", "hash", Role.STAFF);
        SessionInformation first = liveSession("session-1", user);
        SessionInformation second = liveSession("session-2", user);
        when(sessionRegistry.getAllSessions(any(), eq(false))).thenReturn(List.of(first, second));

        new UserSessionService(sessionRegistry).expireSessions(user);

        assertThat(first.isExpired()).isTrue();
        assertThat(second.isExpired()).isTrue();
    }

    @Test
    void expireSessions_looksUpByThePrincipalForThatUser() {
        User user = new User("staff@example.com", "hash", Role.STAFF);
        when(sessionRegistry.getAllSessions(any(), eq(false))).thenReturn(List.of());

        new UserSessionService(sessionRegistry).expireSessions(user);

        // The registry matches principals by equality, so the key must be a CustomUserDetails
        // equal to the one stored at login — not the User entity itself.
        ArgumentCaptor<Object> principal = ArgumentCaptor.forClass(Object.class);
        verify(sessionRegistry).getAllSessions(principal.capture(), eq(false));
        assertThat(principal.getValue())
            .isInstanceOf(CustomUserDetails.class)
            .isEqualTo(new CustomUserDetails(user));
    }

    @Test
    void expireSessions_asksTheRegistryToExcludeAlreadyExpiredSessions() {
        User user = new User("staff@example.com", "hash", Role.STAFF);
        when(sessionRegistry.getAllSessions(any(), eq(false))).thenReturn(List.of());

        new UserSessionService(sessionRegistry).expireSessions(user);

        // includeExpired=false: expired sessions are never fetched, so they cannot be
        // re-expired and their timestamps are left alone.
        verify(sessionRegistry).getAllSessions(any(), eq(false));
        verify(sessionRegistry, never()).getAllSessions(any(), eq(true));
    }

    @Test
    void expireSessions_withNoLiveSessions_isANoOp() {
        User user = new User("never-logged-in@example.com", "hash", Role.RESIDENT);
        when(sessionRegistry.getAllSessions(any(), eq(false))).thenReturn(List.of());

        assertThatCode(() -> new UserSessionService(sessionRegistry).expireSessions(user))
            .doesNotThrowAnyException();
    }

    private static SessionInformation liveSession(String sessionId, User user) {
        return new SessionInformation(new CustomUserDetails(user), sessionId, new Date());
    }
}
