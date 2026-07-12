package com.privatecloud.dto;

import java.util.List;

public class AuthMeResponse {
    private String username;
    private List<String> roles;
    private List<String> servers;
    private boolean admin;

    public AuthMeResponse(String username, List<String> roles, List<String> servers, boolean admin) {
        this.username = username;
        this.roles = roles;
        this.servers = servers;
        this.admin = admin;
    }

    public String getUsername() {
        return username;
    }

    public List<String> getRoles() {
        return roles;
    }

    public List<String> getServers() {
        return servers;
    }

    public boolean isAdmin() {
        return admin;
    }
}
