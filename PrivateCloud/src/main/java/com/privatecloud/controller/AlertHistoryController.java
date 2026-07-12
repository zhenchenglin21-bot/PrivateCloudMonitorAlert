package com.privatecloud.controller;

import com.privatecloud.dto.AlertHistoryCreateRequest;
import com.privatecloud.dto.AlertFeedbackUpdateRequest;
import com.privatecloud.dto.ResultResponse;
import com.privatecloud.entity.AlertHistory;
import com.privatecloud.security.AuthContext;
import com.privatecloud.security.AuthSession;
import com.privatecloud.service.AccessControlService;
import com.privatecloud.service.AlertHistoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/alert-history")
public class AlertHistoryController {
    private static final int REASON_MAX_LENGTH = 500;
    private static final int RECOMMENDATION_MAX_LENGTH = 500;

    private final AlertHistoryService historyService;
    private final AccessControlService accessControlService;
    private final String ingestKey;

    public AlertHistoryController(AlertHistoryService historyService,
                                  AccessControlService accessControlService,
                                  @Value("${alert.ingest-key:}") String ingestKey) {
        this.historyService = historyService;
        this.accessControlService = accessControlService;
        this.ingestKey = ingestKey == null ? "" : ingestKey;
    }

    @GetMapping
    public ResultResponse<?> listHistory(@RequestParam(value = "host", required = false) String host,
                                         @RequestParam(value = "level", required = false) String level,
                                         @RequestParam(value = "thresholdType", required = false) String thresholdType,
                                         @RequestParam(value = "status", required = false) String status,
                                         @RequestParam(value = "page", defaultValue = "1") Integer page,
                                         @RequestParam(value = "size", defaultValue = "20") Integer size) {
        AuthSession session = AuthContext.get();
        boolean admin = accessControlService.isAdmin();
        return ResultResponse.success(historyService.listHistory(host, level, thresholdType, status, page, size, session, admin));
    }

    @GetMapping("/feedback-summary")
    public ResultResponse<?> feedbackSummary(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                             @RequestParam(value = "host", required = false) String host,
                                             @RequestParam(value = "ruleName", required = false) String ruleName,
                                             @RequestParam(value = "metricName", required = false) String metricName) {
        AuthSession session = AuthContext.get();
        boolean trustedAgent = !ingestKey.isBlank() && ingestKey.equals(apiKey);
        boolean admin = trustedAgent || accessControlService.isAdmin();
        if (!trustedAgent) {
            if (!admin && host != null && !host.isBlank() && !accessControlService.isHostAllowed(host)) {
                return ResultResponse.fail("forbidden");
            }
            if (session == null && !admin) {
                return ResultResponse.fail("forbidden");
            }
        }
        return ResultResponse.success(historyService.summarizeFeedback(host, ruleName, metricName, session, admin));
    }

    @GetMapping("/today-summary")
    public ResultResponse<?> todaySummary() {
        AuthSession session = AuthContext.get();
        boolean admin = accessControlService.isAdmin();
        return ResultResponse.success(historyService.summarizeToday(session, admin));
    }

    @PostMapping("/{id}/feedback")
    public ResultResponse<?> updateFeedback(@org.springframework.web.bind.annotation.PathVariable Long id,
                                            @RequestBody AlertFeedbackUpdateRequest request) {
        AuthSession session = AuthContext.get();
        boolean admin = accessControlService.isAdmin();
        try {
            return ResultResponse.success(historyService.updateFeedback(id, request.getFeedbackStatus(), request.getComment(), session, admin));
        } catch (IllegalArgumentException ex) {
            return ResultResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/ingest")
    public ResultResponse<?> ingest(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                    @RequestBody AlertHistoryCreateRequest request) {
        if (ingestKey.isBlank() || apiKey == null || !ingestKey.equals(apiKey)) {
            return ResultResponse.fail("forbidden");
        }
        AlertHistory history = new AlertHistory();
        history.setHost(request.getHost());
        history.setRuleName(request.getRuleName());
        history.setMetricName(request.getMetricName());
        history.setAlertState(request.getAlertState());
        history.setPreviousState(request.getPreviousState());
        history.setThresholdType(request.getThresholdType());
        history.setLevel(request.getLevel() == null ? "warning" : request.getLevel());
        history.setStatus(request.getStatus() == null ? "firing" : request.getStatus());
        history.setReason(trimToLength(request.getReason(), REASON_MAX_LENGTH));
        history.setRecommendation(trimToLength(request.getRecommendation(), RECOMMENDATION_MAX_LENGTH));
        history.setDecisionSource(request.getDecisionSource());
        history.setFeedbackStatus(request.getFeedbackStatus());
        history.setFeedbackComment(request.getFeedbackComment());
        history.setDurationSeconds(request.getDurationSeconds());
        history.setConfidenceScore(request.getConfidenceScore());
        history.setFingerprint(request.getFingerprint());
        history.setContextJson(request.getContextJson());
        history.setValue(request.getValue());
        history.setThresholdValue(request.getThresholdValue());
        history.setStaticThreshold(request.getStaticThreshold());
        history.setDynamicThreshold(request.getDynamicThreshold());
        history.setMeanValue(request.getMeanValue());
        history.setStdValue(request.getStdValue());
        history.setTrendValue(request.getTrendValue());
        if (request.getTime() != null && !request.getTime().isBlank()) {
            try {
                history.setOccurredAt(Instant.parse(request.getTime()));
            } catch (Exception ignored) {
            }
        }
        historyService.save(history);
        return ResultResponse.success(true);
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }
}
