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
public class SwapService {

    private final InfluxDBRepository repository;
    private final InfluxProperties influxProperties;
    private static final List<String> FALLBACK_FIELDS = Arrays.asList(
            "used_percent", "usage_percent", "used", "inuse_percent"
    );

    public SwapService(InfluxDBRepository repository, InfluxProperties influxProperties) {
        this.repository = repository;
        this.influxProperties = influxProperties;
    }

    public List<MetricDTO> getSwapHistory(String host, String start, String end, String window, String role, String field, String name) {
        String range = TimeUtil.buildRange(start, end, "-1h");
        String w = (window == null || window.isBlank()) ? "1m" : window;
        String f = (field == null || field.isBlank()) ? "used_percent" : field;

        if (field == null || field.isBlank()) {
            for (String candidate : FALLBACK_FIELDS) {
                List<MetricDTO> series = querySwapHistory(host, range, w, role, candidate, name);
                if (!series.isEmpty()) {
                    return series;
                }
            }
        }
        return querySwapHistory(host, range, w, role, f, name);
    }

    private List<MetricDTO> querySwapHistory(String host, String range, String window, String role, String field, String name) {
        String hostValue = TimeUtil.escapeTagValue(host);
        String fieldValue = TimeUtil.escapeTagValue(field);
        StringBuilder flux = new StringBuilder(String.format(
                "import \"types\"\n"
                        + "from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> r._measurement == \"swap\")"
                        + "|> filter(fn:(r)=> r._field == \"%s\")",
                influxProperties.getBucket(),
                fieldValue
        ));
        appendRoleFilter(flux, role);
        appendTargetFilter(flux, hostValue, role, name);
        appendNumericValueFilter(flux);
        flux.append(String.format("|> aggregateWindow(every: %s, fn: mean, createEmpty: false)", window));
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
