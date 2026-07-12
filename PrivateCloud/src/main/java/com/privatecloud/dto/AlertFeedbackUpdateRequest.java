package com.privatecloud.dto;

public class AlertFeedbackUpdateRequest {
    private String feedbackStatus;
    private String comment;

    public AlertFeedbackUpdateRequest() {
    }

    public String getFeedbackStatus() {
        return feedbackStatus;
    }

    public void setFeedbackStatus(String feedbackStatus) {
        this.feedbackStatus = feedbackStatus;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
