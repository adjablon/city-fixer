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

    /**
     * Trims the email before Bean Validation sees it. Without this the {@code @Pattern}
     * above — which excludes whitespace — rejects a pasted address with a trailing space
     * before the service ever gets the chance to normalise it, making this form stricter
     * than the register form an admin already uses. Lowercasing stays in the service, which
     * owns the write path (lessons.md).
     */
    public CreateStaffForm {
        email = email == null ? null : email.trim();
    }

    public CreateStaffForm() {
        this(null, null);
    }
}
