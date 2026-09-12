package com.example.city_fix.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class UserTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD_HASH = "$2a$10$hash";

    @Test
    void newUser_isActive() {
        User user = new User(EMAIL, PASSWORD_HASH, Role.RESIDENT);

        assertThat(user.isActive()).isTrue();
    }

    @Test
    void deactivate_makesUserInactive() {
        User user = new User(EMAIL, PASSWORD_HASH, Role.STAFF);

        user.deactivate();

        assertThat(user.isActive()).isFalse();
    }

    @Test
    void activate_restoresDeactivatedUser() {
        User user = new User(EMAIL, PASSWORD_HASH, Role.STAFF);
        user.deactivate();

        user.activate();

        assertThat(user.isActive()).isTrue();
    }

    @Test
    void activate_isIdempotentOnAnAlreadyActiveUser() {
        User user = new User(EMAIL, PASSWORD_HASH, Role.RESIDENT);

        user.activate();

        assertThat(user.isActive()).isTrue();
    }

    @Test
    void nullActiveColumn_readsAsActive() throws Exception {
        // Rows written before the backfill have a null active column. Reflection stands in
        // for loading such a row, since the constructor always sets the flag.
        User user = new User(EMAIL, PASSWORD_HASH, Role.RESIDENT);
        setActiveToNull(user);

        assertThat(user.isActive()).isTrue();
    }

    @Test
    void nullActiveColumn_canStillBeDeactivated() throws Exception {
        User user = new User(EMAIL, PASSWORD_HASH, Role.STAFF);
        setActiveToNull(user);

        user.deactivate();

        assertThat(user.isActive()).isFalse();
    }

    private static void setActiveToNull(User user) throws Exception {
        Field active = User.class.getDeclaredField("active");
        active.setAccessible(true);
        active.set(user, null);
    }
}
