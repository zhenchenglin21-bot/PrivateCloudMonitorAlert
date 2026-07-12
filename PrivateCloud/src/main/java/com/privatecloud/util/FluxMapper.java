package com.privatecloud.util;

import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.privatecloud.dto.MetricDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class FluxMapper {

    public static List<MetricDTO> toSeries(List<FluxTable> tables) {
        List<MetricDTO> series = new ArrayList<>();
        if (tables == null) {
            return series;
        }
        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Instant t = record.getTime();
                Object v = record.getValue();
                if (t == null || v == null) {
                    continue;
                }
                Double value = null;
                if (v instanceof Number) {
                    value = ((Number) v).doubleValue();
                } else {
                    try {
                        value = Double.parseDouble(String.valueOf(v));
                    } catch (Exception ignored) {
                    }
                }
                if (value != null) {
                    series.add(new MetricDTO(t.toString(), value));
                }
            }
        }
        series.sort(Comparator.comparing(MetricDTO::getTime));
        return series;
    }
}
