package com.privatecloud.service;

import com.influxdb.query.FluxTable;
import com.privatecloud.config.InfluxProperties;
import com.privatecloud.repository.InfluxDBRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SchemaService {

    private final InfluxDBRepository repository;
    private final InfluxProperties influxProperties;

    public SchemaService(InfluxDBRepository repository, InfluxProperties influxProperties) {
        this.repository = repository;
        this.influxProperties = influxProperties;
    }

    public List<FluxTable> measurements() {
        String flux = String.format(
                "import \"influxdata/influxdb/schema\" schema.measurements(bucket:\"%s\")",
                influxProperties.getBucket()
        );
        return repository.query(flux);
    }

    public List<FluxTable> tagKeys(String measurement) {
        String flux = String.format(
                "import \"influxdata/influxdb/schema\" schema.tagKeys(bucket:\"%s\")",
                influxProperties.getBucket()
        );
        if (measurement != null && !measurement.isBlank()) {
            flux = String.format(
                    "import \"influxdata/influxdb/schema\" schema.tagKeys(bucket:\"%s\", predicate: (r) => r._measurement == \"%s\")",
                    influxProperties.getBucket(),
                    measurement
            );
        }
        return repository.query(flux);
    }

    public List<FluxTable> tagValues(String tag, String measurement) {
        String flux;
        if (measurement != null && !measurement.isBlank()) {
            flux = String.format(
                    "import \"influxdata/influxdb/schema\" schema.tagValues(bucket:\"%s\", tag:\"%s\", predicate: (r) => r._measurement == \"%s\")",
                    influxProperties.getBucket(),
                    tag,
                    measurement
            );
        } else {
            flux = String.format(
                    "import \"influxdata/influxdb/schema\" schema.tagValues(bucket:\"%s\", tag:\"%s\")",
                    influxProperties.getBucket(),
                    tag
            );
        }
        return repository.query(flux);
    }
}
