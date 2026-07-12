package com.privatecloud.service;

import com.privatecloud.dto.AlertRuleView;
import com.privatecloud.dto.AlertRuleRuntimeView;
import com.privatecloud.entity.AlertRule;
import com.privatecloud.entity.AlertRuleAssignment;
import com.privatecloud.entity.AlertRuleRequest;
import com.privatecloud.repository.AlertRuleAssignmentRepository;
import com.privatecloud.repository.AlertRuleRepository;
import com.privatecloud.repository.AlertRuleRequestRepository;
import com.privatecloud.security.AuthSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AlertRuleService {
    public static final String SYSTEM_PLACEHOLDER_RULE = "__SYS_REQUEST_PLACEHOLDER__";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");

    private final AlertRuleRepository ruleRepository;
    private final AlertRuleAssignmentRepository assignmentRepository;
    private final AlertRuleRequestRepository requestRepository;
    private final AccessControlService accessControlService;

    public AlertRuleService(AlertRuleRepository ruleRepository,
                            AlertRuleAssignmentRepository assignmentRepository,
                            AlertRuleRequestRepository requestRepository,
                            AccessControlService accessControlService) {
        this.ruleRepository = ruleRepository;
        this.assignmentRepository = assignmentRepository;
        this.requestRepository = requestRepository;
        this.accessControlService = accessControlService;
    }

    public List<AlertRuleView> listRulesForHost(String host, AuthSession session) {
        boolean withHost = host != null && !host.isBlank();
        boolean admin = accessControlService.isAdmin();
        if (!admin) {
            List<String> allowed = accessControlService.allowedHosts();
            if (allowed == null || allowed.isEmpty()) {
                return List.of();
            }
        }
        if (withHost && !accessControlService.isHostAllowed(host)) {
            return List.of();
        }
        List<AlertRule> rules = ruleRepository.findByNameNotOrderByUpdatedAtDesc(SYSTEM_PLACEHOLDER_RULE);
        Map<Long, AlertRuleRequest> latestDeleteByRuleId = new HashMap<>();
        for (AlertRuleRequest request : requestRepository.findByActionOrderByRequestedAtDesc("DELETE")) {
            if (request.getRuleId() != null) {
                latestDeleteByRuleId.putIfAbsent(request.getRuleId(), request);
            }
        }
        Map<Long, AlertRuleAssignment> assignmentByRuleId = new HashMap<>();
        Map<Long, AlertRuleRequest> pendingByRuleId = new HashMap<>();
        if (withHost) {
            for (AlertRuleAssignment assignment : assignmentRepository.findByHost(host)) {
                AlertRule assignmentRule = assignment.getRule();
                if (assignmentRule != null && assignmentRule.getId() != null) {
                    assignmentByRuleId.put(assignmentRule.getId(), assignment);
                }
            }
            for (AlertRuleRequest request : requestRepository.findByHostAndStatusOrderByRequestedAtDesc(host, "PENDING")) {
                if (request.getRuleId() != null) {
                    pendingByRuleId.putIfAbsent(request.getRuleId(), request);
                }
            }
        }

        return rules.stream().filter(rule -> {
            AlertRuleRequest latestDelete = latestDeleteByRuleId.get(rule.getId());
            if (latestDelete != null) {
                String status = latestDelete.getStatus();
                if (status != null && !"REJECTED".equalsIgnoreCase(status)) {
                    return false;
                }
            }
            return true;
        }).map(rule -> {
            Optional<AlertRuleAssignment> assignment = withHost
                    ? Optional.ofNullable(assignmentByRuleId.get(rule.getId()))
                    : Optional.empty();
            boolean installed = assignment.isPresent();
            boolean enabled = assignment.map(AlertRuleAssignment::isEnabled).orElse(false);
            Optional<AlertRuleRequest> pending = withHost
                    ? Optional.ofNullable(pendingByRuleId.get(rule.getId()))
                    : Optional.empty();
            String pendingAction = pending.map(AlertRuleRequest::getAction).orElse(null);
            String pendingStatus = pending.map(AlertRuleRequest::getStatus).orElse(null);
            return new AlertRuleView(
                    rule.getId(),
                    rule.getName(),
                    rule.getMetric(),
                    rule.getThreshold(),
                    normalizeSeverity(rule.getSeverity()),
                    installed,
                    enabled,
                    rule.getUpdatedAt(),
                    pendingAction,
                    pendingStatus
            );
        }).collect(Collectors.toList());
    }

    public AlertRule createRule(AlertRule rule) {
        rule.setSeverity(normalizeSeverity(rule.getSeverity()));
        return ruleRepository.save(rule);
    }

    public AlertRule updateRule(AlertRule rule) {
        rule.setSeverity(normalizeSeverity(rule.getSeverity()));
        return ruleRepository.save(rule);
    }

    public void deleteRule(Long id) {
        ruleRepository.deleteById(id);
    }

    @Transactional
    public void deleteRuleCompletely(Long id) {
        AlertRule rule = ruleRepository.findById(id).orElse(null);
        if (rule == null) {
            return;
        }

        List<AlertRuleAssignment> assignments = assignmentRepository.findByRuleId(id);
        if (!assignments.isEmpty()) {
            assignmentRepository.deleteAll(assignments);
        }

        List<AlertRuleRequest> requests = requestRepository.findByRuleId(id);
        if (!requests.isEmpty()) {
            requests.forEach(item -> item.setRule(null));
            requestRepository.saveAll(requests);
            requestRepository.flush();
        }

        ruleRepository.delete(rule);
        ruleRepository.flush();
    }

    public void softDeleteRule(Long id) {
        AlertRule rule = ruleRepository.findById(id).orElse(null);
        if (rule == null) {
            return;
        }
        rule.setEnabled(false);
        ruleRepository.save(rule);
        List<AlertRuleAssignment> assignments = assignmentRepository.findByRuleId(id);
        if (!assignments.isEmpty()) {
            assignments.forEach(item -> item.setEnabled(false));
            assignmentRepository.saveAll(assignments);
        }
    }

    public Optional<AlertRule> findRule(Long id) {
        return ruleRepository.findById(id);
    }

    public Optional<AlertRule> findAnyRule() {
        return ruleRepository.findAll().stream().findFirst();
    }

    public AlertRuleAssignment assignRule(AlertRule rule, String host, boolean enabled) {
        AlertRuleAssignment assignment = assignmentRepository.findByRuleIdAndHost(rule.getId(), host)
                .orElseGet(() -> new AlertRuleAssignment(rule, host, enabled));
        assignment.setEnabled(enabled);
        return assignmentRepository.save(assignment);
    }

    public boolean isRuleEnabledForHost(Long ruleId, String host) {
        return assignmentRepository.findByRuleIdAndHost(ruleId, host)
                .map(AlertRuleAssignment::isEnabled)
                .orElse(false);
    }

    public boolean isRuleInstalledForHost(Long ruleId, String host) {
        return assignmentRepository.findByRuleIdAndHost(ruleId, host).isPresent();
    }

    public void unassignRule(Long ruleId, String host) {
        assignmentRepository.findByRuleIdAndHost(ruleId, host)
                .ifPresent(assignmentRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<AlertRuleRuntimeView> listRuntimeRulesForHost(String host) {
        if (host == null || host.isBlank()) {
            return List.of();
        }
        return assignmentRepository.findByHost(host).stream()
                .filter(AlertRuleAssignment::isEnabled)
                .map(AlertRuleAssignment::getRule)
                .filter(rule -> rule != null && rule.isEnabled() && !SYSTEM_PLACEHOLDER_RULE.equals(rule.getName()))
                .sorted(Comparator.comparing(AlertRule::getUpdatedAt).reversed())
                .map(rule -> new AlertRuleRuntimeView(
                        rule.getId(),
                        rule.getName(),
                        rule.getMetric(),
                        normalizeMetricKey(rule.getMetric()),
                        rule.getThreshold(),
                        normalizeSeverity(rule.getSeverity()),
                        parseThresholdValue(rule.getThreshold())
                ))
                .filter(view -> view.getMetricKey() != null && !view.getMetricKey().isBlank() && view.getThresholdValue() != null)
                .collect(Collectors.toList());
    }

    public String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return "warning";
        }
        String normalized = severity.trim().toLowerCase(Locale.ROOT);
        if ("critical".equals(normalized) || "severe".equals(normalized) || "严重".equals(normalized)) {
            return "critical";
        }
        if ("alert".equals(normalized) || "high".equals(normalized) || "警报".equals(normalized)) {
            return "alert";
        }
        if ("warning".equals(normalized) || "warn".equals(normalized) || "medium".equals(normalized) || "low".equals(normalized) || "警告".equals(normalized)) {
            return "warning";
        }
        return "warning";
    }

    public String normalizeMetricKey(String metric) {
        if (metric == null || metric.isBlank()) {
            return "";
        }
        String raw = metric.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("cpu") && (lower.contains("process") || raw.contains("进程"))) {
            return "abnormal_process_cpu";
        }
        if ((lower.contains("mem") || lower.contains("memory") || raw.contains("内存")) && (lower.contains("process") || raw.contains("进程"))) {
            return "abnormal_process_mem";
        }
        if (lower.contains("cpu") || raw.contains("CPU")) {
            return "cpu";
        }
        if (lower.contains("mem") || lower.contains("memory") || raw.contains("内存")) {
            return "mem";
        }
        if (lower.contains("disk") || lower.contains("storage") || raw.contains("磁盘")) {
            return "disk";
        }
        if (lower.contains("network") || lower.contains("net") || raw.contains("网络")) {
            if (lower.contains("out") || lower.contains("tx") || raw.contains("出")) {
                return "net_out";
            }
            if (lower.contains("in") || lower.contains("rx") || raw.contains("入")) {
                return "net_in";
            }
        }
        return "";
    }

    public Double parseThresholdValue(String threshold) {
        if (threshold == null || threshold.isBlank()) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(threshold);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (Exception ignored) {
            return null;
        }
    }
}
