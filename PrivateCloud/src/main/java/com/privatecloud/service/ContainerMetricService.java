package com.privatecloud.service;

import com.influxdb.query.FluxTable;
import com.privatecloud.config.InfluxProperties;
import com.privatecloud.dto.MetricDTO;
import com.privatecloud.repository.InfluxDBRepository;
import com.privatecloud.util.FluxMapper;
import com.privatecloud.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class ContainerMetricService {

    private final InfluxDBRepository repository;
    private final InfluxProperties influxProperties;
    private static final List<String> DEFAULT_FIELDS = Arrays.asList("value", "gauge", "counter");

    public ContainerMetricService(InfluxDBRepository repository, InfluxProperties influxProperties) {
        this.repository = repository;
        this.influxProperties = influxProperties;
    }

    public List<MetricDTO> getHistory(String host, String start, String end, String window,
                                      String role, String name, String measurement, String field, boolean derivative) {
        String m = measurement == null ? "" : measurement.trim();
        if (m.isBlank()) {
            return List.of();
        }
        String range = TimeUtil.buildRange(start, end, "-1h");
        String w = (window == null || window.isBlank()) ? "1m" : window;

        if (field != null && !field.isBlank()) {
            return query(host, role, name, m, field, range, w, derivative);
        }

        for (String candidate : DEFAULT_FIELDS) {
            List<MetricDTO> series = query(host, role, name, m, candidate, range, w, derivative);
            if (!series.isEmpty()) {
                return series;
            }
        }
        // Some scrapers may not store value under default field names; try without field filter.
        return query(host, role, name, m, null, range, w, derivative);
    }

    private List<MetricDTO> query(String host, String role, String name, String measurement, String field,
                                  String range, String window, boolean derivative) {
        String hostValue = TimeUtil.escapeTagValue(host);
        String measurementValue = TimeUtil.escapeTagValue(measurement);
        StringBuilder flux = new StringBuilder(String.format(
                "import \"types\"\n"
                        + "from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> r._measurement == \"%s\")",
                influxProperties.getBucket(),
                measurementValue
        ));
        if (field != null && !field.isBlank()) {
            flux.append(String.format("|> filter(fn:(r)=> r._field == \"%s\")", TimeUtil.escapeTagValue(field)));
        }
        appendRoleFilter(flux, role);
        appendTargetFilter(flux, hostValue, role, name);
        appendNumericValueFilter(flux);
        flux.append(String.format("|> aggregateWindow(every: %s, fn: mean, createEmpty: false)", window));
        if (derivative) {
            flux.append("|> derivative(unit: 1s, nonNegative: true)");
        }
        List<FluxTable> tables = repository.query(flux.toString());
        return FluxMapper.toSeries(tables);
    }

    private void appendNumericValueFilter(StringBuilder flux) {
        flux.append("|> filter(fn:(r)=> types.isType(v: r._value, type: \"float\") or types.isType(v: r._value, type: \"int\") or types.isType(v: r._value, type: \"uint\"))");
    }

    private void appendRoleFilter(StringBuilder flux, String role) {
        if (role == null || role.isBlank()) {
            return;
        }
        String r = role.trim().toLowerCase();
        if ("server".equals(r) || "host".equals(r)) {
            flux.append("|> filter(fn:(r)=> r.role == \"host\" or r.role == \"server\")");
            return;
        }
        flux.append(String.format("|> filter(fn:(r)=> r.role == \"%s\")", TimeUtil.escapeTagValue(role)));
    }

    private void appendTargetFilter(StringBuilder flux, String host, String role, String name) {
        String n = name == null ? "" : TimeUtil.escapeTagValue(name);
        if (n.isBlank()) {
            flux.append(String.format("|> filter(fn:(r)=> r.host == \"%s\")", host));
            return;
        }
        String r = role == null ? "" : role.trim().toLowerCase();
        if ("vm".equals(r)) {
            flux.append(String.format("|> filter(fn:(r)=> r.host == \"%s\" or (exists r.vm_name and r.vm_name == \"%s\") or r.host == \"%s\")", host, n, n));
            return;
        }
        if ("container".equals(r)) {
            flux.append(String.format("|> filter(fn:(r)=> r.host == \"%s\" or (exists r.container_name and r.container_name == \"%s\") or r.host == \"%s\")", host, n, n));
            return;
        }
        flux.append(String.format("|> filter(fn:(r)=> r.host == \"%s\" or (exists r.host_name and r.host_name == \"%s\") or r.host == \"%s\")", host, n, n));
    }
}
