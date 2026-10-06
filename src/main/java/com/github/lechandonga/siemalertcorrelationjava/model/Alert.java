package com.github.lechandonga.siemalertcorrelationjava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 告警。id 由 ruleId + dedupKey 确定性派生：同一规则同一分组永远对应同一条告警，
 * 因此"已关闭告警再次命中"只会重开同一条，而不会另开一条。
 * version 为乐观锁版本，并发更新冲突时拒绝后写入方。
 */
public class Alert {
    public String id;
    public String ruleId;
    public int ruleVersion;
    public String dedupKey;
    public Severity severity;
    public AlertStatus status;
    public long version;

    public Instant createdAt;
    public Instant updatedAt;
    public Instant firstOccurrenceAt;
    public Instant lastOccurrenceAt;
    public Instant closedAt;

    public List<String> occurrenceFingerprints = new ArrayList<>();
    public List<OccurrenceRef> occurrences = new ArrayList<>();
    public List<TimelineEntry> timeline = new ArrayList<>();

    public record OccurrenceRef(String fingerprint, String eventIds, Instant at, String merged) {
    }
}
