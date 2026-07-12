package com.privatecloud.controller;

import com.privatecloud.dto.*;
import com.privatecloud.entity.AlertRule;
import com.privatecloud.security.AuthContext;
import com.privatecloud.security.AuthSession;
import com.privatecloud.service.AccessControlService;
import com.privatecloud.service.AlertRuleRequestService;
import com.privatecloud.service.AlertRuleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/alert-rules")
public class AlertRuleController {

    private final AlertRuleService ruleService;
    private final AlertRuleRequestService requestService;
    private final AccessControlService accessControlService;
    private final String ingestKey;

    public AlertRuleController(AlertRuleService ruleService,
                               AlertRuleRequestService requestService,
                               AccessControlService accessControlService,
                               @Value("${alert.ingest-key:}") String ingestKey) {
        this.ruleService = ruleService;
        this.requestService = requestService;
        this.accessControlService = accessControlService;
        this.ingestKey = ingestKey == null ? "" : ingestKey;
    }

    @GetMapping
    public ResultResponse<?> listRules(@RequestParam(value = "host", required = false) String host) {
        AuthSession session = AuthContext.get();
        List<AlertRuleView> views = ruleService.listRulesForHost(host, session);
        return ResultResponse.success(views);
    }

    @GetMapping("/runtime")
    public ResultResponse<?> listRuntimeRules(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                              @RequestParam("host") String host) {
        if (ingestKey.isBlank() || apiKey == null || !ingestKey.equals(apiKey)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(ruleService.listRuntimeRulesForHost(host));
    }

    @PostMapping
    public ResultResponse<?> createRule(@RequestBody AlertRuleCreateRequest request) {
        return ResultResponse.fail("use request workflow");
    }

    @PutMapping("/{id}")
    public ResultResponse<?> updateRule(@PathVariable Long id, @RequestBody AlertRuleUpdateRequest request) {
        if (!accessControlService.isAdmin()) {
            return ResultResponse.fail("forbidden");
        }
        AlertRule rule = ruleService.findRule(id).orElse(null);
        if (rule == null) {
            return ResultResponse.fail("rule not found");
        }
        if (request.getName() != null) rule.setName(request.getName());
        if (request.getMetric() != null) rule.setMetric(request.getMetric());
        if (request.getThreshold() != null) rule.setThreshold(request.getThreshold());
        if (request.getSeverity() != null) rule.setSeverity(ruleService.normalizeSeverity(request.getSeverity()));
        if (request.getEnabled() != null) rule.setEnabled(request.getEnabled());
        return ResultResponse.success(ruleService.updateRule(rule));
    }

    @DeleteMapping("/{id}")
    public ResultResponse<?> deleteRule(@PathVariable Long id) {
        return ResultResponse.fail("use request workflow");
    }

    @PostMapping("/{id}/assign")
    public ResultResponse<?> assignRule(@PathVariable Long id, @RequestBody AlertRuleAssignmentRequest request) {
        if (!accessControlService.isAdmin()) {
            return ResultResponse.fail("forbidden");
        }
        AlertRule rule = ruleService.findRule(id).orElse(null);
        if (rule == null) {
            return ResultResponse.fail("rule not found");
        }
        ruleService.assignRule(rule, request.getHost(), request.isEnabled());
        return ResultResponse.success(true);
    }

    @PostMapping("/requests")
    public ResultResponse<?> createRequest(@RequestBody AlertRuleRequestCreate request) {
        AuthSession session = AuthContext.get();
        try {
            requestService.createRequest(
                    request.getRuleId(),
                    request.getHost(),
                    request.getAction(),
                    request.getName(),
                    request.getMetric(),
                    request.getThreshold(),
                    request.getSeverity(),
                    session
            );
            return ResultResponse.success(true);
        } catch (IllegalArgumentException ex) {
            return ResultResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/requests")
    public ResultResponse<?> listRequests(@RequestParam(value = "status", required = false) String status,
                                          @RequestParam(value = "category", required = false) String category) {
        AuthSession session = AuthContext.get();
        boolean admin = accessControlService.isAdmin();
        return ResultResponse.success(requestService.listRequests(status, category, session, admin));
    }

    @PostMapping("/requests/{id}/review")
    public ResultResponse<?> reviewRequest(@PathVariable Long id, @RequestBody AlertRuleRequestReview review) {
        if (!accessControlService.isAdmin()) {
            return ResultResponse.fail("forbidden");
        }
        AuthSession session = AuthContext.get();
        requestService.reviewRequest(id, review.isApproved(), review.getComment(), session);
        return ResultResponse.success(true);
    }
}
