package com.privatecloud.controller;

import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.AccessControlService;
import com.privatecloud.service.HostService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hosts")
public class HostController {

    private final HostService hostService;
    private final AccessControlService accessControlService;

    public HostController(HostService hostService, AccessControlService accessControlService) {
        this.hostService = hostService;
        this.accessControlService = accessControlService;
    }

    @GetMapping
    public ResultResponse<?> list() {
        return ResultResponse.success(hostService.getHosts());
    }

    @GetMapping("/topology")
    public ResultResponse<?> topology(@RequestParam(value = "start", required = false) String start,
                                      @RequestParam(value = "end", required = false) String end) {
        var nodes = hostService.getTopology(start, end);
        if (accessControlService.isAdmin()) {
            return ResultResponse.success(nodes);
        }
        var filtered = nodes.stream()
                .filter(node -> accessControlService.isHostAllowed(node.getHost())
                        || (node.getQueryHost() != null && accessControlService.isHostAllowed(node.getQueryHost())))
                .toList();
        return ResultResponse.success(filtered);
    }
}
