package com.privatecloud.repository;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxTable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class InfluxDBRepository {

    private final InfluxDBClient influxDBClient;

    public InfluxDBRepository(InfluxDBClient influxDBClient) {
        this.influxDBClient = influxDBClient;
    }

    public List<FluxTable> query(String flux) {
        return influxDBClient.getQueryApi().query(flux);
    }
}