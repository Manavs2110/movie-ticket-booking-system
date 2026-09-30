package com.moviebooking.controller.auth;

import com.moviebooking.common.CurrentUser;
import com.moviebooking.dto.auth.RegisterRequest;
import com.moviebooking.dto.auth.UserResponse;
import com.moviebooking.service.auth.AppUserDetailsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserDetailsService userService;

    public AuthController(AppUserDetailsService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return userService.register(request);
    }

    /** Confirms the credentials and returns the role, so a client can pick admin vs customer screens. */
    @GetMapping("/me")
    public UserResponse me() {
        return userService.get(CurrentUser.id());
    }
}
