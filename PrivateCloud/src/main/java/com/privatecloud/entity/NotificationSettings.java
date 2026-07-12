package com.privatecloud.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "notification_settings")
public class NotificationSettings {

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean emailEnabled = false;

    @Column(length = 254)
    private String recipientEmail;

    @Column(length = 128)
    private String smtpHost;

    @Column(nullable = false)
    private Integer smtpPort = 587;

    @Column(nullable = false)
    private Boolean smtpAuth = true;

    @Column(nullable = false)
    private Boolean smtpStarttlsEnable = true;

    @Column(nullable = false)
    private Boolean smtpSslEnable = false;

    @Column(length = 254)
    private String smtpUsername;

    @Column(length = 1024)
    private String smtpPasswordEncrypted;

    @Column(length = 254)
    private String senderEmail;

    @Column(length = 64)
    private String senderName;

    @Column(nullable = false)
    private Integer intervalMinutes = 10;

    private Instant lastSentAt;

    private Instant lastAttemptAt;

    @Column(length = 24)
    private String lastSendStatus;

    @Column(length = 500)
    private String lastSendError;

    public NotificationSettings() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public void setEmailEnabled(boolean emailEnabled) {
        this.emailEnabled = emailEnabled;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public void setRecipientEmail(String recipientEmail) {
        this.recipientEmail = recipientEmail;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public void setSmtpHost(String smtpHost) {
        this.smtpHost = smtpHost;
    }

    public Integer getSmtpPort() {
        return smtpPort;
    }

    public void setSmtpPort(Integer smtpPort) {
        this.smtpPort = smtpPort;
    }

    public Boolean getSmtpAuth() {
        return smtpAuth;
    }

    public void setSmtpAuth(Boolean smtpAuth) {
        this.smtpAuth = smtpAuth;
    }

    public Boolean getSmtpStarttlsEnable() {
        return smtpStarttlsEnable;
    }

    public void setSmtpStarttlsEnable(Boolean smtpStarttlsEnable) {
        this.smtpStarttlsEnable = smtpStarttlsEnable;
    }

    public Boolean getSmtpSslEnable() {
        return smtpSslEnable;
    }

    public void setSmtpSslEnable(Boolean smtpSslEnable) {
        this.smtpSslEnable = smtpSslEnable;
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public void setSmtpUsername(String smtpUsername) {
        this.smtpUsername = smtpUsername;
    }

    public String getSmtpPasswordEncrypted() {
        return smtpPasswordEncrypted;
    }

    public void setSmtpPasswordEncrypted(String smtpPasswordEncrypted) {
        this.smtpPasswordEncrypted = smtpPasswordEncrypted;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public void setSenderEmail(String senderEmail) {
        this.senderEmail = senderEmail;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public Integer getIntervalMinutes() {
        return intervalMinutes;
    }

    public void setIntervalMinutes(Integer intervalMinutes) {
        this.intervalMinutes = intervalMinutes;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(Instant lastSentAt) {
        this.lastSentAt = lastSentAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public String getLastSendStatus() {
        return lastSendStatus;
    }

    public void setLastSendStatus(String lastSendStatus) {
        this.lastSendStatus = lastSendStatus;
    }

    public String getLastSendError() {
        return lastSendError;
    }

    public void setLastSendError(String lastSendError) {
        this.lastSendError = lastSendError;
    }
}
