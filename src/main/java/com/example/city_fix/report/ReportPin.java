package com.example.city_fix.report;

/**
 * One report as the staff map needs it. Deliberately carries no description and no reporter:
 * the payload is unbounded and rendered into the page, so user-supplied text stays out of it.
 *
 * @param status the raw enum name, used by the script to pick a marker colour
 */
public record ReportPin(
    Long id,
    double latitude,
    double longitude,
    String status,
    String statusLabel,
    String categoryLabel,
    String createdAt) {
}
