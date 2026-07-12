package com.privatecloud.dto;

public class AlertRuleAssignmentRequest {
    private String host;
    private boolean enabled;

    public AlertRuleAssignmentRequest() {}

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
}
