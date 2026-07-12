package com.privatecloud.controller;

import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.AccessControlService;
import com.privatecloud.service.MemoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memoryService;
    private final AccessControlService accessControlService;

    public MemoryController(MemoryService memoryService, AccessControlService accessControlService) {
        this.memoryService = memoryService;
        this.accessControlService = accessControlService;
    }

    @GetMapping("/usage/{host}")
    public ResultResponse<?> getMemoryUsage(@PathVariable String host,
                                            @RequestParam(value = "start", required = false) String start,
                                            @RequestParam(value = "role", required = false) String role) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(
                memoryService.getMemoryUsage(host, start, role)
        );
    }

    @GetMapping("/history/{host}")
    public ResultResponse<?> getMemoryHistory(@PathVariable String host,
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
                memoryService.getMemoryHistory(host, start, end, window, role, field, name)
        );
    }
}
