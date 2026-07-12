package com.privatecloud.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "alert_feedback_batch_item",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_feedback_batch_code", columnNames = {"batch_id", "short_code"})
        },
        indexes = {
                @Index(name = "idx_feedback_batch", columnList = "batch_id"),
                @Index(name = "idx_feedback_source_message", columnList = "source_message_id"),
                @Index(name = "idx_feedback_alert", columnList = "alert_id"),
                @Index(name = "idx_feedback_user", columnList = "user_id")
        }
)
public class AlertFeedbackBatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    @Column(name = "short_code", nullable = false, length = 16)
    private String shortCode;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    @Column(name = "source_message_id", length = 255)
    private String sourceMessageId;

    @Column(name = "feedback_status", nullable = false, length = 24)
    private String feedbackStatus = "UNLABELED";

    @Column(name = "feedback_source", length = 24)
    private String feedbackSource;

    @Column(name = "feedback_at")
    private Instant feedbackAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public Long getAlertId() {
        return alertId;
    }

    public void setAlertId(Long alertId) {
        this.alertId = alertId;
    }

    public String getFeedbackStatus() {
        return feedbackStatus;
    }

    public void setFeedbackStatus(String feedbackStatus) {
        this.feedbackStatus = feedbackStatus;
    }

    public String getSourceMessageId() {
        return sourceMessageId;
    }

    public void setSourceMessageId(String sourceMessageId) {
        this.sourceMessageId = sourceMessageId;
    }

    public String getFeedbackSource() {
        return feedbackSource;
    }

    public void setFeedbackSource(String feedbackSource) {
        this.feedbackSource = feedbackSource;
    }

    public Instant getFeedbackAt() {
        return feedbackAt;
    }

    public void setFeedbackAt(Instant feedbackAt) {
        this.feedbackAt = feedbackAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
