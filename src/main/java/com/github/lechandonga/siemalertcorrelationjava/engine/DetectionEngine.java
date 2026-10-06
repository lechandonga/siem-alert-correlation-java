package com.github.lechandonga.siemalertcorrelationjava.engine;

import com.github.lechandonga.siemalertcorrelationjava.model.Alert;
import com.github.lechandonga.siemalertcorrelationjava.model.AlertStatus;
import com.github.lechandonga.siemalertcorrelationjava.model.DetectionRule;
import com.github.lechandonga.siemalertcorrelationjava.model.Occurrence;
import com.github.lechandonga.siemalertcorrelationjava.model.SecurityEvent;
import com.github.lechandonga.siemalertcorrelationjava.model.TimelineEntry;
import com.github.lechandonga.siemalertcorrelationjava.storage.AlertStore;
import com.github.lechandonga.siemalertcorrelationjava.storage.EventStore;
import com.github.lechandonga.siemalertcorrelationjava.storage.RuleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检测引擎。所有检测都基于"全量事件按 (eventTime,eventId) 排序后重算"，
 * 配合确定性的命中指纹与告警 id，使得：
 *   - 乱序/迟到事件不会漏报或重复告警；
 *   - 规则调整后重放同一批事件，告警集合与内容完全一致；
 *   - 已关闭告警再次命中时按 id 找到原告警重开。
 * 单把 ReentrantLock 串行化"入库-检测-状态流转"，保证并发下结论可复现。
 */
@Component
public class DetectionEngine {
    private static final Logger log = LoggerFactory.getLogger(DetectionEngine.class);

    private final EventStore eventStore;
    private final RuleStore ruleStore;
    private final AlertStore alertStore;
    private final RuleMatcher matcher;
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    public DetectionEngine(EventStore eventStore, RuleStore ruleStore, AlertStore alertStore, RuleMatcher matcher) {
        this.eventStore = eventStore;
        this.ruleStore = ruleStore;
        this.alertStore = alertStore;
        this.matcher = matcher;
    }

