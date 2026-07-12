package com.privatecloud.dto;

import java.time.Instant;

public class MonitorTargetChangeView {
    private String host;
    private String role;
    private boolean enabled;
    private String changedBy;
    private Instant changedAt;

    public MonitorTargetChangeView(String host, String role, boolean enabled, String changedBy, Instant changedAt) {
        this.host = host;
        this.role = role;
        this.enabled = enabled;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
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

    public String getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
