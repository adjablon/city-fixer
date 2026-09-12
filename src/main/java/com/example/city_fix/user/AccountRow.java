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

    public static AccountRow from(UserRepository.AccountSummary summary) {
        return new AccountRow(
            summary.getId(),
            summary.getEmail(),
            summary.getRole().getLabel(),
            // Same null-means-active tolerance as User.isActive(): rows predating the
            // backfill read as active here too.
            summary.getActive() == null || summary.getActive(),
            summary.getCreatedAt());
    }
}
