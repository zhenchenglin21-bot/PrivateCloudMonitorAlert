package com.privatecloud.controller;

import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.AccessControlService;
import com.privatecloud.service.ContainerMetricService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/container-metrics")
public class ContainerMetricController {

    private final ContainerMetricService containerMetricService;
    private final AccessControlService accessControlService;

    public ContainerMetricController(ContainerMetricService containerMetricService, AccessControlService accessControlService) {
        this.containerMetricService = containerMetricService;
        this.accessControlService = accessControlService;
    }

    @GetMapping("/history/{host}")
    public ResultResponse<?> getHistory(@PathVariable String host,
                                        @RequestParam(value = "start", required = false, defaultValue = "-1h") String start,
                                        @RequestParam(value = "end", required = false) String end,
                                        @RequestParam(value = "window", required = false, defaultValue = "1m") String window,
                                        @RequestParam(value = "role", required = false) String role,
                                        @RequestParam(value = "name", required = false) String name,
                                        @RequestParam(value = "measurement") String measurement,
                                        @RequestParam(value = "field", required = false) String field,
                                        @RequestParam(value = "derivative", required = false, defaultValue = "false") boolean derivative) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(
                containerMetricService.getHistory(host, start, end, window, role, name, measurement, field, derivative)
        );
    }
}
