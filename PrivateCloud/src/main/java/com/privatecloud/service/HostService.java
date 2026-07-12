package com.privatecloud.service;

import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.privatecloud.config.InfluxProperties;
import com.privatecloud.dto.TopologyNodeDTO;
import com.privatecloud.repository.InfluxDBRepository;
import com.privatecloud.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class HostService {

    private final InfluxDBRepository repository;
    private final InfluxProperties influxProperties;

    public HostService(InfluxDBRepository repository, InfluxProperties influxProperties) {
        this.repository = repository;
        this.influxProperties = influxProperties;
    }

    public List<FluxTable> getHosts() {

        String flux = String.format(
                "import \"influxdata/influxdb/schema\" schema.tagValues(bucket:\"%s\", tag:\"host\")",
                influxProperties.getBucket()
        );

        return repository.query(flux);
    }

    public List<TopologyNodeDTO> getTopology(String start, String end) {
        String range = TimeUtil.buildRange(start, end, "-30d");
        String flux = String.format(
                "from(bucket:\"%s\")"
                        + range
                        + "|> filter(fn:(r)=> exists r.role)"
                        + "|> keep(columns:[\"_time\", \"role\", \"host\", \"host_name\", \"vm_name\", \"container_name\"])"
                        + "|> group(columns:[\"role\", \"host\", \"host_name\", \"vm_name\", \"container_name\"])"
                        + "|> last(column:\"_time\")",
                influxProperties.getBucket()
        );

        List<FluxTable> tables = repository.query(flux);
        Map<String, TopologyNodeDTO> latestNodes = new LinkedHashMap<>();

        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                String role = normalizeRole(asString(record.getValueByKey("role")));
                String host = asString(record.getValueByKey("host"));
                String hostName = asString(record.getValueByKey("host_name"));
                String vmName = asString(record.getValueByKey("vm_name"));
                String containerName = asString(record.getValueByKey("container_name"));

                String entityName = resolveEntityName(role, host, hostName, vmName, containerName);
                if (entityName == null || entityName.isBlank()) {
                    continue;
                }

                String serverName = firstNonBlank(hostName, host);
                String parentHost = resolveParentHost(role, serverName, vmName);
                String queryHost = firstNonBlank(host, hostName);
                Instant time = record.getTime();
                String key = role + "|" + entityName + "|" + (parentHost == null ? "" : parentHost);

                TopologyNodeDTO current = latestNodes.get(key);
                if (current == null || shouldReplace(current, time, queryHost, host)) {
                    latestNodes.put(key, new TopologyNodeDTO(
                            entityName,
                            role,
                            parentHost,
                            queryHost,
                            time == null ? null : time.toString()
                    ));
                }
            }
        }

        return latestNodes.values().stream()
                .sorted(Comparator.comparing(TopologyNodeDTO::getRole).thenComparing(TopologyNodeDTO::getHost))
                .collect(Collectors.toList());
    }

    private boolean shouldReplace(TopologyNodeDTO current, Instant candidateTime, String queryHost, String rawHost) {
        String currentQueryHost = current.getQueryHost();
        boolean currentMissing = currentQueryHost == null || currentQueryHost.isBlank();
        boolean candidateHasRawHost = rawHost != null && !rawHost.isBlank() && rawHost.equals(queryHost);
        if (currentMissing && queryHost != null && !queryHost.isBlank()) {
            return true;
        }
        if (!currentMissing && candidateHasRawHost && !rawHost.equals(currentQueryHost)) {
            return true;
        }
        return isAfter(candidateTime, current.getLastSeen());
    }

    private boolean isAfter(Instant candidate, String currentValue) {
        if (candidate == null) {
            return false;
        }
        if (currentValue == null || currentValue.isBlank()) {
            return true;
        }
        try {
            return candidate.isAfter(Instant.parse(currentValue));
        } catch (Exception ignored) {
            return true;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeRole(String rawRole) {
        String role = rawRole == null ? "" : rawRole.trim().toLowerCase(Locale.ROOT);
        if ("host".equals(role) || "server".equals(role)) {
            return "host";
        }
        if ("vm".equals(role)) {
            return "vm";
        }
        if ("container".equals(role)) {
            return "container";
        }
        return "host";
    }

    private String resolveEntityName(String role, String host, String hostName, String vmName, String containerName) {
        if ("host".equals(role)) {
            return firstNonBlank(hostName, host);
        }
        if ("vm".equals(role)) {
            return firstNonBlank(vmName, host);
        }
        return firstNonBlank(containerName, host);
    }

    private String resolveParentHost(String role, String serverName, String vmName) {
        if ("vm".equals(role)) {
            return serverName;
        }
        if ("container".equals(role)) {
            String vm = vmName == null ? null : vmName.trim();
            if (vm != null && !vm.isBlank()) {
                return vm;
            }
            return serverName;
        }
        return null;
    }
}
