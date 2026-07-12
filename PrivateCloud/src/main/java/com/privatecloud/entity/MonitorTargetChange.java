package com.privatecloud.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "monitor_target_changes")
public class MonitorTargetChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String host;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 64)
    private String changedBy;

    @Column(nullable = false)
    private Instant changedAt = Instant.now();

    public MonitorTargetChange() {}

    public MonitorTargetChange(String host, String role, boolean enabled, String changedBy) {
        this.host = host;
        this.role = role;
        this.enabled = enabled;
        this.changedBy = changedBy;
    }

    public Long getId() {
        return id;
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
