package com.privatecloud.dto;

import java.time.Instant;

public class AlertRuleRequestView {
    private Long id;
    private Long ruleId;
    private String ruleName;
    private String metric;
    private String threshold;
    private String severity;
    private String host;
    private String action;
    private String status;
    private String requestedBy;
    private Instant requestedAt;
    private String reviewedBy;
    private Instant reviewedAt;
    private String comment;

    public AlertRuleRequestView(Long id, Long ruleId, String ruleName, String metric, String threshold, String severity,
                                String host, String action, String status, String requestedBy, Instant requestedAt,
                                String reviewedBy, Instant reviewedAt, String comment) {
        this.id = id;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.metric = metric;
        this.threshold = threshold;
        this.severity = severity;
        this.host = host;
        this.action = action;
        this.status = status;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.comment = comment;
    }

    public Long getId() {
        return id;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public String getRuleName() {
        return ruleName;
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

    public String getHost() {
        return host;
    }

    public String getAction() {
        return action;
    }

    public String getStatus() {
        return status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getComment() {
        return comment;
    }
}
