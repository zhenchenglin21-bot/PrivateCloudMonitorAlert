package com.privatecloud.controller;

import com.privatecloud.dto.NotificationSettingsRequest;
import com.privatecloud.dto.ResultResponse;
import com.privatecloud.service.AlertNotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/notification")
public class NotificationSettingsController {

    private final AlertNotificationService alertNotificationService;

    public NotificationSettingsController(AlertNotificationService alertNotificationService) {
        this.alertNotificationService = alertNotificationService;
    }

    @GetMapping
    public ResultResponse<?> getSettings() {
        return ResultResponse.success(alertNotificationService.getSettingsView());
    }

    @PostMapping
    public ResultResponse<?> updateSettings(@RequestBody NotificationSettingsRequest request) {
        try {
            return ResultResponse.success(alertNotificationService.updateSettings(request));
        } catch (IllegalArgumentException ex) {
            return ResultResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/send-now")
    public ResultResponse<?> sendNow() {
        boolean sent = alertNotificationService.sendDigestNow();
        return ResultResponse.success(sent);
    }

    @PostMapping("/poll-feedback-now")
    public ResultResponse<?> pollFeedbackNow() {
        try {
            int processed = alertNotificationService.ingestFeedbackNow();
            return ResultResponse.success(processed);
        } catch (IllegalArgumentException ex) {
            return ResultResponse.fail(ex.getMessage());
        }
    }
}
