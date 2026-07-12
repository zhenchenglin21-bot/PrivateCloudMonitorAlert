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
public class NetworkService {

    private final InfluxDBRepository repository;
    private final InfluxProperties influxProperties;
    private static final List<String> IN_FIELDS = Arrays.asList(
            "bytes_recv", "bytes_received", "rx_bytes", "receive_bytes", "in_bytes", "bytes_in", "bytes_rcvd", "in_octets"
    );
    private static final List<String> OUT_FIELDS = Arrays.asList(
            "bytes_sent", "bytes_send", "tx_bytes", "transmit_bytes", "out_bytes", "bytes_out", "bytes_xmit", "out_octets"
    );

    public NetworkService(InfluxDBRepository repository, InfluxProperties influxProperties) {
        this.repository = repository;
        this.influxProperties = influxProperties;
    }

    public List<FluxTable> getNetwork(String host, String field, String start, String role) {
        String f = (field == null || field.isBlank()) ? "bytes_sent" : field;
        String range = TimeUtil.buildRange(start, null, "-1h");
        String hostValue = TimeUtil.escapeTagValue(host);
        String fieldValue = TimeUtil.escapeTagValue(f);
        StringBuilder flux = new StringBuilder(String.format(
                "from(bucket:\"%s\")"
                        + range
                        + measurementFilter()
                        + "|> filter(fn:(r)=> r._field == \"%s\")"
                        + "|> filter(fn:(r)=> r.host == \"%s\")",
                influxProperties.getBucket(),
                fieldValue,
                hostValue
        ));
        appendRoleFilter(flux, role);
        return repository.query(flux.toString());
    }

    public List<MetricDTO> getNetworkHistory(String host, String field, String start, String end, String window, String role, String name) {
        return getNetworkHistory(host, field, start, end, window, role, name, true);
    }

    private List<MetricDTO> getNetworkHistory(String host, String field, String start, String end, String window, String role, String name, boolean useDerivative) {
        String f = (field == null || field.isBlank()) ? "bytes_sent" : field;
        String range = TimeUtil.buildRange(start, end, "-1h");
        String w = (window == null || window.isBlank()) ? "1m" : window;
        String hostValue = TimeUtil.escapeTagValue(host);
        String fieldValue = TimeUtil.escapeTagValue(f);
        StringBuilder flux = new StringBuilder(String.format(
                "import \"types\"\n"
                        + "from(bucket:\"%s\")"
                        + range
                        + measurementFilter()
                        + "|> filter(fn:(r)=> r._field == \"%s\")",
                influxProperties.getBucket(),
                fieldValue
        ));
        appendRoleFilter(flux, role);
        appendTargetFilter(flux, hostValue, role, name);
        appendNumericValueFilter(flux);
        flux.append(String.format("|> aggregateWindow(every: %s, fn: mean, createEmpty: false)", w));
        if (useDerivative && isCounterField(f)) {
            flux.append("|> derivative(unit: 1s, nonNegative: true)");
        }
        List<FluxTable> tables = repository.query(flux.toString());
        return FluxMapper.toSeries(tables);
    }

    public List<MetricDTO> getNetworkHistoryByDirection(String host, String direction, String start, String end, String window, String role, String name) {
        boolean ingress = direction == null || direction.isBlank() || "in".equalsIgnoreCase(direction);
        List<String> candidates = ingress ? IN_FIELDS : OUT_FIELDS;
        for (String field : candidates) {
            List<MetricDTO> series = getNetworkHistory(host, field, start, end, window, role, name);
            if (series != null && !series.isEmpty()) {
                return series;
            }
            // Fallback for host metrics where net measurement may not have role tag.
            if ("host".equalsIgnoreCase(role) || "server".equalsIgnoreCase(role)) {
                series = getNetworkHistory(host, field, start, end, window, null, null, true);
                if (series != null && !series.isEmpty()) {
                    return series;
                }
            }
        }
        return List.of();
    }

    private boolean isCounterField(String field) {
        String f = field == null ? "" : field.toLowerCase();
        return f.contains("bytes")
                || f.contains("rx")
                || f.contains("tx")
                || f.contains("receive")
                || f.contains("transmit")
                || f.contains("in_")
                || f.contains("out_");
    }

    private String measurementFilter() {
        return "|> filter(fn:(r)=> r._measurement == \"net\" or r._measurement == \"network\" or r._measurement == \"network_interface\")";
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
            flux.append("|> filter(fn:(r)=> (not exists r.role) or r.role == \"host\" or r.role == \"server\")");
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
