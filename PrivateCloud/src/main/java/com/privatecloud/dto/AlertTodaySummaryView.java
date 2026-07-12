package com.privatecloud.dto;

import java.time.Instant;

public class AlertTodaySummaryView {
    private Instant startTime;
    private Instant endTime;
    private long totalCount;
    private long firingCount;
    private long resolvedCount;
    private long criticalCount;
    private long alertCount;
    private long warningCount;
    private long effectiveCount;
    private long falsePositiveCount;
    private long feedbackTotalCount;
    private double feedbackCoverageRate;
    private double effectiveRate;

    public AlertTodaySummaryView(
            Instant startTime,
            Instant endTime,
            long totalCount,
            long firingCount,
            long resolvedCount,
            long criticalCount,
            long alertCount,
            long warningCount,
            long effectiveCount,
            long falsePositiveCount,
            long feedbackTotalCount,
            double feedbackCoverageRate,
            double effectiveRate
    ) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.totalCount = totalCount;
        this.firingCount = firingCount;
        this.resolvedCount = resolvedCount;
        this.criticalCount = criticalCount;
        this.alertCount = alertCount;
        this.warningCount = warningCount;
        this.effectiveCount = effectiveCount;
        this.falsePositiveCount = falsePositiveCount;
        this.feedbackTotalCount = feedbackTotalCount;
        this.feedbackCoverageRate = feedbackCoverageRate;
        this.effectiveRate = effectiveRate;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public long getFiringCount() {
        return firingCount;
    }

    public long getResolvedCount() {
        return resolvedCount;
    }

    public long getCriticalCount() {
        return criticalCount;
    }

    public long getAlertCount() {
        return alertCount;
    }

    public long getWarningCount() {
        return warningCount;
    }

    public long getEffectiveCount() {
        return effectiveCount;
    }

    public long getFalsePositiveCount() {
        return falsePositiveCount;
    }

    public long getFeedbackTotalCount() {
        return feedbackTotalCount;
    }

    public double getFeedbackCoverageRate() {
        return feedbackCoverageRate;
    }

    public double getEffectiveRate() {
        return effectiveRate;
    }
}
