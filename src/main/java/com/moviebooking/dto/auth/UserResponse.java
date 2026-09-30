package com.moviebooking.dto.auth;

import com.moviebooking.model.auth.Role;
import com.moviebooking.model.auth.User;

public record UserResponse(Long id, String email, String fullName, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
