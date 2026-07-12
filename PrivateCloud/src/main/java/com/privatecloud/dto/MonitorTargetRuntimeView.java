package com.privatecloud.dto;

public class MonitorTargetRuntimeView {
    private String host;
    private String role;
    private String parentHost;
    private String queryHost;
    private boolean enabled;

    public MonitorTargetRuntimeView(String host, String role, String parentHost, String queryHost, boolean enabled) {
        this.host = host;
        this.role = role;
        this.parentHost = parentHost;
        this.queryHost = queryHost;
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public String getRole() {
        return role;
    }

    public String getParentHost() {
        return parentHost;
    }

    public String getQueryHost() {
        return queryHost;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
