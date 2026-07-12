package com.privatecloud.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "alert_history",
        indexes = {
                @Index(name = "idx_alert_history_host_occurred_at", columnList = "host,occurred_at"),
                @Index(name = "idx_alert_history_level_status_occurred_at", columnList = "level,status,occurred_at"),
                @Index(name = "idx_alert_history_rule_metric", columnList = "rule_name,metric_name")
        }
)
public class AlertHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String host;

    private Long ruleId;

    @Column(nullable = false, length = 120)
    private String ruleName;

    @Column(length = 64)
    private String metricName;

    @Column(length = 20)
    private String alertState;

    @Column(length = 20)
    private String previousState;

    @Column(length = 20)
    private String thresholdType;

    @Column(nullable = false, length = 20)
    private String level;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 500)
    private String reason;

    @Column(length = 500)
    private String recommendation;

    @Column(length = 40)
    private String decisionSource;

    @Column(length = 24)
    private String feedbackStatus;

    @Column(length = 24)
    private String feedbackSource;

    @Column(length = 300)
    private String feedbackComment;

    private Long durationSeconds;

    private Double confidenceScore;

    @Column(length = 160)
    private String fingerprint;

    private Instant feedbackAt;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String contextJson;

    private Double value;
    private Double thresholdValue;
    private Double staticThreshold;
    private Double dynamicThreshold;
    private Double meanValue;
    private Double stdValue;
    private Double trendValue;

    @Column(length = 20)
    private String agentStatus;

    @Column(length = 120)
    private String agentModel;

    private Double agentRiskScore;

    @Column(length = 500)
    private String agentPrediction;

    @Column(length = 1200)
    private String agentAnalysis;

    @Column(length = 1200)
    private String agentRecommendation;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String agentRawResponse;

    private Instant agentUpdatedAt;

    @Column(nullable = false)
    private Instant occurredAt = Instant.now();

    public AlertHistory() {}

    public AlertHistory(String host, Long ruleId, String ruleName, String level, String status, String reason, Double value, Instant occurredAt) {
        this.host = host;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.level = level;
        this.status = status;
        this.reason = reason;
        this.value = value;
        if (occurredAt != null) {
            this.occurredAt = occurredAt;
        }
    }

    public Long getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public String getAlertState() {
        return alertState;
    }

    public void setAlertState(String alertState) {
        this.alertState = alertState;
    }

    public String getPreviousState() {
        return previousState;
    }

    public void setPreviousState(String previousState) {
        this.previousState = previousState;
    }

    public String getThresholdType() {
        return thresholdType;
    }

    public void setThresholdType(String thresholdType) {
        this.thresholdType = thresholdType;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    public String getDecisionSource() {
        return decisionSource;
    }

    public void setDecisionSource(String decisionSource) {
        this.decisionSource = decisionSource;
    }

    public String getFeedbackStatus() {
        return feedbackStatus;
    }

    public void setFeedbackStatus(String feedbackStatus) {
        this.feedbackStatus = feedbackStatus;
    }

    public String getFeedbackSource() {
        return feedbackSource;
    }

    public void setFeedbackSource(String feedbackSource) {
        this.feedbackSource = feedbackSource;
    }

    public String getFeedbackComment() {
        return feedbackComment;
    }

    public void setFeedbackComment(String feedbackComment) {
        this.feedbackComment = feedbackComment;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(Double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public Instant getFeedbackAt() {
        return feedbackAt;
    }

    public void setFeedbackAt(Instant feedbackAt) {
        this.feedbackAt = feedbackAt;
    }

    public String getContextJson() {
        return contextJson;
    }

    public void setContextJson(String contextJson) {
        this.contextJson = contextJson;
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }

    public Double getThresholdValue() {
        return thresholdValue;
    }

    public void setThresholdValue(Double thresholdValue) {
        this.thresholdValue = thresholdValue;
    }

    public Double getStaticThreshold() {
        return staticThreshold;
    }

    public void setStaticThreshold(Double staticThreshold) {
        this.staticThreshold = staticThreshold;
    }

    public Double getDynamicThreshold() {
        return dynamicThreshold;
    }

    public void setDynamicThreshold(Double dynamicThreshold) {
        this.dynamicThreshold = dynamicThreshold;
    }

    public Double getMeanValue() {
        return meanValue;
    }

    public void setMeanValue(Double meanValue) {
        this.meanValue = meanValue;
    }

    public Double getStdValue() {
        return stdValue;
    }

    public void setStdValue(Double stdValue) {
        this.stdValue = stdValue;
    }

    public Double getTrendValue() {
        return trendValue;
    }

    public void setTrendValue(Double trendValue) {
        this.trendValue = trendValue;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getAgentStatus() {
        return agentStatus;
    }

    public void setAgentStatus(String agentStatus) {
        this.agentStatus = agentStatus;
    }

    public String getAgentModel() {
        return agentModel;
    }

    public void setAgentModel(String agentModel) {
        this.agentModel = agentModel;
    }

    public Double getAgentRiskScore() {
        return agentRiskScore;
    }

    public void setAgentRiskScore(Double agentRiskScore) {
        this.agentRiskScore = agentRiskScore;
    }

    public String getAgentPrediction() {
        return agentPrediction;
    }

    public void setAgentPrediction(String agentPrediction) {
        this.agentPrediction = agentPrediction;
    }

    public String getAgentAnalysis() {
        return agentAnalysis;
    }

    public void setAgentAnalysis(String agentAnalysis) {
        this.agentAnalysis = agentAnalysis;
    }

    public String getAgentRecommendation() {
        return agentRecommendation;
    }

    public void setAgentRecommendation(String agentRecommendation) {
        this.agentRecommendation = agentRecommendation;
    }

    public String getAgentRawResponse() {
        return agentRawResponse;
    }

    public void setAgentRawResponse(String agentRawResponse) {
        this.agentRawResponse = agentRawResponse;
    }

    public Instant getAgentUpdatedAt() {
        return agentUpdatedAt;
    }

    public void setAgentUpdatedAt(Instant agentUpdatedAt) {
        this.agentUpdatedAt = agentUpdatedAt;
    }
}
