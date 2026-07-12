package com.privatecloud.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "alert_rule_requests")
public class AlertRuleRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id")
    @JsonIgnore
    private AlertRule rule;

    @Column(name = "rule_id", insertable = false, updatable = false)
    private Long ruleId;

    @Column(length = 128)
    private String host;

    @Column(nullable = false, length = 24)
    private String action;

    @Column(length = 120)
    private String ruleName;

    @Column(length = 80)
    private String metric;

    @Column(length = 120)
    private String threshold;

    @Column(length = 20)
    private String severity;

    @Column(nullable = false, length = 24)
    private String status = "PENDING";

    @Column(nullable = false)
    private Long requestedBy;

    @Column(nullable = false)
    private Instant requestedAt = Instant.now();

    private Long reviewedBy;

    private Instant reviewedAt;

    @Column(length = 200)
    private String comment;

    public AlertRuleRequest() {}

    public AlertRuleRequest(AlertRule rule, String host, String action, Long requestedBy) {
        this.rule = rule;
        this.host = host;
        this.action = action;
        this.requestedBy = requestedBy;
    }

    public AlertRuleRequest(String ruleName, String metric, String threshold, String action, Long requestedBy) {
        this.ruleName = ruleName;
        this.metric = metric;
        this.threshold = threshold;
        this.action = action;
        this.requestedBy = requestedBy;
    }

    public AlertRuleRequest(String ruleName, String metric, String threshold, String severity, String action, Long requestedBy) {
        this.ruleName = ruleName;
        this.metric = metric;
        this.threshold = threshold;
        this.severity = severity;
        this.action = action;
        this.requestedBy = requestedBy;
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

    public Long getRuleId() {
        return ruleId;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public String getThreshold() {
        return threshold;
    }

    public void setThreshold(String threshold) {
        this.threshold = threshold;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
