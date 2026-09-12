package com.example.city_fix.user;

/**
 * Shared test fixtures for the user package.
 *
 * <p>Seeding a real id matters: {@code CustomUserDetails.equals} compares only the id, so two
 * users built without one are equal to each other. Assertions about principal identity are
 * silently vacuous unless the fixture carries a distinct id.
 */
final class UserFixtures {

    private UserFixtures() {
    }

    static User persistedUser(Long id, Role role) {
        return persistedUser(id, "user" + id + "@example.com", role);
    }

    static User persistedUser(Long id, String email, Role role) {
        User user = new User(email, "hash", role);
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not seed the test user id", e);
        }
        return user;
    }
}
