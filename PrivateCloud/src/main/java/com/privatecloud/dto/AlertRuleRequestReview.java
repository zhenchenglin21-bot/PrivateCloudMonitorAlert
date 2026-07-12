package com.privatecloud.dto;

public class AlertRuleRequestReview {
    private boolean approved;
    private String comment;

    public AlertRuleRequestReview() {}

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
