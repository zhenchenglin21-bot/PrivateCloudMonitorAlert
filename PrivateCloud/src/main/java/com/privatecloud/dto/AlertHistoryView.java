package com.privatecloud.dto;

import java.time.Instant;

public class AlertHistoryView {
    private Long id;
    private Instant time;
    private String level;
    private String rule;
    private String metricName;
    private String alertState;
    private String previousState;
    private String thresholdType;
    private String host;
    private String status;
    private String reason;
    private String recommendation;
    private String decisionSource;
    private String feedbackStatus;
    private String feedbackSource;
    private String feedbackComment;
    private Long durationSeconds;
    private Double confidenceScore;
    private Double value;
    private Double thresholdValue;
    private Double staticThreshold;
    private Double dynamicThreshold;
    private Double meanValue;
    private Double stdValue;
    private Double trendValue;
    private String agentStatus;
    private String agentModel;
    private Double agentRiskScore;
    private String agentPrediction;
    private String agentAnalysis;
    private String agentRecommendation;
    private String agentRawResponse;
    private Instant agentUpdatedAt;

    public AlertHistoryView(Long id, Instant time, String level, String rule, String metricName, String alertState,
                            String previousState, String thresholdType, String host, String status, String reason,
                            String recommendation, String decisionSource, String feedbackStatus,
                            String feedbackSource, String feedbackComment,
                            Long durationSeconds, Double confidenceScore, Double value, Double thresholdValue,
                            Double staticThreshold, Double dynamicThreshold, Double meanValue, Double stdValue,
                            Double trendValue, String agentStatus, String agentModel, Double agentRiskScore,
                            String agentPrediction, String agentAnalysis, String agentRecommendation,
                            String agentRawResponse, Instant agentUpdatedAt) {
        this.id = id;
        this.time = time;
        this.level = level;
        this.rule = rule;
        this.metricName = metricName;
        this.alertState = alertState;
        this.previousState = previousState;
        this.thresholdType = thresholdType;
        this.host = host;
        this.status = status;
        this.reason = reason;
        this.recommendation = recommendation;
        this.decisionSource = decisionSource;
        this.feedbackStatus = feedbackStatus;
        this.feedbackSource = feedbackSource;
        this.feedbackComment = feedbackComment;
        this.durationSeconds = durationSeconds;
        this.confidenceScore = confidenceScore;
        this.value = value;
        this.thresholdValue = thresholdValue;
        this.staticThreshold = staticThreshold;
        this.dynamicThreshold = dynamicThreshold;
        this.meanValue = meanValue;
        this.stdValue = stdValue;
        this.trendValue = trendValue;
        this.agentStatus = agentStatus;
        this.agentModel = agentModel;
        this.agentRiskScore = agentRiskScore;
        this.agentPrediction = agentPrediction;
        this.agentAnalysis = agentAnalysis;
        this.agentRecommendation = agentRecommendation;
        this.agentRawResponse = agentRawResponse;
        this.agentUpdatedAt = agentUpdatedAt;
    }

    public Long getId() {
        return id;
    }

    public Instant getTime() {
        return time;
    }

    public String getLevel() {
        return level;
    }

    public String getRule() {
        return rule;
    }

    public String getMetricName() {
        return metricName;
    }

    public String getAlertState() {
        return alertState;
    }

    public String getPreviousState() {
        return previousState;
    }

    public String getThresholdType() {
        return thresholdType;
    }

    public String getHost() {
        return host;
    }

    public String getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public String getDecisionSource() {
        return decisionSource;
    }

    public String getFeedbackStatus() {
        return feedbackStatus;
    }

    public String getFeedbackSource() {
        return feedbackSource;
    }

    public String getFeedbackComment() {
        return feedbackComment;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public Double getConfidenceScore() {
        return confidenceScore;
    }

    public Double getValue() {
        return value;
    }

    public Double getThresholdValue() {
        return thresholdValue;
    }

    public Double getStaticThreshold() {
        return staticThreshold;
    }

    public Double getDynamicThreshold() {
        return dynamicThreshold;
    }

    public Double getMeanValue() {
        return meanValue;
    }

    public Double getStdValue() {
        return stdValue;
    }

    public Double getTrendValue() {
        return trendValue;
    }

    public String getAgentStatus() {
        return agentStatus;
    }

    public String getAgentModel() {
        return agentModel;
    }

    public Double getAgentRiskScore() {
        return agentRiskScore;
    }

    public String getAgentPrediction() {
        return agentPrediction;
    }

    public String getAgentAnalysis() {
        return agentAnalysis;
    }

    public String getAgentRecommendation() {
        return agentRecommendation;
    }

    public String getAgentRawResponse() {
        return agentRawResponse;
    }

    public Instant getAgentUpdatedAt() {
        return agentUpdatedAt;
    }
}
