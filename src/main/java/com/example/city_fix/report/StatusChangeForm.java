package com.example.city_fix.report;

import jakarta.validation.constraints.NotNull;

public record StatusChangeForm(

    @NotNull(message = "Please choose a status.")
    ReportStatus status
) {
}
