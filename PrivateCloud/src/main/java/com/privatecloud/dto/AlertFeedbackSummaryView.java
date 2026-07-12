package com.privatecloud.dto;

public class AlertFeedbackSummaryView {
    private String host;
    private String ruleName;
    private String metricName;
    private long totalCount;
    private long trueCount;
    private long falseCount;
    private long effectiveCount;
    private long falsePositiveCount;
    private long unresolvedCount;
    private double effectiveRate;
    private double falsePositiveRate;

    public AlertFeedbackSummaryView(
            String host,
            String ruleName,
            String metricName,
            long totalCount,
            long trueCount,
            long falseCount,
            long effectiveCount,
            long falsePositiveCount,
            long unresolvedCount,
            double effectiveRate,
            double falsePositiveRate
    ) {
        this.host = host;
        this.ruleName = ruleName;
        this.metricName = metricName;
        this.totalCount = totalCount;
        this.trueCount = trueCount;
        this.falseCount = falseCount;
        this.effectiveCount = effectiveCount;
        this.falsePositiveCount = falsePositiveCount;
        this.unresolvedCount = unresolvedCount;
        this.effectiveRate = effectiveRate;
        this.falsePositiveRate = falsePositiveRate;
    }

    public String getHost() {
        return host;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getMetricName() {
        return metricName;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public long getTrueCount() {
        return trueCount;
    }

    public long getFalseCount() {
        return falseCount;
    }

    public long getEffectiveCount() {
        return effectiveCount;
    }

    public long getFalsePositiveCount() {
        return falsePositiveCount;
    }

    public long getUnresolvedCount() {
        return unresolvedCount;
    }

    public double getEffectiveRate() {
        return effectiveRate;
    }

    public double getFalsePositiveRate() {
        return falsePositiveRate;
    }
}
