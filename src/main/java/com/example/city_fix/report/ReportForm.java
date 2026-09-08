package com.example.city_fix.report;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReportForm(

    @NotNull(message = "Place a pin on the map to set the location.")
    @DecimalMin(value = "-90", message = "Latitude must be between -90 and 90.")
    @DecimalMax(value = "90", message = "Latitude must be between -90 and 90.")
    Double latitude,

    @NotNull(message = "Place a pin on the map to set the location.")
    @DecimalMin(value = "-180", message = "Longitude must be between -180 and 180.")
    @DecimalMax(value = "180", message = "Longitude must be between -180 and 180.")
    Double longitude,

    @NotBlank(message = "Please describe the problem.")
    @Size(max = 2000, message = "Description must be 2000 characters or fewer.")
    String description,

    @NotNull(message = "Please choose a category.")
    Category category
) {
}
