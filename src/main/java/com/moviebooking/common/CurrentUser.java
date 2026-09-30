package com.moviebooking.common;

import com.moviebooking.model.auth.AppUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static AppUserPrincipal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Not authenticated");
        }
        return principal;
    }

    public static Long id() {
        return principal().id();
    }
}
