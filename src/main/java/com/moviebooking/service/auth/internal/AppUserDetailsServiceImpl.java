package com.moviebooking.service.auth.internal;

import com.moviebooking.common.AppException;
import com.moviebooking.common.ErrorCode;
import com.moviebooking.dto.auth.RegisterRequest;
import com.moviebooking.dto.auth.UserResponse;
import com.moviebooking.model.auth.AppUserPrincipal;
import com.moviebooking.model.auth.Role;
import com.moviebooking.model.auth.User;
import com.moviebooking.repository.auth.UserRepository;
import com.moviebooking.service.auth.AppUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AppUserDetailsServiceImpl implements AppUserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AppUserDetailsServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByEmail(normalize(username))
                .map(AppUserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }

    /** Public registration always creates a CUSTOMER; the admin comes from the Flyway seed. */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.EMAIL_TAKEN, "Email is already registered");
        }
        User user = userRepository.save(new User(email, passwordEncoder.encode(request.password()),
                request.fullName().trim(), Role.CUSTOMER));
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        return userRepository.findById(id).map(UserResponse::from)
                .orElseThrow(() -> AppException.notFound("User", id));
    }

    @Transactional(readOnly = true)
    public UserContact contact(Long id) {
        User user = userRepository.findById(id).orElseThrow(() -> AppException.notFound("User", id));
        return new UserContact(user.getId(), user.getEmail(), user.getFullName());
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
