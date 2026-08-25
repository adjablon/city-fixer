package com.example.city_fix.auth;

import com.example.city_fix.user.User;

public record UserResponse(Long id, String email, String role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole().name());
    }

    public static UserResponse from(CustomUserDetails userDetails) {
        return new UserResponse(userDetails.getId(), userDetails.getEmail(), userDetails.getRole().name());
    }
}
