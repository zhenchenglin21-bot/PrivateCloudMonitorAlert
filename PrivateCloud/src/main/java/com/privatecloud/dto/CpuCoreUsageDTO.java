package com.privatecloud.dto;

public class CpuCoreUsageDTO {
    private String cpu;
    private Double usageUser;
    private Double usageSystem;

    public CpuCoreUsageDTO() {
    }

    public CpuCoreUsageDTO(String cpu, Double usageUser, Double usageSystem) {
        this.cpu = cpu;
        this.usageUser = usageUser;
        this.usageSystem = usageSystem;
    }

    public String getCpu() {
        return cpu;
    }

    public void setCpu(String cpu) {
        this.cpu = cpu;
    }

    public Double getUsageUser() {
        return usageUser;
    }

    public void setUsageUser(Double usageUser) {
        this.usageUser = usageUser;
    }

    public Double getUsageSystem() {
        return usageSystem;
    }

    public void setUsageSystem(Double usageSystem) {
        this.usageSystem = usageSystem;
    }
}
