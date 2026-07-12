package com.privatecloud.dto;

import java.time.Instant;

public class AlertRuleView {
    private Long id;
    private String name;
    private String metric;
    private String threshold;
    private String severity;
    private boolean installed;
    private boolean enabled;
    private Instant updatedAt;
    private String pendingAction;
    private String pendingStatus;

    public AlertRuleView(Long id, String name, String metric, String threshold, String severity,
                         boolean installed, boolean enabled, Instant updatedAt, String pendingAction, String pendingStatus) {
        this.id = id;
        this.name = name;
        this.metric = metric;
        this.threshold = threshold;
        this.severity = severity;
        this.installed = installed;
        this.enabled = enabled;
        this.updatedAt = updatedAt;
        this.pendingAction = pendingAction;
        this.pendingStatus = pendingStatus;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getMetric() {
        return metric;
    }

    public String getThreshold() {
        return threshold;
    }

    public String getSeverity() {
        return severity;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInstalled() {
        return installed;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getPendingAction() {
        return pendingAction;
    }

    public String getPendingStatus() {
        return pendingStatus;
    }
}
