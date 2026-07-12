package com.privatecloud.security;

import java.util.List;

public class AuthSession {
    private final Long userId;
    private final String username;
    private final List<String> roles;
    private final List<String> allowedHosts;

    public AuthSession(Long userId, String username, List<String> roles, List<String> allowedHosts) {
        this.userId = userId;
        this.username = username;
        this.roles = roles;
        this.allowedHosts = allowedHosts;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public List<String> getRoles() {
        return roles;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }
}
