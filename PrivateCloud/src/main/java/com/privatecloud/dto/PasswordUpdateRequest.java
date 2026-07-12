package com.privatecloud.dto;

import jakarta.validation.constraints.NotBlank;

public class PasswordUpdateRequest {
    @NotBlank
    private String password;

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
