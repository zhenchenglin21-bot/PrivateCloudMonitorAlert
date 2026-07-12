package com.privatecloud.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AlertNotificationScheduler {

    private final AlertNotificationService alertNotificationService;

    public AlertNotificationScheduler(AlertNotificationService alertNotificationService) {
        this.alertNotificationService = alertNotificationService;
    }

    @Scheduled(fixedDelayString = "${alert.notification.scheduler-delay-ms:60000}")
    public void run() {
        alertNotificationService.runScheduledDigest();
        alertNotificationService.runScheduledFeedbackIngestion();
    }
}
