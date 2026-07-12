package com.privatecloud.service;

import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.privatecloud.config.InfluxProperties;
import com.privatecloud.dto.ProcessMetricDTO;
import com.privatecloud.dto.TopProcessDTO;
import com.privatecloud.repository.InfluxDBRepository;
import com.privatecloud.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@Service
public class ProcessService {

    private final InfluxDBRepository repository;
    private final InfluxProperties influxProperties;

    private static final List<String> MEASUREMENTS = Arrays.asList(
            "procstat",
            "procstat_lookup",
            "processes",
            "process"
    );

    private static final List<String> PROCSTAT_FIELDS = Arrays.asList(
            "cpu_usage",
            "cpu_usage_percent",
            "cpu_percent",
            "memory_usage",
            "memory_rss",
            "memory_vms",
            "memory_swap",
            "num_threads",
            "pid_count",
            "pid"
    );

    private static final List<String> MEM_FIELDS = Arrays.asList(
            "memory_rss",
            "memory_usage",
            "memory_vms",
            "memory_swap"
    );

    private static final List<String> DISK_FIELDS = Arrays.asList(
            "read_bytes",
            "write_bytes",
            "io_read_bytes",
            "io_write_bytes"
    );

    private static final List<String> NET_FIELDS = Arrays.asList(
            "net_rx_bytes",
            "net_tx_bytes",
            "net_bytes_recv",
            "net_bytes_sent"
    );


    private static final List<String> PROCESS_COUNT_FIELDS = Arrays.asList(
            "running",
            "pid_count",
            "total",
            "sleeping",
            "idle",
            "blocked",
            "paging",
            "stopped",
            "zombies",
            "dead",
            "unknown",
            "total_threads"
    );

    private static final List<String> CPU_FIELDS = Arrays.asList(
            "cpu_usage_percent",
            "cpu_percent",
            "usage_percent",
            "cpu_usage"
    );

    private static final List<String> PROCESS_TAGS = Arrays.asList(
            "process_name",
            "pattern",
            "exe",
            "command",
            "proc",
            "name",
            "process",
            "pid"
    );

    public ProcessService(InfluxDBRepository repository, InfluxProperties influxProperties) {
        this.repository = repository;
        this.influxProperties = influxProperties;
    }

