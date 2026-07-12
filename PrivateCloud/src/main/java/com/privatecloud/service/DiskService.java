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
public class DiskService {

    private final InfluxDBRepository repository;
    private final InfluxProperties influxProperties;
    private static final List<String> CONTAINER_FIELDS = Arrays.asList(
            "usage_percent", "used_percent", "used", "usage", "filesystem_usage"
    );
    private static final List<String> ROOT_PERCENT_FIELDS = Arrays.asList(
            "used_percent", "usage_percent", "filesystem_usage"
    );
    private static final List<String> ROOT_PATH_CANDIDATES = Arrays.asList(
            "/", "/rootfs", "C:\\", "C:/", "C:"
    );
    private static final List<String> ROOT_PATH_TAGS = Arrays.asList(
            "path", "mount", "mountpoint"
    );
    private static final List<String> IGNORED_FS_TYPES = Arrays.asList(
            "tmpfs", "devtmpfs", "squashfs", "overlay", "aufs", "ramfs"
    );

    public DiskService(InfluxDBRepository repository, InfluxProperties influxProperties) {
        this.repository = repository;
        this.influxProperties = influxProperties;
    }

    public List<FluxTable> getDiskUsage(String host, String start, String role) {
        String range = TimeUtil.buildRange(start, null, "-1h");
        String hostValue = TimeUtil.escapeTagValue(host);
        StringBuilder flux = new StringBuilder(String.format(
                "from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> r._measurement == \"disk\")"
                        + "|> filter(fn:(r)=> r._field == \"used_percent\")"
                        + "|> filter(fn:(r)=> r.host == \"%s\")",
                influxProperties.getBucket(),
                hostValue
        ));
        appendRoleFilter(flux, role);
        return repository.query(flux.toString());
    }

    public List<MetricDTO> getDiskHistory(String host, String start, String end, String window, String role, String field, String name) {
        String range = TimeUtil.buildRange(start, end, "-1h");
        String w = (window == null || window.isBlank()) ? "1m" : window;
        String f = (field == null || field.isBlank()) ? "used_percent" : field;
        boolean autoField = field == null || field.isBlank();
        if (autoField && !"container".equalsIgnoreCase(role)) {
            List<MetricDTO> rootSeries = queryDiskRootPathPercent(host, range, w, role, name);
            if (!rootSeries.isEmpty()) {
                return rootSeries;
            }
            List<MetricDTO> totalSeries = queryDiskTotalUsagePercent(host, range, w, role, name);
            if (!totalSeries.isEmpty()) {
                return totalSeries;
            }
        }
        if ((field == null || field.isBlank()) && "container".equalsIgnoreCase(role)) {
            for (String candidate : CONTAINER_FIELDS) {
                List<MetricDTO> series = queryDiskHistory(host, range, w, role, candidate, name);
                if (!series.isEmpty()) {
                    return series;
                }
            }
        }
        return queryDiskHistory(host, range, w, role, f, name);
    }

