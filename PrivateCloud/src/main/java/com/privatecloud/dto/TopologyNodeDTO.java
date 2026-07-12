package com.privatecloud.dto;

public class TopologyNodeDTO {

    private String host;
    private String role;
    private String parentHost;
    private String queryHost;
    private String lastSeen;

    public TopologyNodeDTO() {
    }

    public TopologyNodeDTO(String host, String role, String parentHost, String queryHost, String lastSeen) {
        this.host = host;
        this.role = role;
        this.parentHost = parentHost;
        this.queryHost = queryHost;
        this.lastSeen = lastSeen;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getParentHost() {
        return parentHost;
    }

    public void setParentHost(String parentHost) {
        this.parentHost = parentHost;
    }

    public String getQueryHost() {
        return queryHost;
    }

    public void setQueryHost(String queryHost) {
        this.queryHost = queryHost;
    }

    public String getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(String lastSeen) {
        this.lastSeen = lastSeen;
    }
}
