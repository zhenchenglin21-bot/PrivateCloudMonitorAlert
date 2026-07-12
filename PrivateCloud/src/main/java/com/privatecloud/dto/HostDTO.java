package com.privatecloud.dto;

public class HostDTO {

    private String host;
    private String ip;

    public HostDTO() {}

    public HostDTO(String host, String ip) {
        this.host = host;
        this.ip = ip;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }
}