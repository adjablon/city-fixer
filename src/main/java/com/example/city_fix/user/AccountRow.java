package com.example.city_fix.user;

import java.time.Instant;

/**
 * Flat view model for the admin account list. Deliberately carries no password field — the
 * entity's hash must not reach the view layer.
 */
public record AccountRow(
    Long id,
    String email,
    String roleLabel,
    boolean active,
    Instant createdAt
) {

    public static AccountRow from(User user) {
        return new AccountRow(
            user.getId(),
            user.getEmail(),
            user.getRole().getLabel(),
            user.isActive(),
            user.getCreatedAt());
    }
}
