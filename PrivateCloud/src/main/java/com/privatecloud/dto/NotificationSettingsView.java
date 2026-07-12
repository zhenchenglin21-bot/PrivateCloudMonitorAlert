package com.privatecloud.dto;

import java.time.Instant;

public class NotificationSettingsView {
    private boolean emailEnabled;
    private String recipientEmail;
    private String smtpHost;
    private int smtpPort;
    private boolean smtpAuth;
    private boolean smtpStarttlsEnable;
    private boolean smtpSslEnable;
    private String smtpUsername;
    private boolean smtpPasswordSet;
    private String senderEmail;
    private String senderName;
    private int intervalMinutes;
    private Instant lastSentAt;
    private Instant lastAttemptAt;
    private Instant nextSendAt;
    private String lastSendStatus;
    private String lastSendError;

    public NotificationSettingsView(
            boolean emailEnabled,
            String recipientEmail,
            String smtpHost,
            int smtpPort,
            boolean smtpAuth,
            boolean smtpStarttlsEnable,
            boolean smtpSslEnable,
            String smtpUsername,
            boolean smtpPasswordSet,
            String senderEmail,
            String senderName,
            int intervalMinutes,
            Instant lastSentAt,
            Instant lastAttemptAt,
            Instant nextSendAt,
            String lastSendStatus,
            String lastSendError
    ) {
        this.emailEnabled = emailEnabled;
        this.recipientEmail = recipientEmail;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpAuth = smtpAuth;
        this.smtpStarttlsEnable = smtpStarttlsEnable;
        this.smtpSslEnable = smtpSslEnable;
        this.smtpUsername = smtpUsername;
        this.smtpPasswordSet = smtpPasswordSet;
        this.senderEmail = senderEmail;
        this.senderName = senderName;
        this.intervalMinutes = intervalMinutes;
        this.lastSentAt = lastSentAt;
        this.lastAttemptAt = lastAttemptAt;
        this.nextSendAt = nextSendAt;
        this.lastSendStatus = lastSendStatus;
        this.lastSendError = lastSendError;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public boolean isSmtpAuth() {
        return smtpAuth;
    }

    public boolean isSmtpStarttlsEnable() {
        return smtpStarttlsEnable;
    }

    public boolean isSmtpSslEnable() {
        return smtpSslEnable;
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public boolean isSmtpPasswordSet() {
        return smtpPasswordSet;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public String getSenderName() {
        return senderName;
    }

    public int getIntervalMinutes() {
        return intervalMinutes;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getNextSendAt() {
        return nextSendAt;
    }

    public String getLastSendStatus() {
        return lastSendStatus;
    }

    public String getLastSendError() {
        return lastSendError;
    }
}
