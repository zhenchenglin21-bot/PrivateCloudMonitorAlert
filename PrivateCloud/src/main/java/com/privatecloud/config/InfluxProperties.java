package com.privatecloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InfluxProperties {

    @Value("${influxdb.bucket}")
    private String bucket;

    public String getBucket() {
        return bucket;
    }
}
