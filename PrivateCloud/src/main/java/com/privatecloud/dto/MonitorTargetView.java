package com.privatecloud.dto;

import java.time.Instant;

public class MonitorTargetView {
    private String host;
    private String role;
    private boolean enabled;
    private Instant updatedAt;

    public MonitorTargetView(String host, String role, boolean enabled, Instant updatedAt) {
        this.host = host;
        this.role = role;
        this.enabled = enabled;
        this.updatedAt = updatedAt;
    }

    public String getHost() {
        return host;
    }

    public String getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
