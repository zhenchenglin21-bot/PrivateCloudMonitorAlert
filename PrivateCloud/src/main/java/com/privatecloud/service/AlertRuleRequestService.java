package com.privatecloud.service;

import com.privatecloud.dto.AlertRuleRequestView;
import com.privatecloud.entity.AlertRule;
import com.privatecloud.entity.AlertRuleRequest;
import com.privatecloud.repository.AlertRuleRequestRepository;
import com.privatecloud.repository.UserRepository;
import com.privatecloud.security.AuthSession;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AlertRuleRequestService {
    private static final String RULE_DEFINITION_HOST_PLACEHOLDER = "";

    private final AlertRuleRequestRepository requestRepository;
    private final AlertRuleService ruleService;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;

    public AlertRuleRequestService(AlertRuleRequestRepository requestRepository,
                                   AlertRuleService ruleService,
                                   AccessControlService accessControlService,
                                   UserRepository userRepository) {
        this.requestRepository = requestRepository;
        this.ruleService = ruleService;
        this.accessControlService = accessControlService;
        this.userRepository = userRepository;
    }

    public AlertRuleRequest createRequest(Long ruleId, String host, String action,
                                          String name, String metric, String threshold, String severity,
                                          AuthSession session) {
        String normalized = normalizeAction(action);
        String requestHost = normalizeHost(host);
        String normalizedSeverity = ruleService.normalizeSeverity(severity);
        if ("CREATE".equals(normalized)) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name required");
            }
            AlertRuleRequest request = new AlertRuleRequest(
                    name,
                    metric,
                    threshold,
                    normalizedSeverity,
                    normalized,
                    session.getUserId()
            );
            request.setHost(RULE_DEFINITION_HOST_PLACEHOLDER);
            return saveCreateRequestCompat(request);
        }
        if ("UPDATE".equals(normalized)) {
            if (ruleId == null) {
                throw new IllegalArgumentException("ruleId required");
            }
            AlertRule rule = ruleService.findRule(ruleId).orElseThrow(() -> new IllegalArgumentException("rule not found"));
            Optional<AlertRuleRequest> pendingUpdate = requestRepository
                    .findFirstByRuleIdAndActionAndStatusOrderByRequestedAtDesc(ruleId, "UPDATE", "PENDING");
            if (pendingUpdate.isPresent()) {
                throw new IllegalArgumentException("pending request exists");
            }
            AlertRuleRequest request = new AlertRuleRequest(rule, RULE_DEFINITION_HOST_PLACEHOLDER, normalized, session.getUserId());
            request.setRuleName(firstNonBlank(name, rule.getName()));
            request.setMetric(firstNonBlank(metric, rule.getMetric()));
            request.setThreshold(firstNonBlank(threshold, rule.getThreshold()));
            request.setSeverity(ruleService.normalizeSeverity(firstNonBlank(severity, rule.getSeverity())));
            return requestRepository.save(request);
        }
        if ("DELETE".equals(normalized)) {
            if (ruleId == null) {
                throw new IllegalArgumentException("ruleId required");
            }
            Optional<AlertRuleRequest> pendingDelete = requestRepository
                    .findFirstByRuleIdAndActionAndStatusOrderByRequestedAtDesc(ruleId, "DELETE", "PENDING");
            if (pendingDelete.isPresent()) {
                throw new IllegalArgumentException("pending request exists");
            }
            AlertRule rule = ruleService.findRule(ruleId).orElseThrow(() -> new IllegalArgumentException("rule not found"));
            AlertRuleRequest request = new AlertRuleRequest(rule, RULE_DEFINITION_HOST_PLACEHOLDER, normalized, session.getUserId());
            fillRuleSnapshot(request, rule);
            return requestRepository.save(request);
        }
        if (requestHost == null) {
            throw new IllegalArgumentException("host required");
        }
        if (!accessControlService.isHostAllowed(requestHost)) {
            throw new IllegalArgumentException("forbidden");
        }
        AlertRule rule = ruleService.findRule(ruleId).orElseThrow(() -> new IllegalArgumentException("rule not found"));
        boolean installed = ruleService.isRuleInstalledForHost(ruleId, requestHost);
        boolean currentEnabled = ruleService.isRuleEnabledForHost(ruleId, requestHost);
        if ("ENABLE".equals(normalized) && currentEnabled) {
            throw new IllegalArgumentException("rule already enabled");
        }
        if ("DISABLE".equals(normalized) && !currentEnabled) {
            throw new IllegalArgumentException("rule already disabled");
        }
        if ("UNINSTALL".equals(normalized) && !installed) {
            throw new IllegalArgumentException("rule not installed");
        }
        Optional<AlertRuleRequest> pending = requestRepository
                .findFirstByRuleIdAndHostAndStatusOrderByRequestedAtDesc(ruleId, requestHost, "PENDING");
        if (pending.isPresent()) {
            throw new IllegalArgumentException("pending request exists");
        }
        AlertRuleRequest request = new AlertRuleRequest(rule, requestHost, normalized, session.getUserId());
        fillRuleSnapshot(request, rule);
        return requestRepository.save(request);
    }

    @Transactional(readOnly = true)
    public List<AlertRuleRequestView> listRequests(String status, String category, AuthSession session, boolean admin) {
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        String normalizedCategory = normalizeCategory(category);
        List<AlertRuleRequest> requests;
        if (admin) {
            requests = normalizedStatus == null
                    ? requestRepository.findAll()
                    : requestRepository.findByStatusOrderByRequestedAtDesc(normalizedStatus);
        } else {
            List<String> allowed = accessControlService.allowedHosts();
            if (allowed == null || allowed.isEmpty()) {
                return List.of();
            }
            requests = requestRepository.findByRequestedByOrderByRequestedAtDesc(session.getUserId());
            if (normalizedStatus != null) {
                requests = requests.stream()
                        .filter(item -> item.getStatus() != null && item.getStatus().equalsIgnoreCase(normalizedStatus))
                        .collect(Collectors.toList());
            }
        }

        Set<Long> userIds = new HashSet<>();
        for (AlertRuleRequest request : requests) {
            if (request.getRequestedBy() != null) {
                userIds.add(request.getRequestedBy());
            }
            if (request.getReviewedBy() != null) {
                userIds.add(request.getReviewedBy());
            }
        }
        Map<Long, String> usernameById = new HashMap<>();
        userRepository.findAllById(userIds).forEach(user -> usernameById.put(user.getId(), user.getUsername()));

        return requests.stream()
                .filter(item -> matchesCategory(item, normalizedCategory))
                .sorted(Comparator.comparing(AlertRuleRequest::getRequestedAt).reversed())
                .map(item -> toView(item, usernameById))
                .collect(Collectors.toList());
    }

    @Transactional
    public AlertRuleRequest reviewRequest(Long id, boolean approved, String comment, AuthSession session) {
        AlertRuleRequest request = requestRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("request not found"));
        if (!"PENDING".equalsIgnoreCase(request.getStatus())) {
            return request;
        }
        request.setStatus(approved ? "APPROVED" : "REJECTED");
        request.setReviewedBy(session.getUserId());
        request.setReviewedAt(Instant.now());
        request.setComment(comment);

        if (approved) {
            String action = request.getAction();
            Long ruleId = request.getRuleId();
            if ("CREATE".equalsIgnoreCase(action)) {
                AlertRule rule = new AlertRule(
                        request.getRuleName(),
                        request.getMetric(),
                        request.getThreshold(),
                        ruleService.normalizeSeverity(request.getSeverity())
                );
                ruleService.createRule(rule);
            } else if ("UPDATE".equalsIgnoreCase(action)) {
                if (ruleId != null) {
                    AlertRule rule = ruleService.findRule(ruleId).orElse(null);
                    if (rule != null) {
                        rule.setName(firstNonBlank(request.getRuleName(), rule.getName()));
                        rule.setMetric(firstNonBlank(request.getMetric(), rule.getMetric()));
                        rule.setThreshold(firstNonBlank(request.getThreshold(), rule.getThreshold()));
                        rule.setSeverity(ruleService.normalizeSeverity(firstNonBlank(request.getSeverity(), rule.getSeverity())));
                        ruleService.updateRule(rule);
                    }
                }
            } else if ("DELETE".equalsIgnoreCase(action)) {
                if (ruleId != null) {
                    request.setRule(null);
                    requestRepository.saveAndFlush(request);
                    ruleService.deleteRuleCompletely(ruleId);
                    return request;
                }
            } else if ("UNINSTALL".equalsIgnoreCase(action)) {
                if (ruleId != null && request.getHost() != null) {
                    ruleService.unassignRule(ruleId, request.getHost());
                }
            } else {
                boolean enable = "ENABLE".equalsIgnoreCase(request.getAction());
                AlertRule rule = ruleId == null ? null : ruleService.findRule(ruleId).orElse(null);
                if (rule != null) ruleService.assignRule(rule, request.getHost(), enable);
            }
        }
        return requestRepository.save(request);
    }

    private String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "ENABLE";
        }
        String normalized = action.trim().toUpperCase();
        if (!normalized.equals("ENABLE") && !normalized.equals("DISABLE")
                && !normalized.equals("CREATE") && !normalized.equals("DELETE")
                && !normalized.equals("UPDATE") && !normalized.equals("UNINSTALL")) {
            return "ENABLE";
        }
        return normalized;
    }

    private String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String value = host.trim();
        return value.isEmpty() ? null : value;
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String normalized = category.trim().toLowerCase(Locale.ROOT);
        if ("rule".equals(normalized) || "definition".equals(normalized)) {
            return "rule";
        }
        if ("assignment".equals(normalized)) {
            return "assignment";
        }
        return null;
    }

    private boolean matchesCategory(AlertRuleRequest request, String category) {
        if (category == null) {
            return true;
        }
        String action = request.getAction() == null ? "" : request.getAction().trim().toUpperCase(Locale.ROOT);
        if ("rule".equals(category)) {
            return "CREATE".equals(action) || "UPDATE".equals(action) || "DELETE".equals(action);
        }
        return "ENABLE".equals(action) || "DISABLE".equals(action) || "UNINSTALL".equals(action);
    }

    private AlertRuleRequestView toView(AlertRuleRequest request, Map<Long, String> usernameById) {
        String requestedBy = usernameById.getOrDefault(request.getRequestedBy(), "--");
        String reviewedBy = request.getReviewedBy() == null ? null : usernameById.getOrDefault(request.getReviewedBy(), "--");
        Long ruleId = request.getRuleId();
        String ruleName = request.getRuleName();
        String metric = request.getMetric();
        String threshold = request.getThreshold();
        String severity = request.getSeverity();
        if ((isBlank(ruleName) || isBlank(metric) || isBlank(threshold) || isBlank(severity)) && ruleId != null) {
            AlertRule rule = ruleService.findRule(ruleId).orElse(null);
            if (rule != null) {
                ruleName = firstNonBlank(ruleName, rule.getName());
                metric = firstNonBlank(metric, rule.getMetric());
                threshold = firstNonBlank(threshold, rule.getThreshold());
                severity = firstNonBlank(severity, rule.getSeverity());
            }
        }
        return new AlertRuleRequestView(
                request.getId(),
                ruleId,
                ruleName,
                metric,
                threshold,
                ruleService.normalizeSeverity(severity),
                isBlank(request.getHost()) ? null : request.getHost(),
                request.getAction(),
                request.getStatus(),
                requestedBy,
                request.getRequestedAt(),
                reviewedBy,
                request.getReviewedAt(),
                request.getComment()
        );
    }

    private void fillRuleSnapshot(AlertRuleRequest request, AlertRule rule) {
        request.setRuleName(rule.getName());
        request.setMetric(rule.getMetric());
        request.setThreshold(rule.getThreshold());
        request.setSeverity(ruleService.normalizeSeverity(rule.getSeverity()));
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AlertRuleRequest saveCreateRequestCompat(AlertRuleRequest request) {
        try {
            return requestRepository.save(request);
        } catch (DataIntegrityViolationException ex) {
            if (!isRuleIdNotNullError(ex)) {
                throw ex;
            }
            AlertRule placeholder = ruleService.findAnyRule()
                    .orElseGet(this::createSystemPlaceholderRule);
            request.setRule(placeholder);
            return requestRepository.save(request);
        }
    }

    private boolean isRuleIdNotNullError(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String m = message.toLowerCase(Locale.ROOT);
                if (m.contains("rule_id") && (m.contains("cannot be null") || m.contains("null"))) {
                    return true;
                }
                if (m.contains("alertrulerequest.rule")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private AlertRule createSystemPlaceholderRule() {
        AlertRule rule = new AlertRule(
                AlertRuleService.SYSTEM_PLACEHOLDER_RULE,
                "internal",
                "N/A",
                "warning"
        );
        rule.setEnabled(false);
        return ruleService.createRule(rule);
    }
}
