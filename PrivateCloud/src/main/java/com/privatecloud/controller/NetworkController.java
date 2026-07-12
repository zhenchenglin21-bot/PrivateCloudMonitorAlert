package com.privatecloud.controller;

import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.AccessControlService;
import com.privatecloud.service.NetworkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/network")
public class NetworkController {

    private final NetworkService networkService;
    private final AccessControlService accessControlService;

    public NetworkController(NetworkService networkService, AccessControlService accessControlService) {
        this.networkService = networkService;
        this.accessControlService = accessControlService;
    }

    @GetMapping("/{host}")
    public ResultResponse<?> getNetwork(@PathVariable String host,
                                        @RequestParam(value = "field", required = false) String field,
                                        @RequestParam(value = "start", required = false, defaultValue = "-1h") String start,
                                        @RequestParam(value = "role", required = false) String role) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(networkService.getNetwork(host, field, start, role));
    }

    @GetMapping("/history/{host}")
    public ResultResponse<?> getNetworkHistory(@PathVariable String host,
                                               @RequestParam(value = "field", required = false) String field,
                                               @RequestParam(value = "start", required = false, defaultValue = "-1h") String start,
                                               @RequestParam(value = "end", required = false) String end,
                                               @RequestParam(value = "window", required = false, defaultValue = "1m") String window,
                                               @RequestParam(value = "role", required = false) String role,
                                               @RequestParam(value = "name", required = false) String name) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(
                networkService.getNetworkHistory(host, field, start, end, window, role, name)
        );
    }

    @GetMapping("/history-smart/{host}")
    public ResultResponse<?> getNetworkHistorySmart(@PathVariable String host,
                                                    @RequestParam(value = "direction", required = false, defaultValue = "in") String direction,
                                                    @RequestParam(value = "start", required = false, defaultValue = "-1h") String start,
                                                    @RequestParam(value = "end", required = false) String end,
                                                    @RequestParam(value = "window", required = false, defaultValue = "1m") String window,
                                                    @RequestParam(value = "role", required = false) String role,
                                                    @RequestParam(value = "name", required = false) String name) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(
                networkService.getNetworkHistoryByDirection(host, direction, start, end, window, role, name)
        );
    }
}
