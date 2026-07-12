package com.privatecloud.controller;

import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.AccessControlService;
import com.privatecloud.service.ProcessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/process")
public class ProcessController {

    private final ProcessService processService;
    private final AccessControlService accessControlService;

    public ProcessController(ProcessService processService, AccessControlService accessControlService) {
        this.processService = processService;
        this.accessControlService = accessControlService;
    }

    @GetMapping("/top/{host}")
    public ResultResponse<?> topProcess(@PathVariable String host,
                                        @RequestParam(value = "start", required = false) String start,
                                        @RequestParam(value = "end", required = false) String end,
                                        @RequestParam(value = "role", required = false) String role,
                                        @RequestParam(value = "metric", required = false) String metric,
                                        @RequestParam(value = "name", required = false) String name) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(
                processService.getTopProcess(host, start, end, role, metric, name)
        );
    }

    @GetMapping("/toplist/{host}")
    public ResultResponse<?> topList(@PathVariable String host,
                                     @RequestParam(value = "start", required = false) String start,
                                     @RequestParam(value = "end", required = false) String end,
                                     @RequestParam(value = "role", required = false) String role,
                                     @RequestParam(value = "metric", required = false) String metric,
                                     @RequestParam(value = "name", required = false) String name,
                                     @RequestParam(value = "limit", required = false) Integer limit) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        int top = limit == null ? 5 : Math.max(1, limit);
        return ResultResponse.success(
                processService.getTopProcessList(host, start, end, role, name, metric, top)
        );
    }
}
