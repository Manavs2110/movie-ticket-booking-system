package com.moviebooking.service.auth;

import com.moviebooking.dto.auth.RegisterRequest;
import com.moviebooking.dto.auth.UserResponse;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

public interface AppUserDetailsService extends UserDetailsService {

    record UserContact(Long id, String email, String fullName) {
    }

    UserDetails loadUserByUsername(String username);

    /** Public registration always creates a CUSTOMER; the admin comes from the Flyway seed. */
    UserResponse register(RegisterRequest request);

    UserResponse get(Long id);

    UserContact contact(Long id);
}
