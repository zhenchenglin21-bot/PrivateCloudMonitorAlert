package com.privatecloud.controller;

import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.AccessControlService;
import com.privatecloud.service.CpuService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cpu")
public class CpuController {

    private final CpuService cpuService;
    private final AccessControlService accessControlService;

    public CpuController(CpuService cpuService, AccessControlService accessControlService) {
        this.cpuService = cpuService;
        this.accessControlService = accessControlService;
    }

    @GetMapping("/usage/{host}")
    public ResultResponse<?> getCpuUsage(@PathVariable String host,
                                         @RequestParam(value = "start", required = false) String start,
                                         @RequestParam(value = "role", required = false) String role) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(
                cpuService.getCpuUsage(host, start, role)
        );
    }

    @GetMapping("/history/{host}")
    public ResultResponse<?> getCpuHistory(@PathVariable String host,
                                           @RequestParam(value = "start", required = false) String start,
                                           @RequestParam(value = "end", required = false) String end,
                                           @RequestParam(value = "window", required = false) String window,
                                           @RequestParam(value = "role", required = false) String role,
                                           @RequestParam(value = "field", required = false) String field,
                                           @RequestParam(value = "name", required = false) String name) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(
                cpuService.getCpuHistory(host, start, end, window, role, field, name)
        );
    }

    @GetMapping("/cores/{host}")
    public ResultResponse<?> getCpuCores(@PathVariable String host,
                                         @RequestParam(value = "start", required = false) String start,
                                         @RequestParam(value = "end", required = false) String end,
                                         @RequestParam(value = "role", required = false) String role,
                                         @RequestParam(value = "name", required = false) String name) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(
                cpuService.getCpuCoreUsage(host, start, end, role, name)
        );
    }
}