    public void withLock(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public <T> T withLock(java.util.function.Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 全量重算并与存量告警对账。touchedEventIds 为本次新入库事件，
     * 摘要只统计与这些事件相关的变化，避免把历史变化误算到本次回执上。
     */
    public DetectionSummary recompute(Set<String> touchedEventIds) {
        lock.lock();
        try {
            List<SecurityEvent> events = eventStore.allOrderedByTime();
            List<DetectionRule> rules = ruleStore.all();

            Set<String> beforeIds = alertStore.all().stream().map(a -> a.id).collect(Collectors.toSet());
            Map<String, AlertStatus> beforeStatus = alertStore.all().stream()
                    .collect(Collectors.toMap(a -> a.id, a -> a.status));
            Map<String, Set<String>> beforeFingerprints = alertStore.all().stream()
                    .collect(Collectors.toMap(a -> a.id, a -> new HashSet<>(a.occurrenceFingerprints)));

            Set<String> computedAlertIds = new HashSet<>();
            Set<String> touchedFingerprints = new HashSet<>();

            for (DetectionRule rule : rules) {
                List<Occurrence> occurrences = matcher.match(rule, events);
                occurrences.stream()
                        .filter(o -> o.eventIds().stream().anyMatch(touchedEventIds::contains))
                        .forEach(o -> touchedFingerprints.add(o.fingerprint()));
                reconcileRule(rule, occurrences, computedAlertIds);
            }

            List<String> removed = new ArrayList<>();
            for (String id : new ArrayList<>(beforeIds)) {
                if (!computedAlertIds.contains(id)) {
                    Alert gone = alertStore.get(id);
                    removed.add(id);
                    alertStore.remove(id);
                    log.info("[业务:抑制合并/重放] 规则重算后告警 {} 不再被任何规则命中，已移除", idOf(gone));
                }
            }
            alertStore.persist();

            return buildSummary(touchedFingerprints, beforeIds, beforeStatus, beforeFingerprints, computedAlertIds);
        } finally {
            lock.unlock();
        }
    }

    private String idOf(Alert alert) {
        return alert == null ? "?" : alert.ruleId + "/" + alert.dedupKey;
    }

    private void reconcileRule(DetectionRule rule, List<Occurrence> allOccurrences, Set<String> computedAlertIds) {
        Map<String, List<Occurrence>> byKey = allOccurrences.stream()
                .collect(Collectors.groupingBy(Occurrence::dedupKey));
        for (Map.Entry<String, List<Occurrence>> entry : byKey.entrySet()) {
            String dedupKey = entry.getKey();
            List<Occurrence> occurrences = entry.getValue();
            occurrences.sort(Comparator.comparing(Occurrence::lastEventTime).thenComparing(Occurrence::fingerprint));

            String alertId = RuleMatcher.sha8(rule.ruleId() + "|" + dedupKey);
            computedAlertIds.add(alertId);

            List<Alert.OccurrenceRef> refs = new ArrayList<>();
            List<String> fingerprints = new ArrayList<>();
            int blockCount = 0;
            int suppressedCount = 0;
            Occurrence lastAccepted = null;

            for (Occurrence occurrence : occurrences) {
                fingerprints.add(occurrence.fingerprint());
                boolean suppress = lastAccepted != null
                        && Duration.between(lastAccepted.lastEventTime(), occurrence.lastEventTime())
                        .getSeconds() <= rule.suppressSeconds()
                        // 同一次命中里的事件本就重叠，必须以指纹判重而不是只看时间
                        && !fingerprints.subList(0, fingerprints.size() - 1).contains(occurrence.fingerprint());
                if (suppress) {
                    long gap = Duration.between(lastAccepted.lastEventTime(), occurrence.lastEventTime()).getSeconds();
                    refs.add(new Alert.OccurrenceRef(occurrence.fingerprint(), String.join(",", occurrence.eventIds()),
                            occurrence.lastEventTime(),
                            "SUPPRESSED:距上一命中块 " + gap + "s ≤ 抑制窗口 " + rule.suppressSeconds()
                                    + "s，合并进当前告警不另开"));
                    suppressedCount++;
                } else {
                    if (lastAccepted != null) {
                        long gap = Duration.between(lastAccepted.lastEventTime(), occurrence.lastEventTime()).getSeconds();
                        refs.add(new Alert.OccurrenceRef(occurrence.fingerprint(), String.join(",", occurrence.eventIds()),
                                occurrence.lastEventTime(),
                                "NEW_BLOCK:距上一命中块 " + gap + "s > 抑制窗口 " + rule.suppressSeconds()
                                        + "s，作为新命中块计入同一条告警(不另开)"));
                    } else {
                        refs.add(new Alert.OccurrenceRef(occurrence.fingerprint(), String.join(",", occurrence.eventIds()),
                                occurrence.lastEventTime(), "FIRST_BLOCK:首次命中"));
                    }
                    lastAccepted = occurrence;
                    blockCount++;
                }
            }
            Instant firstOccurrenceAt = occurrences.get(0).firstEventTime();
            Instant lastOccurrenceAt = occurrences.get(occurrences.size() - 1).lastEventTime();

            Alert existing = alertStore.get(alertId);
            boolean fingerprintsChanged = existing == null
                    || !new HashSet<>(existing.occurrenceFingerprints).equals(new HashSet<>(fingerprints));
            if (existing == null) {
                Alert alert = new Alert();
                alert.id = alertId;
                alert.ruleId = rule.ruleId();
                alert.ruleVersion = rule.version();
                alert.dedupKey = dedupKey;
                alert.severity = rule.severity();
                alert.status = AlertStatus.OPEN;
                alert.version = 0;
                alert.createdAt = Instant.now();
                alert.updatedAt = alert.createdAt;
                alert.firstOccurrenceAt = firstOccurrenceAt;
                alert.lastOccurrenceAt = lastOccurrenceAt;
                alert.occurrenceFingerprints = fingerprints;
                alert.occurrences = refs;
                alert.timeline.add(new TimelineEntry(alert.createdAt, "CREATED",
                        "规则 " + rule.ruleId() + " 首次命中：命中块 " + blockCount + " 个，抑制合并 "
                                + suppressedCount + " 次；证据事件=" + evidenceOf(occurrences), "system"));
                alertStore.put(alert);
                log.info("[业务:告警生命周期] 新建告警 {} 规则={} 命中块={} 抑制={}",
                        alertId, rule.ruleId(), blockCount, suppressedCount);
            } else {
                Set<String> oldFingerprints = new HashSet<>(existing.occurrenceFingerprints);
                existing.ruleVersion = rule.version();
                existing.severity = rule.severity();
                existing.occurrences = refs;
                existing.occurrenceFingerprints = fingerprints;
                existing.firstOccurrenceAt = firstOccurrenceAt;
                existing.lastOccurrenceAt = lastOccurrenceAt;

                long newFingerprintCount = fingerprints.stream().filter(f -> !oldFingerprints.contains(f)).count();
                boolean newAcceptedBlock = refs.stream().anyMatch(ref ->
                        !oldFingerprints.contains(ref.fingerprint())
                                && (ref.merged().startsWith("NEW_BLOCK") || ref.merged().startsWith("FIRST_BLOCK")));
                if (existing.status == AlertStatus.CLOSED && newAcceptedBlock) {
                    existing.status = AlertStatus.OPEN;
                    existing.closedAt = null;
                    existing.updatedAt = Instant.now();
                    existing.timeline.add(new TimelineEntry(existing.updatedAt, "REOPENED_BY_MATCH",
                            "已关闭告警在抑制窗口外再次命中，按规则重开原告警(不另开)；新指纹数="
                                    + newFingerprintCount, "system"));
                    log.info("[业务:告警生命周期] 告警 {} 因再次命中由 CLOSED 重开为 OPEN", alertId);
                } else if (fingerprintsChanged) {
                    existing.updatedAt = Instant.now();
                    existing.timeline.add(new TimelineEntry(existing.updatedAt, "REPLAY_RECONCILED",
                            "规则/事件重放后对账更新：命中块 " + blockCount + " 个，抑制合并 "
                                    + suppressedCount + " 次，保留既有处理状态", "system"));
                    log.info("[业务:乱序/重放] 告警 {} 按重放结果对账(状态保持 {})", alertId, existing.status);
                }
            }
        }
    }

    private String evidenceOf(List<Occurrence> occurrences) {
        return occurrences.stream().map(o -> "[" + String.join(",", o.eventIds()) + "]")
                .collect(Collectors.joining(","));
    }

    private DetectionSummary buildSummary(Set<String> touchedFingerprints,
                                          Set<String> beforeIds,
                                          Map<String, AlertStatus> beforeStatus,
                                          Map<String, Set<String>> beforeFingerprints,
                                          Set<String> computedAlertIds) {
        List<String> created = new ArrayList<>();
        List<String> merged = new ArrayList<>();
        List<String> reopened = new ArrayList<>();
        List<String> suppressed = new ArrayList<>();

        for (Alert alert : alertStore.all()) {
            boolean touches = alert.occurrenceFingerprints.stream().anyMatch(touchedFingerprints::contains);
            if (!touches && !(!beforeIds.contains(alert.id) && touchedFingerprints.isEmpty())) {
                continue;
            }
            if (!beforeIds.contains(alert.id)) {
                created.add(alert.id);
            } else if (beforeStatus.get(alert.id) == AlertStatus.CLOSED && alert.status != AlertStatus.CLOSED) {
                reopened.add(alert.id);
            }
            Set<String> old = beforeFingerprints.getOrDefault(alert.id, Set.of());
            boolean newSuppressed = alert.occurrences.stream()
                    .anyMatch(ref -> ref.merged() != null && ref.merged().startsWith("SUPPRESSED")
                            && !old.contains(ref.fingerprint()));
            if (newSuppressed) {
                merged.add(alert.id);
                suppressed.add(alert.id + "(抑制窗口内同类命中合并,未另开告警)");
            }
        }
        return new DetectionSummary(created, merged, reopened, suppressed);
    }
}
