package com.privatecloud.controller;

import com.privatecloud.dto.MonitorTargetChangeView;
import com.privatecloud.dto.MonitorTargetRuntimeView;
import com.privatecloud.dto.MonitorTargetToggleRequest;
import com.privatecloud.dto.MonitorTargetView;
import com.privatecloud.dto.ResultResponse;
import com.privatecloud.security.AuthContext;
import com.privatecloud.security.AuthSession;
import com.privatecloud.service.AccessControlService;
import com.privatecloud.service.MonitorTargetService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/monitor-targets")
public class MonitorTargetController {

    private final MonitorTargetService monitorTargetService;
    private final AccessControlService accessControlService;
    private final String ingestKey;

    public MonitorTargetController(MonitorTargetService monitorTargetService,
                                   AccessControlService accessControlService,
                                   @Value("${alert.ingest-key:}") String ingestKey) {
        this.monitorTargetService = monitorTargetService;
        this.accessControlService = accessControlService;
        this.ingestKey = ingestKey == null ? "" : ingestKey;
    }

    @GetMapping
    public ResultResponse<?> listTargets(@RequestParam(value = "start", required = false) String start,
                                         @RequestParam(value = "end", required = false) String end) {
        AuthSession session = AuthContext.get();
        List<MonitorTargetView> targets = monitorTargetService.listTargets(start, end, session);
        return ResultResponse.success(targets);
    }

    @GetMapping("/runtime")
    public ResultResponse<?> listRuntimeTargets(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                                @RequestParam(value = "start", required = false) String start,
                                                @RequestParam(value = "end", required = false) String end) {
        if (ingestKey.isBlank() || apiKey == null || !ingestKey.equals(apiKey)) {
            return ResultResponse.fail("forbidden");
        }
        List<MonitorTargetRuntimeView> targets = monitorTargetService.listRuntimeTargets(start, end);
        return ResultResponse.success(targets);
    }

    @PostMapping("/toggle")
    public ResultResponse<?> toggleTarget(@RequestBody MonitorTargetToggleRequest request) {
        AuthSession session = AuthContext.get();
        if (!accessControlService.isAdmin()) {
            String host = request.getHost();
            if (host == null || host.isBlank() || !accessControlService.isHostAllowed(host)) {
                return ResultResponse.fail("forbidden");
            }
        }
        monitorTargetService.toggleTarget(request.getHost(), request.getRole(), request.isEnabled(), session);
        return ResultResponse.success(true);
    }

    @GetMapping("/changes")
    public ResultResponse<?> listChanges(@RequestParam(value = "host", required = false) String host,
                                         @RequestParam(value = "role", required = false) String role) {
        AuthSession session = AuthContext.get();
        List<MonitorTargetChangeView> changes = monitorTargetService.listChanges(host, role, session);
        return ResultResponse.success(changes);
    }
}
