package com.privatecloud.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

public class TimeUtil {

    private static final DateTimeFormatter SPACE_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<DateTimeFormatter> LOCAL_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    );

    private TimeUtil() {
    }

    public static String buildRange(String start, String end, String defaultStart) {
        String normalizedStart = normalizeTimeExpr(start);
        if (normalizedStart == null) {
            normalizedStart = normalizeTimeExpr(defaultStart);
        }
        if (normalizedStart == null) {
            normalizedStart = "-1h";
        }

        String normalizedEnd = normalizeTimeExpr(end);
        if (normalizedEnd == null) {
            return String.format("|> range(start: %s)", normalizedStart);
        }
        return String.format("|> range(start: %s, stop: %s)", normalizedStart, normalizedEnd);
    }

    public static String escapeTagValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalizeTimeExpr(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("time(v:")) {
            return value;
        }
        if (value.startsWith("-")) {
            return value;
        }
        if (isFluxRelativeExpr(value)) {
            return value;
        }

        Instant instant = tryParseInstant(value);
        if (instant != null) {
            return toFluxTime(instant);
        }
        return value;
    }

    private static boolean isFluxRelativeExpr(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("now()")
                || lower.matches("^-\\d+[smhdw]$")
                || lower.matches("^\\d+[smhdw]$");
    }

    private static Instant tryParseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
        }

        try {
            LocalDateTime localDateTime = LocalDateTime.parse(value, SPACE_DATE_TIME);
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
        }

        for (DateTimeFormatter formatter : LOCAL_TIME_FORMATS) {
            try {
                LocalDateTime localDateTime = LocalDateTime.parse(value, formatter);
                return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException ignored) {
            }
        }

        try {
            LocalDate localDate = LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
        }

        return null;
    }

    private static String toFluxTime(Instant instant) {
        return String.format("time(v: \"%s\")", instant.toString());
    }
}
