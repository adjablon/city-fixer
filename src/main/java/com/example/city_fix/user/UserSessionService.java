package com.example.city_fix.user;

import com.example.city_fix.auth.CustomUserDetails;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

/**
 * Single owner of session eviction. Deactivating an account blocks future logins through
 * {@link CustomUserDetails#isEnabled()}; this ends the sessions the account already holds.
 */
@Service
public class UserSessionService {

    private static final Logger log = LoggerFactory.getLogger(UserSessionService.class);

    private final SessionRegistry sessionRegistry;

    public UserSessionService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Expires every live session held by the given user. Package-private: the admin service
     * is the only caller, and a public capability with no second caller is the pattern
     * flagged in S-01 F5 and S-02 F5.
     */
    void expireSessions(User user) {
        // The registry keys sessions by principal and looks them up by equality, which is
        // why CustomUserDetails carries value-based equals/hashCode. A principal rebuilt
        // from the same row is therefore an equal key to the one stored at login.
        List<SessionInformation> sessions =
            sessionRegistry.getAllSessions(new CustomUserDetails(user), false);

        for (SessionInformation session : sessions) {
            session.expireNow();
        }

        // Zero is the normal case — the user simply was not logged in anywhere.
        log.info("Expired {} session(s) for '{}'", sessions.size(), user.getEmail());
    }
}
