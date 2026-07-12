package com.privatecloud.dto;

import java.time.Instant;
import java.util.List;

public class UserResponse {
    private Long id;
    private String username;
    private boolean enabled;
    private Instant createdAt;
    private List<String> roles;
    private List<String> serverHosts;

    public UserResponse(Long id, String username, boolean enabled, Instant createdAt, List<String> roles, List<String> serverHosts) {
        this.id = id;
        this.username = username;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.roles = roles;
        this.serverHosts = serverHosts;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<String> getRoles() {
        return roles;
    }

    public List<String> getServerHosts() {
        return serverHosts;
    }
}
