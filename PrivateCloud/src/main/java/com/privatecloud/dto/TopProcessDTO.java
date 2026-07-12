package com.privatecloud.dto;

public class TopProcessDTO {
    private String name;
    private Double value;
    private String field;
    private String time;

    public TopProcessDTO() {
    }

    public TopProcessDTO(String name, Double value, String field, String time) {
        this.name = name;
        this.value = value;
        this.field = field;
        this.time = time;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }
}
