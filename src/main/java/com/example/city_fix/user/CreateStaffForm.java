package com.example.city_fix.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Constraints are deliberately identical to {@code RegisterRequest}, so an admin-created
 * staff account is held to exactly the same standard as a self-registered resident.
 */
public record CreateStaffForm(

    @NotBlank(message = "Email is required")
    @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "Invalid email format")
    String email,

    @NotNull(message = "Password must be at least 8 characters")
    @Size(min = 8, message = "Password must be at least 8 characters")
    String password
) {

    public CreateStaffForm() {
        this(null, null);
    }
}