    public TopProcessDTO getTopProcess(String host, String start, String end, String role, String metric, String name) {
        String range = TimeUtil.buildRange(start, end, "-1h");
        List<String> fields = fieldsForMetric(metric);
        int cpuCoreCount = resolveCpuCoreCount(host, range, role, name);

        for (String measurement : MEASUREMENTS) {
            List<String> candidates = fields;
            for (String candidate : candidates) {
                TopProcessDTO result = queryTopProcess(host, range, role, name, measurement, candidate, cpuCoreCount);
                if (result != null) {
                    return result;
                }
                if (shouldFallbackRole(role)) {
                    result = queryTopProcess(host, range, null, name, measurement, candidate, cpuCoreCount);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }
        return null;
    }

    public List<ProcessMetricDTO> getTopProcessList(String host, String start, String end,
                                                    String role, String name, String metric, int limit) {
        String range = TimeUtil.buildRange(start, end, "-1h");
        List<String> fields = fieldsForMetric(metric);
        int top = Math.max(1, limit);
        int cpuCoreCount = resolveCpuCoreCount(host, range, role, name);
        List<ProcessMetricDTO> collected = new ArrayList<>();

        for (String measurement : MEASUREMENTS) {
            List<String> candidates = fields;
            for (String field : candidates) {
                List<ProcessMetricDTO> list = queryTopProcessList(host, range, role, name, measurement, field, top, cpuCoreCount);
                if (!list.isEmpty()) {
                    return list;
                }
                if (shouldFallbackRole(role)) {
                    list = queryTopProcessList(host, range, null, name, measurement, field, top, cpuCoreCount);
                    if (!list.isEmpty()) {
                        return list;
                    }
                }
            }
        }
        return collected;
    }

    private boolean shouldFallbackRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String r = role.trim().toLowerCase();
        return "server".equals(r) || "host".equals(r);
    }

    private TopProcessDTO queryTopProcess(String host, String range, String role, String name, String measurement, String field, int cpuCoreCount) {
        String hostValue = TimeUtil.escapeTagValue(host);
        String measurementValue = TimeUtil.escapeTagValue(measurement);
        String fieldValue = TimeUtil.escapeTagValue(field);

        StringBuilder flux = new StringBuilder(String.format(
                "import \"types\"\n"
                        + "from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> r._measurement == \"%s\")"
                        + "|> filter(fn:(r)=> r._field == \"%s\")",
                influxProperties.getBucket(),
                measurementValue,
                fieldValue
        ));

        appendRoleFilter(flux, role);
        appendTargetFilter(flux, hostValue, role, name);
        appendNumericValueFilter(flux);
        flux.append(String.format("|> group(columns:[%s])", processTagColumns()));
        boolean cpuField = isCpuField(field);
        if (cpuField) {
            flux.append("|> mean(column:\"_value\")");
        } else {
            flux.append("|> last()");
        }
        flux.append("|> group()");
        flux.append("|> sort(columns:[\"_value\"], desc: true)");
        flux.append("|> limit(n:1)");

        List<FluxTable> tables = repository.query(flux.toString());
        TopProcessDTO result = toTopProcess(tables, field);
        result = normalizeCpuValue(result, field, cpuCoreCount);
        if (result != null || !cpuField) {
            return result;
        }

        String fallbackFlux = flux.toString().replace("|> mean(column:\"_value\")", "|> last()");
        tables = repository.query(fallbackFlux);
        return normalizeCpuValue(toTopProcess(tables, field), field, cpuCoreCount);
    }

    private List<ProcessMetricDTO> queryTopProcessList(String host, String range, String role, String name,
                                                       String measurement, String field, int limit, int cpuCoreCount) {
        String hostValue = TimeUtil.escapeTagValue(host);
        String measurementValue = TimeUtil.escapeTagValue(measurement);
        String fieldValue = TimeUtil.escapeTagValue(field);

        StringBuilder flux = new StringBuilder(String.format(
                "import \"types\"\n"
                        + "from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> r._measurement == \"%s\")"
                        + "|> filter(fn:(r)=> r._field == \"%s\")",
                influxProperties.getBucket(),
                measurementValue,
                fieldValue
        ));

        appendRoleFilter(flux, role);
        appendTargetFilter(flux, hostValue, role, name);
        appendNumericValueFilter(flux);
        flux.append(String.format("|> group(columns:[%s])", processTagColumns()));
        boolean cpuField = isCpuField(field);
        if (cpuField) {
            flux.append("|> mean(column:\"_value\")");
        } else {
            flux.append("|> last()");
        }
        flux.append("|> group()");
        flux.append("|> sort(columns:[\"_value\"], desc: true)");
        flux.append(String.format("|> limit(n:%d)", limit));

        List<FluxTable> tables = repository.query(flux.toString());
        List<ProcessMetricDTO> result = normalizeCpuValues(toProcessList(tables, field), field, cpuCoreCount);
        if (!result.isEmpty() || !cpuField) {
            return result;
        }

        String fallbackFlux = flux.toString().replace("|> mean(column:\"_value\")", "|> last()");
        tables = repository.query(fallbackFlux);
        return normalizeCpuValues(toProcessList(tables, field), field, cpuCoreCount);
    }

    private TopProcessDTO toTopProcess(List<FluxTable> tables, String field) {
        if (tables == null || tables.isEmpty()) {
            return null;
        }
        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Instant time = record.getTime();
                if (time == null) {
                    time = resolveRecordTime(record);
                }
                Object raw = record.getValue();
                if (time == null || raw == null) {
                    continue;
                }
                Double value = null;
                if (raw instanceof Number) {
                    value = ((Number) raw).doubleValue();
                } else {
                    try {
                        value = Double.parseDouble(String.valueOf(raw));
                    } catch (Exception ignored) {
                    }
                }
                if (value == null) {
                    continue;
                }
                String name = extractProcessName(record);
                if (name == null || name.isBlank()) {
                    name = field;
                }
                return new TopProcessDTO(name, value, field, time.toString());
            }
        }
        return null;
    }

    private List<ProcessMetricDTO> toProcessList(List<FluxTable> tables, String field) {
        List<ProcessMetricDTO> list = new ArrayList<>();
        if (tables == null || tables.isEmpty()) {
            return list;
        }
        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Instant time = record.getTime();
                if (time == null) {
                    time = resolveRecordTime(record);
                }
                Object raw = record.getValue();
                if (time == null || raw == null) {
                    continue;
                }
                Double value = null;
                if (raw instanceof Number) {
                    value = ((Number) raw).doubleValue();
                } else {
                    try {
                        value = Double.parseDouble(String.valueOf(raw));
                    } catch (Exception ignored) {
                    }
                }
                if (value == null) {
                    continue;
                }
                String name = extractProcessName(record);
                if (name == null || name.isBlank()) {
                    name = field;
                }
                list.add(new ProcessMetricDTO(name, value, field, time.toString()));
            }
        }
        return list;
    }

    private String extractProcessName(FluxRecord record) {
        for (String tag : PROCESS_TAGS) {
            Object value = record.getValueByKey(tag);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private boolean isCpuField(String field) {
        if (field == null || field.isBlank()) {
            return false;
        }
        String lower = field.toLowerCase(Locale.ROOT);
        return lower.contains("cpu");
    }

    private boolean shouldNormalizeCpuByCoreCount(String field) {
        if (field == null || field.isBlank()) {
            return false;
        }
        String lower = field.toLowerCase(Locale.ROOT);
        return "cpu_usage".equals(lower);
    }

    private TopProcessDTO normalizeCpuValue(TopProcessDTO source, String field, int cpuCoreCount) {
        if (source == null || source.getValue() == null || !shouldNormalizeCpuByCoreCount(field)) {
            return source;
        }
        int divisor = Math.max(1, cpuCoreCount);
        source.setValue(source.getValue() / divisor);
        return source;
    }

    private List<ProcessMetricDTO> normalizeCpuValues(List<ProcessMetricDTO> source, String field, int cpuCoreCount) {
        if (source == null || source.isEmpty() || !shouldNormalizeCpuByCoreCount(field)) {
            return source;
        }
        int divisor = Math.max(1, cpuCoreCount);
        List<ProcessMetricDTO> normalized = new ArrayList<>(source.size());
        for (ProcessMetricDTO item : source) {
            Double value = item.getValue();
            normalized.add(new ProcessMetricDTO(
                    item.getName(),
                    value == null ? null : value / divisor,
                    item.getField(),
                    item.getTime()
            ));
        }
        return normalized;
    }

    private int resolveCpuCoreCount(String host, String range, String role, String name) {
        String hostValue = TimeUtil.escapeTagValue(host);
        StringBuilder flux = new StringBuilder(String.format(
                "from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> r._measurement == \"cpu\")"
                        + "|> filter(fn:(r)=> r._field == \"usage_user\" or r._field == \"usage_system\" or r._field == \"usage_idle\" or r._field == \"usage_active\")",
                influxProperties.getBucket()
        ));
        appendRoleFilter(flux, role);
        appendTargetFilter(flux, hostValue, role, name);
        flux.append("|> keep(columns:[\"cpu\"])");
        flux.append("|> filter(fn:(r)=> exists r.cpu and r.cpu != \"cpu-total\" and r.cpu != \"total\")");
        flux.append("|> distinct(column:\"cpu\")");
        flux.append("|> count(column:\"cpu\")");

        try {
            List<FluxTable> tables = repository.query(flux.toString());
            if (tables == null || tables.isEmpty()) {
                return 1;
            }
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Object raw = record.getValue();
                    if (raw instanceof Number) {
                        return Math.max(1, ((Number) raw).intValue());
                    }
                    if (raw != null) {
                        try {
                            return Math.max(1, Integer.parseInt(String.valueOf(raw)));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    private Instant resolveRecordTime(FluxRecord record) {
        Instant fromTime = toInstant(record.getValueByKey("_time"));
        if (fromTime != null) {
            return fromTime;
        }
        Object stop = record.getValueByKey("_stop");
        Instant fromStop = toInstant(stop);
        if (fromStop != null) {
            return fromStop;
        }
        Object start = record.getValueByKey("_start");
        Instant fromStart = toInstant(start);
        if (fromStart != null) {
            return fromStart;
        }
        return null;
    }

    private Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant) {
            return (Instant) value;
        }
        if (value instanceof OffsetDateTime) {
            return ((OffsetDateTime) value).toInstant();
        }
        if (value instanceof Date) {
            return ((Date) value).toInstant();
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> fieldsForMetric(String metric) {
        if (metric == null || metric.isBlank()) {
            return CPU_FIELDS;
        }
        String m = metric.trim().toLowerCase(Locale.ROOT);
        if ("cpu".equals(m)) {
            return CPU_FIELDS;
        }
        if ("mem".equals(m) || "memory".equals(m)) {
            return MEM_FIELDS;
        }
        if ("disk".equals(m) || "storage".equals(m)) {
            return DISK_FIELDS;
        }
        if ("net".equals(m) || "network".equals(m)) {
            return NET_FIELDS;
        }
        return CPU_FIELDS;
    }

    private String processTagColumns() {
        return "\"process_name\",\"pattern\",\"exe\",\"command\",\"proc\",\"name\",\"process\",\"pid\"";
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
