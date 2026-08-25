package com.example.city_fix.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "Email is required")
    @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "Invalid email format")
    String email,

    @NotNull(message = "Password must be at least 8 characters")
    @Size(min = 8, message = "Password must be at least 8 characters")
    String password
) {}