    private List<MetricDTO> queryDiskHistory(String host, String range, String window, String role, String field, String name) {
        String hostValue = TimeUtil.escapeTagValue(host);
        String fieldValue = TimeUtil.escapeTagValue(field);
        StringBuilder flux = new StringBuilder(String.format(
                "import \"types\"\n"
                        + "from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> r._measurement == \"disk\")"
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

    private List<MetricDTO> queryDiskRootPathPercent(String host, String range, String window, String role, String name) {
        for (String field : ROOT_PERCENT_FIELDS) {
            List<MetricDTO> series = queryDiskRootPathPercentByField(host, range, window, role, name, field);
            if (!series.isEmpty()) {
                return series;
            }
        }
        return List.of();
    }

    private List<MetricDTO> queryDiskRootPathPercentByField(String host, String range, String window, String role, String name, String field) {
        String hostValue = TimeUtil.escapeTagValue(host);
        String fieldValue = TimeUtil.escapeTagValue(field);
        StringBuilder flux = new StringBuilder(String.format(
                "import \"types\"\n"
                        + "from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> r._measurement == \"disk\")"
                        + "|> filter(fn:(r)=> r._field == \"%s\")",
                influxProperties.getBucket(),
                fieldValue
        ));
        appendRoleFilter(flux, role);
        appendTargetFilter(flux, hostValue, role, name);
        appendNumericValueFilter(flux);
        appendPhysicalFilesystemFilter(flux);
        appendRootPathFilter(flux);
        flux.append(String.format("|> aggregateWindow(every: %s, fn: last, createEmpty: false)", window));
        flux.append("|> group(columns:[\"_time\"])");
        flux.append("|> mean(column:\"_value\")");
        flux.append("|> sort(columns:[\"_time\"])");
        List<FluxTable> tables = repository.query(flux.toString());
        return FluxMapper.toSeries(tables);
    }

    private List<MetricDTO> queryDiskTotalUsagePercent(String host, String range, String window, String role, String name) {
        String hostValue = TimeUtil.escapeTagValue(host);
        StringBuilder flux = new StringBuilder(String.format(
                "import \"types\"\n"
                        + "raw = from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> r._measurement == \"disk\")"
                        + "|> filter(fn:(r)=> r._field == \"used\" or r._field == \"total\")",
                influxProperties.getBucket()
        ));
        appendRoleFilter(flux, role);
        appendTargetFilter(flux, hostValue, role, name);
        appendNumericValueFilter(flux);
        appendPhysicalFilesystemFilter(flux);
        flux.append(String.format("|> aggregateWindow(every: %s, fn: last, createEmpty: false)\n", window));
        flux.append("raw");
        flux.append("|> group(columns:[\"_time\", \"_field\"])");
        flux.append("|> sum(column:\"_value\")");
        flux.append("|> pivot(rowKey:[\"_time\"], columnKey:[\"_field\"], valueColumn:\"_value\")");
        flux.append("|> filter(fn:(r)=> exists r.total and r.total > 0.0 and exists r.used)");
        flux.append("|> map(fn:(r)=> ({_time: r._time, _value: float(v: r.used) / float(v: r.total) * 100.0}))");
        flux.append("|> sort(columns:[\"_time\"])");
        List<FluxTable> tables = repository.query(flux.toString());
        return FluxMapper.toSeries(tables);
    }

    private void appendNumericValueFilter(StringBuilder flux) {
        flux.append("|> filter(fn:(r)=> types.isType(v: r._value, type: \"float\") or types.isType(v: r._value, type: \"int\") or types.isType(v: r._value, type: \"uint\"))");
    }

    private void appendPhysicalFilesystemFilter(StringBuilder flux) {
        flux.append("|> filter(fn:(r)=> not exists r.fstype or (");
        for (int i = 0; i < IGNORED_FS_TYPES.size(); i++) {
            if (i > 0) {
                flux.append(" and ");
            }
            flux.append(String.format("r.fstype != \"%s\"", TimeUtil.escapeTagValue(IGNORED_FS_TYPES.get(i))));
        }
        flux.append("))");
    }

    private void appendRootPathFilter(StringBuilder flux) {
        flux.append("|> filter(fn:(r)=> ");
        for (int i = 0; i < ROOT_PATH_TAGS.size(); i++) {
            if (i > 0) {
                flux.append(" or ");
            }
            String tag = ROOT_PATH_TAGS.get(i);
            flux.append(String.format("(exists r.%s and (", tag));
            for (int j = 0; j < ROOT_PATH_CANDIDATES.size(); j++) {
                if (j > 0) {
                    flux.append(" or ");
                }
                flux.append(String.format("r.%s == \"%s\"", tag, TimeUtil.escapeTagValue(ROOT_PATH_CANDIDATES.get(j))));
            }
            flux.append("))");
        }
        flux.append(")");
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
