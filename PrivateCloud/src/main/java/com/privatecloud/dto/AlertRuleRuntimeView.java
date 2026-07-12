package com.privatecloud.dto;

public class AlertRuleRuntimeView {
    private Long id;
    private String name;
    private String metric;
    private String metricKey;
    private String threshold;
    private String severity;
    private Double thresholdValue;

    public AlertRuleRuntimeView(Long id, String name, String metric, String metricKey, String threshold,
                                String severity, Double thresholdValue) {
        this.id = id;
        this.name = name;
        this.metric = metric;
        this.metricKey = metricKey;
        this.threshold = threshold;
        this.severity = severity;
        this.thresholdValue = thresholdValue;
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

    public String getMetricKey() {
        return metricKey;
    }

    public String getThreshold() {
        return threshold;
    }

    public String getSeverity() {
        return severity;
    }

    public Double getThresholdValue() {
        return thresholdValue;
    }
}
