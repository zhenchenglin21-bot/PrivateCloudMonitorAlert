package com.privatecloud.dto;

public class ProcessMetricDTO {
    private String name;
    private Double value;
    private String field;
    private String time;

    public ProcessMetricDTO() {}

    public ProcessMetricDTO(String name, Double value, String field, String time) {
        this.name = name;
        this.value = value;
        this.field = field;
        this.time = time;
    }

    public String getName() {
        return name;
    }

    public Double getValue() {
        return value;
    }

    public String getField() {
        return field;
    }

    public String getTime() {
        return time;
    }
}
