package com.privatecloud.dto;

public class AlertHistoryCreateRequest {
    private String host;
    private String ruleName;
    private String metricName;
    private String alertState;
    private String previousState;
    private String thresholdType;
    private String level;
    private String status;
    private String reason;
    private String recommendation;
    private String decisionSource;
    private String feedbackStatus;
    private String feedbackComment;
    private Long durationSeconds;
    private Double confidenceScore;
    private String fingerprint;
    private String contextJson;
    private Double value;
    private Double thresholdValue;
    private Double staticThreshold;
    private Double dynamicThreshold;
    private Double meanValue;
    private Double stdValue;
    private Double trendValue;
    private String time;

    public AlertHistoryCreateRequest() {}

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
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

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }
}
