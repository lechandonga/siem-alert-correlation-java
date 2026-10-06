package com.github.lechandonga.siemalertcorrelationjava.model;

public class Rule {
    private String ruleId;
    private RuleType type;
    private String eventType;
    private String groupBy = "sourceIp";
    private int threshold;
    private long windowSeconds;
    private long suppressionSeconds;
    private String failureEventType;
    private String successEventType;
    private boolean enabled = true;

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public RuleType getType() {
        return type;
    }

    public void setType(RuleType type) {
        this.type = type;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getGroupBy() {
        return groupBy;
    }

    public void setGroupBy(String groupBy) {
        this.groupBy = groupBy;
    }

    public int getThreshold() {
        return threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public long getSuppressionSeconds() {
        return suppressionSeconds;
    }

    public void setSuppressionSeconds(long suppressionSeconds) {
        this.suppressionSeconds = suppressionSeconds;
    }

    public String getFailureEventType() {
        return failureEventType;
    }

    public void setFailureEventType(String failureEventType) {
        this.failureEventType = failureEventType;
    }

    public String getSuccessEventType() {
        return successEventType;
    }

    public void setSuccessEventType(String successEventType) {
        this.successEventType = successEventType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
