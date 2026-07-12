package com.privatecloud.service;

import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.privatecloud.config.InfluxProperties;
import com.privatecloud.dto.CpuCoreUsageDTO;
import com.privatecloud.dto.MetricDTO;
import com.privatecloud.repository.InfluxDBRepository;
import com.privatecloud.util.FluxMapper;
import com.privatecloud.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

@Service
public class CpuService {

    private final InfluxDBRepository repository;
    private final InfluxProperties influxProperties;
    private static final List<String> HOST_TOTAL_FIELDS = Arrays.asList(
            "usage_user", "usage_active", "usage_total", "usage_percent", "cpu_usage"
    );
    private static final List<String> CONTAINER_FIELDS = Arrays.asList(
            "usage_user", "usage_percent", "usage_system", "usage_total", "cpu_usage"
    );

    public CpuService(InfluxDBRepository repository, InfluxProperties influxProperties) {
        this.repository = repository;
        this.influxProperties = influxProperties;
    }

    public List<FluxTable> getCpuUsage(String host, String start, String role) {

        String range = TimeUtil.buildRange(start, null, "-1h");
        String hostValue = TimeUtil.escapeTagValue(host);
        StringBuilder flux = new StringBuilder(String.format(
                "from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> r._measurement == \"cpu\")"
                        + "|> filter(fn:(r)=> r.host == \"%s\")",
                influxProperties.getBucket(),
                hostValue
        ));
        appendRoleFilter(flux, role);

        return repository.query(flux.toString());
    }

    public List<MetricDTO> getCpuHistory(String host, String start, String end, String window, String role, String field, String name) {
        String range = TimeUtil.buildRange(start, end, "-1h");
        String w = (window == null || window.isBlank()) ? "1m" : window;
        String f = field == null ? "" : field.trim();
        if (f.isBlank() && "container".equalsIgnoreCase(role)) {
            for (String candidate : CONTAINER_FIELDS) {
                List<MetricDTO> series = queryCpuHistory(host, range, w, role, candidate, name, null);
                if (!series.isEmpty()) {
                    return series;
                }
            }
            return List.of();
        }
        if (f.isBlank()) {
            for (String candidate : HOST_TOTAL_FIELDS) {
                List<MetricDTO> series = queryCpuHistoryWithCpuTotalFallback(host, range, w, role, candidate, name);
                if (!series.isEmpty()) {
                    return series;
                }
            }
            return List.of();
        }
        return queryCpuHistoryWithCpuTotalFallback(host, range, w, role, f, name);
    }

    public List<CpuCoreUsageDTO> getCpuCoreUsage(String host, String start, String end, String role, String name) {
        String range = TimeUtil.buildRange(start, end, "-1h");
        String hostValue = TimeUtil.escapeTagValue(host);
        StringBuilder flux = new StringBuilder(String.format(
                "import \"types\"\n"
                        + "from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> r._measurement == \"cpu\")"
                        + "|> filter(fn:(r)=> r._field == \"usage_user\" or r._field == \"usage_system\")",
                influxProperties.getBucket()
        ));
        appendRoleFilter(flux, role);
        appendTargetFilter(flux, hostValue, role, name);
        appendNumericValueFilter(flux);
        flux.append("|> group(columns:[\"cpu\", \"_field\"])");
        flux.append("|> last()");
        flux.append("|> pivot(rowKey:[\"cpu\"], columnKey:[\"_field\"], valueColumn:\"_value\")");
        flux.append("|> sort(columns:[\"cpu\"])");

        List<FluxTable> tables = repository.query(flux.toString());
        List<CpuCoreUsageDTO> result = new ArrayList<>();
        if (tables == null) {
            return result;
        }
        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Object cpuObj = record.getValueByKey("cpu");
                if (cpuObj == null) {
                    continue;
                }
                String cpu = String.valueOf(cpuObj);
                if (cpu.isBlank() || "cpu-total".equalsIgnoreCase(cpu)) {
                    continue;
                }
                Double user = toDouble(record.getValueByKey("usage_user"));
                Double system = toDouble(record.getValueByKey("usage_system"));
                result.add(new CpuCoreUsageDTO(cpu, user, system));
            }
        }
        return result;
    }

    private List<MetricDTO> queryCpuHistoryWithCpuTotalFallback(String host, String range, String window, String role, String field, String name) {
        if ("container".equalsIgnoreCase(role)) {
            return queryCpuHistory(host, range, window, role, field, name, null);
        }
        List<MetricDTO> cpuTotalSeries = queryCpuHistory(host, range, window, role, field, name, "cpu-total");
        if (!cpuTotalSeries.isEmpty()) {
            return cpuTotalSeries;
        }
        return queryCpuHistory(host, range, window, role, field, name, null);
    }

    private List<MetricDTO> queryCpuHistory(String host, String range, String window, String role, String field, String name, String cpuTag) {
        String hostValue = TimeUtil.escapeTagValue(host);
        String fieldValue = TimeUtil.escapeTagValue(field);
        StringBuilder flux = new StringBuilder(String.format(
                "import \"types\"\n"
                        + "from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> r._measurement == \"cpu\")"
                        + "|> filter(fn:(r)=> r._field == \"%s\")",
                influxProperties.getBucket(),
                fieldValue
        ));
        appendRoleFilter(flux, role);
        appendTargetFilter(flux, hostValue, role, name);
        if (cpuTag != null && !cpuTag.isBlank()) {
            flux.append(String.format("|> filter(fn:(r)=> exists r.cpu and r.cpu == \"%s\")", TimeUtil.escapeTagValue(cpuTag)));
        }
        appendNumericValueFilter(flux);
        flux.append(String.format("|> aggregateWindow(every: %s, fn: mean, createEmpty: false)", window));
        List<FluxTable> tables = repository.query(flux.toString());
        return FluxMapper.toSeries(tables);
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
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
