package com.privatecloud.controller;

import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.HealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/influx")
    public ResultResponse<?> influx() {
        boolean ok = healthService.ping();
        return ok ? ResultResponse.success(true) : ResultResponse.fail("influxdb not reachable or query failed");
    }
}
