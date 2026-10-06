package com.github.lechandonga.siemalertcorrelationjava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Alert {
    private final String alertId;
    private final String ruleId;
    private final String groupKey;
    private AlertStatus status = AlertStatus.NEW;
    private long version = 1;
    private int hitCount = 1;
    private int suppressedCount = 0;
    private Instant firstHitAt;
    private Instant lastHitAt;
    private String detail;
    private final List<AlertHistoryEntry> history = new ArrayList<>();

    public Alert(String alertId, String ruleId, String groupKey, Instant firstHitAt, String detail) {
        this.alertId = alertId;
        this.ruleId = ruleId;
        this.groupKey = groupKey;
        this.firstHitAt = firstHitAt;
        this.lastHitAt = firstHitAt;
        this.detail = detail;
        this.history.add(new AlertHistoryEntry(firstHitAt, "CREATED", detail));
    }

    public String getAlertId() {
        return alertId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public void setStatus(AlertStatus status) {
        this.status = status;
    }

    public long getVersion() {
        return version;
    }

    public void incrementVersion() {
        this.version++;
    }

    public int getHitCount() {
        return hitCount;
    }

    public void incrementHitCount() {
        this.hitCount++;
    }

    public int getSuppressedCount() {
        return suppressedCount;
    }

    public void incrementSuppressedCount() {
        this.suppressedCount++;
    }

    public Instant getFirstHitAt() {
        return firstHitAt;
    }

    public Instant getLastHitAt() {
        return lastHitAt;
    }

    public void setLastHitAt(Instant lastHitAt) {
        this.lastHitAt = lastHitAt;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public List<AlertHistoryEntry> getHistory() {
        return history;
    }

    public void addHistory(Instant at, String action, String reason) {
        this.history.add(new AlertHistoryEntry(at, action, reason));
    }
}
