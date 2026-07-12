package com.privatecloud.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public class UserCreateRequest {
    @NotBlank
    private String username;
    @NotBlank
    private String password;
    private List<String> roles;
    private List<String> serverHosts;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

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
}
