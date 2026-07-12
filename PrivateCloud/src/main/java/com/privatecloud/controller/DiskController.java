package com.privatecloud.controller;

import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.AccessControlService;
import com.privatecloud.service.DiskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/disk")
public class DiskController {

    private final DiskService diskService;
    private final AccessControlService accessControlService;

    public DiskController(DiskService diskService, AccessControlService accessControlService) {
        this.diskService = diskService;
        this.accessControlService = accessControlService;
    }

    @GetMapping("/{host}")
    public ResultResponse<?> getDisk(@PathVariable String host,
                                     @RequestParam(value = "start", required = false, defaultValue = "-1h") String start,
                                     @RequestParam(value = "role", required = false) String role) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(diskService.getDiskUsage(host, start, role));
    }

    @GetMapping("/history/{host}")
    public ResultResponse<?> getDiskHistory(@PathVariable String host,
                                            @RequestParam(value = "start", required = false, defaultValue = "-1h") String start,
                                            @RequestParam(value = "end", required = false) String end,
                                            @RequestParam(value = "window", required = false, defaultValue = "1m") String window,
                                            @RequestParam(value = "role", required = false) String role,
                                            @RequestParam(value = "field", required = false) String field,
                                            @RequestParam(value = "name", required = false) String name) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(diskService.getDiskHistory(host, start, end, window, role, field, name));
    }
}
