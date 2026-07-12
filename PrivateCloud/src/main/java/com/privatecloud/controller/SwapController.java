package com.privatecloud.controller;

import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.AccessControlService;
import com.privatecloud.service.SwapService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/swap")
public class SwapController {

    private final SwapService swapService;
    private final AccessControlService accessControlService;

    public SwapController(SwapService swapService, AccessControlService accessControlService) {
        this.swapService = swapService;
        this.accessControlService = accessControlService;
    }

    @GetMapping("/history/{host}")
    public ResultResponse<?> getSwapHistory(@PathVariable String host,
                                            @RequestParam(value = "start", required = false, defaultValue = "-1h") String start,
                                            @RequestParam(value = "end", required = false) String end,
                                            @RequestParam(value = "window", required = false, defaultValue = "1m") String window,
                                            @RequestParam(value = "role", required = false) String role,
                                            @RequestParam(value = "field", required = false) String field,
                                            @RequestParam(value = "name", required = false) String name) {
        if (!accessControlService.isHostAllowed(host)) {
            return ResultResponse.fail("forbidden");
        }
        return ResultResponse.success(
                swapService.getSwapHistory(host, start, end, window, role, field, name)
        );
    }
}
