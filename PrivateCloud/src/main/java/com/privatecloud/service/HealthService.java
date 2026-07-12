package com.privatecloud.service;

import com.influxdb.query.FluxTable;
import com.privatecloud.config.InfluxProperties;
import com.privatecloud.repository.InfluxDBRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HealthService {

    private final InfluxDBRepository repository;
    private final InfluxProperties influxProperties;

    public HealthService(InfluxDBRepository repository, InfluxProperties influxProperties) {
        this.repository = repository;
        this.influxProperties = influxProperties;
    }

    public boolean ping() {
        String flux = String.format(
                "from(bucket:\"%s\") |> range(start: -1m) |> limit(n:1)",
                influxProperties.getBucket()
        );
        try {
            List<FluxTable> tables = repository.query(flux);
            return tables != null;
        } catch (Exception e) {
            return false;
        }
    }
}
