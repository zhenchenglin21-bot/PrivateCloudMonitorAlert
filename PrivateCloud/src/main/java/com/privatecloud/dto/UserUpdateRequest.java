package com.privatecloud.dto;

import java.util.List;

public class UserUpdateRequest {
    private List<String> roles;
    private List<String> serverHosts;
    private Boolean enabled;

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    public List<String> getServerHosts() {
        return serverHosts;
    }

    public void setServerHosts(List<String> serverHosts) {
        this.serverHosts = serverHosts;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
