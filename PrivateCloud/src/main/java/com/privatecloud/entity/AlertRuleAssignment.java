package com.privatecloud.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "alert_rule_assignments",
        uniqueConstraints = @UniqueConstraint(name = "uk_rule_host", columnNames = {"rule_id", "host"})
)
public class AlertRuleAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    @JsonIgnore
    private AlertRule rule;

    @Column(nullable = false, length = 128)
    private String host;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public AlertRuleAssignment() {}

    public AlertRuleAssignment(AlertRule rule, String host, boolean enabled) {
        this.rule = rule;
        this.host = host;
        this.enabled = enabled;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public AlertRule getRule() {
        return rule;
    }

    public void setRule(AlertRule rule) {
        this.rule = rule;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
