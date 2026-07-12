package com.privatecloud.service;

import com.privatecloud.dto.AlertHistoryView;
import com.privatecloud.dto.AlertFeedbackSummaryView;
import com.privatecloud.dto.PagedResult;
import com.privatecloud.dto.AlertTodaySummaryView;
import com.privatecloud.entity.AlertHistory;
import com.privatecloud.repository.AlertHistoryRepository;
import com.privatecloud.security.AuthSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AlertHistoryService {

    private final AlertHistoryRepository historyRepository;
    private final AccessControlService accessControlService;
    private final AlertAgentService alertAgentService;

    public AlertHistoryService(
            AlertHistoryRepository historyRepository,
            AccessControlService accessControlService,
            AlertAgentService alertAgentService
    ) {
        this.historyRepository = historyRepository;
        this.accessControlService = accessControlService;
        this.alertAgentService = alertAgentService;
    }

    public PagedResult<AlertHistoryView> listHistory(String host, String level, String thresholdType, String status, Integer page, Integer size, AuthSession session, boolean admin) {
        String levelFilter = normalizeLevelFilter(level);
        String thresholdTypeFilter = normalize(thresholdType);
        String statusFilter = normalize(status);
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = size == null ? 20 : Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "occurredAt"));
        Specification<AlertHistory> specification = buildHistorySpecification(host, levelFilter, thresholdTypeFilter, statusFilter, session, admin);
        if (specification == null) {
            return new PagedResult<>(List.of(), 0, safePage, safeSize);
        }
        Page<AlertHistory> historyPage = historyRepository.findAll(specification, pageable);
        List<AlertHistoryView> items = historyPage.getContent().stream()
                .map(this::toView)
                .collect(Collectors.toList());
        return new PagedResult<>(items, historyPage.getTotalElements(), safePage, safeSize);
    }

    public AlertHistory save(AlertHistory history) {
        if (history.getOccurredAt() == null) {
            history.setOccurredAt(Instant.now());
        }
        history.setLevel(normalizeLevelValue(history.getLevel()));
        history.setStatus(normalizeStatusValue(history.getStatus()));
        String normalizedFeedbackStatus = normalizeFeedbackStatus(history.getFeedbackStatus());
        history.setFeedbackStatus(normalizedFeedbackStatus);
        if (isBlank(history.getFeedbackSource())) {
            history.setFeedbackSource("DEFAULT");
        }

        if ("resolved".equalsIgnoreCase(history.getStatus()) && !isBlank(history.getFingerprint())) {
            Optional<AlertHistory> unresolved = historyRepository
                    .findFirstByFingerprintAndStatusNotOrderByOccurredAtDesc(history.getFingerprint(), "resolved");
            if (unresolved.isPresent()) {
                AlertHistory existing = unresolved.get();
                applyResolvedUpdate(existing, history);
                return historyRepository.save(existing);
            }
        }

        AlertHistory saved = historyRepository.save(history);
        if ("firing".equalsIgnoreCase(saved.getStatus())) {
            alertAgentService.enqueue(saved.getId());
        }
        return saved;
    }

    public AlertHistory updateFeedback(Long id, String feedbackStatus, String comment, AuthSession session, boolean admin) {
        AlertHistory history = historyRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("alert not found"));
        if (!admin && !accessControlService.isHostAllowed(history.getHost())) {
            throw new IllegalArgumentException("forbidden");
        }
        String normalizedStatus = normalizeFeedbackStatus(feedbackStatus);
        history.setFeedbackStatus(normalizedStatus);
        history.setFeedbackSource("MANUAL");
        history.setFeedbackComment(isBlank(comment) ? null : comment.trim());
        history.setFeedbackAt(Instant.now());
        return historyRepository.save(history);
    }

    public AlertFeedbackSummaryView summarizeFeedback(String host, String ruleName, String metricName, AuthSession session, boolean admin) {
        Specification<AlertHistory> base = buildFeedbackSummarySpecification(host, ruleName, metricName, session, admin);
        if (base == null) {
            return new AlertFeedbackSummaryView(
                    host,
                    ruleName,
                    metricName,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    0.0
            );
        }

        long totalCount = historyRepository.count(base);
        long effectiveCount = historyRepository.count(base.and(feedbackStatusEquals("VALID")));
        long falsePositiveCount = historyRepository.count(base.and(feedbackStatusEquals("FALSE_POSITIVE")));
        long labeledCount = effectiveCount + falsePositiveCount;
        long unresolvedCount = Math.max(totalCount - labeledCount, 0);
        double effectiveRate = labeledCount == 0 ? 0.0 : (double) effectiveCount / labeledCount;
        double falsePositiveRate = labeledCount == 0 ? 0.0 : (double) falsePositiveCount / labeledCount;
        return new AlertFeedbackSummaryView(
                host,
                ruleName,
                metricName,
                labeledCount,
                effectiveCount,
                falsePositiveCount,
                effectiveCount,
                falsePositiveCount,
                unresolvedCount,
                effectiveRate,
                falsePositiveRate
        );
    }

    public AlertTodaySummaryView summarizeToday(AuthSession session, boolean admin) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);
        Instant start = today.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();

        Specification<AlertHistory> base = buildTodayBaseSpecification(start, end, session, admin);
        if (base == null) {
            return new AlertTodaySummaryView(start, end, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0);
        }

        long total = historyRepository.count(base);
        long firing = historyRepository.count(base.and(equalLower("status", "firing")));
        long resolved = historyRepository.count(base.and(equalLower("status", "resolved")));
        long critical = historyRepository.count(base.and(equalLower("level", "critical")));
        long alert = historyRepository.count(base.and(equalLower("level", "alert")));
        long warning = historyRepository.count(base.and(equalLower("level", "warning")));
        long effective = historyRepository.count(base.and(feedbackStatusEquals("VALID")));
        long falsePositive = historyRepository.count(base.and(feedbackStatusEquals("FALSE_POSITIVE")));
        long feedbackTotal = effective + falsePositive;
        double feedbackCoverageRate = total == 0 ? 0.0 : (double) feedbackTotal / total;
        double effectiveRate = feedbackTotal == 0 ? 0.0 : (double) effective / feedbackTotal;
        return new AlertTodaySummaryView(
                start,
                end,
                total,
                firing,
                resolved,
                critical,
                alert,
                warning,
                effective,
                falsePositive,
                feedbackTotal,
                feedbackCoverageRate,
                effectiveRate
        );
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeLevelFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeLevelValue(value);
    }

    private AlertHistoryView toView(AlertHistory history) {
        String normalizedFeedbackStatus = normalizeFeedbackStatus(history.getFeedbackStatus());
        return new AlertHistoryView(
                history.getId(),
                history.getOccurredAt(),
                normalizeLevelValue(history.getLevel()),
                history.getRuleName(),
                history.getMetricName(),
                history.getAlertState(),
                history.getPreviousState(),
                history.getThresholdType(),
                history.getHost(),
                history.getStatus(),
                history.getReason(),
                history.getRecommendation(),
                history.getDecisionSource(),
                normalizedFeedbackStatus,
                history.getFeedbackSource(),
                history.getFeedbackComment(),
                history.getDurationSeconds(),
                history.getConfidenceScore(),
                history.getValue(),
                history.getThresholdValue(),
                history.getStaticThreshold(),
                history.getDynamicThreshold(),
                history.getMeanValue(),
                history.getStdValue(),
                history.getTrendValue(),
                history.getAgentStatus(),
                history.getAgentModel(),
                history.getAgentRiskScore(),
                history.getAgentPrediction(),
                history.getAgentAnalysis(),
                history.getAgentRecommendation(),
                history.getAgentRawResponse(),
                history.getAgentUpdatedAt()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeLevelValue(String value) {
        if (value == null || value.isBlank()) {
            return "warning";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "critical" -> "critical";
            case "alert" -> "alert";
            case "warning" -> "warning";
            default -> normalized;
        };
    }

    private String normalizeStatusValue(String value) {
        if (value == null || value.isBlank()) {
            return "firing";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "firing", "resolved", "steady" -> normalized;
            default -> "firing";
        };
    }

    private String normalizeFeedbackStatus(String feedbackStatus) {
        if (!isBlank(feedbackStatus)) {
            String normalized = feedbackStatus.trim().toUpperCase(Locale.ROOT);
            if ("FALSE_POSITIVE".equals(normalized)) {
                return "FALSE_POSITIVE";
            }
            if ("VALID".equals(normalized)) {
                return "VALID";
            }
            if ("UNLABELED".equals(normalized)) {
                return "UNLABELED";
            }
        }
        return "UNLABELED";
    }

    private void applyResolvedUpdate(AlertHistory target, AlertHistory resolved) {
        target.setLevel(normalizeLevelValue(resolved.getLevel()));
        target.setStatus("resolved");
        target.setOccurredAt(resolved.getOccurredAt());
        target.setAlertState(resolved.getAlertState());
        target.setPreviousState(resolved.getPreviousState());
        target.setReason(resolved.getReason());
        target.setRecommendation(resolved.getRecommendation());
        target.setDecisionSource(resolved.getDecisionSource());
        target.setDurationSeconds(resolved.getDurationSeconds());
        target.setConfidenceScore(resolved.getConfidenceScore());
        target.setThresholdType(resolved.getThresholdType());
        target.setThresholdValue(resolved.getThresholdValue());
        target.setStaticThreshold(resolved.getStaticThreshold());
        target.setDynamicThreshold(resolved.getDynamicThreshold());
        target.setMeanValue(resolved.getMeanValue());
        target.setStdValue(resolved.getStdValue());
        target.setTrendValue(resolved.getTrendValue());
        target.setValue(resolved.getValue());
        target.setContextJson(resolved.getContextJson());
    }

    private Specification<AlertHistory> buildHistorySpecification(String host, String level, String thresholdType, String status, AuthSession session, boolean admin) {
        Specification<AlertHistory> specification = Specification.where(null);
        if (admin) {
            if (!isBlank(host)) {
                specification = specification.and((root, query, cb) -> cb.equal(root.get("host"), host.trim()));
            }
        } else {
            if (!isBlank(host) && !accessControlService.isHostAllowed(host)) {
                return null;
            }
            List<String> allowed = accessControlService.allowedEntityHosts();
            if (allowed == null || allowed.isEmpty()) {
                return null;
            }
            if (!isBlank(host)) {
                specification = specification.and((root, query, cb) -> cb.equal(root.get("host"), host.trim()));
            } else {
                specification = specification.and((root, query, cb) -> root.get("host").in(allowed));
            }
        }
        if (!isBlank(level)) {
            specification = specification.and((root, query, cb) -> cb.equal(cb.lower(root.get("level")), level));
        }
        if (!isBlank(thresholdType)) {
            specification = specification.and((root, query, cb) -> cb.equal(cb.lower(root.get("thresholdType")), thresholdType));
        }
        if (!isBlank(status)) {
            specification = specification.and((root, query, cb) -> cb.equal(cb.lower(root.get("status")), status));
        }
        return specification;
    }

    private Specification<AlertHistory> buildFeedbackSummarySpecification(
            String host,
            String ruleName,
            String metricName,
            AuthSession session,
            boolean admin
    ) {
        String hostFilter = isBlank(host) ? null : host.trim();
        String ruleNameFilter = isBlank(ruleName) ? null : ruleName.trim().toLowerCase(Locale.ROOT);
        String metricNameFilter = isBlank(metricName) ? null : metricName.trim().toLowerCase(Locale.ROOT);

        Specification<AlertHistory> specification = Specification.where(null);
        if (admin) {
            if (hostFilter != null) {
                specification = specification.and((root, query, cb) -> cb.equal(root.get("host"), hostFilter));
            }
        } else {
            if (hostFilter != null && !accessControlService.isHostAllowed(hostFilter)) {
                return null;
            }
            List<String> allowedHosts = accessControlService.allowedEntityHosts();
            if (allowedHosts == null || allowedHosts.isEmpty()) {
                return null;
            }
            if (hostFilter != null) {
                specification = specification.and((root, query, cb) -> cb.equal(root.get("host"), hostFilter));
            } else {
                specification = specification.and((root, query, cb) -> root.get("host").in(allowedHosts));
            }
        }
        if (ruleNameFilter != null) {
            specification = specification.and((root, query, cb) -> cb.equal(cb.lower(root.get("ruleName")), ruleNameFilter));
        }
        if (metricNameFilter != null) {
            specification = specification.and((root, query, cb) -> cb.equal(cb.lower(root.get("metricName")), metricNameFilter));
        }
        return specification;
    }

    private Specification<AlertHistory> buildTodayBaseSpecification(
            Instant start,
            Instant end,
            AuthSession session,
            boolean admin
    ) {
        Specification<AlertHistory> specification = Specification.where(
                (root, query, cb) -> cb.and(
                        cb.greaterThanOrEqualTo(root.get("occurredAt"), start),
                        cb.lessThan(root.get("occurredAt"), end)
                )
        );
        if (admin) {
            return specification;
        }
        List<String> allowed = accessControlService.allowedEntityHosts();
        if (allowed == null || allowed.isEmpty()) {
            return null;
        }
        return specification.and((root, query, cb) -> root.get("host").in(allowed));
    }

    private Specification<AlertHistory> equalLower(String field, String value) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get(field)), value);
    }

    private Specification<AlertHistory> feedbackStatusEquals(String statusValue) {
        return (root, query, cb) -> cb.equal(cb.upper(root.get("feedbackStatus")), statusValue.toUpperCase(Locale.ROOT));
    }
}
