package com.github.lechandonga.siemalertcorrelationjava.engine;

import com.github.lechandonga.siemalertcorrelationjava.model.Alert;
import com.github.lechandonga.siemalertcorrelationjava.model.AlertStatus;
import com.github.lechandonga.siemalertcorrelationjava.model.TimelineEntry;
import com.github.lechandonga.siemalertcorrelationjava.storage.AlertStore;
import com.github.lechandonga.siemalertcorrelationjava.web.ApiException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 告警生命周期状态机 + 乐观锁并发控制。
 *
 * 合法流转：
 *   OPEN ──ACK──▶ ACKNOWLEDGED
 *   ACKNOWLEDGED ──UNACK──▶ OPEN
 *   OPEN/ACKNOWLEDGED ──CLOSE──▶ CLOSED
 *   CLOSED ──REOPEN(人工)──▶ OPEN   （再次命中由检测引擎自动重开）
 */
@Service
public class AlertLifecycleService {

    private static final Map<AlertStatus, Set<String>> ALLOWED = Map.of(
            AlertStatus.OPEN, Set.of("ACK", "CLOSE"),
            AlertStatus.ACKNOWLEDGED, Set.of("UNACK", "CLOSE"),
            AlertStatus.CLOSED, Set.of("REOPEN"));

    private final AlertStore alertStore;
    private final DetectionEngine engine;

    public AlertLifecycleService(AlertStore alertStore, DetectionEngine engine) {
        this.alertStore = alertStore;
        this.engine = engine;
    }

    public Alert transition(String alertId, String action, String operator, Long expectedVersion) {
        return engine.withLock(() -> {
            Alert alert = alertStore.get(alertId);
            if (alert == null) {
                throw new ApiException(404, "ALERT_NOT_FOUND", "告警不存在: " + alertId);
            }
            if (expectedVersion != null && expectedVersion != alert.version) {
                throw new ApiException(409, "VERSION_CONFLICT",
                        "并发更新冲突：期望版本 " + expectedVersion + "，当前版本 " + alert.version
                                + "，请基于最新状态重试，本次更新被拒绝(状态未丢失)");
            }
            String normalizedAction = action == null ? "" : action.trim().toUpperCase();
            Set<String> allowed = ALLOWED.get(alert.status);
            if (allowed == null || !allowed.contains(normalizedAction)) {
                throw new ApiException(422, "ILLEGAL_TRANSITION",
                        "非法流转：状态 " + alert.status + " 不允许执行 " + normalizedAction
                                + "，允许的操作: " + allowed);
            }

            AlertStatus previous = alert.status;
            switch (normalizedAction) {
                case "ACK" -> alert.status = AlertStatus.ACKNOWLEDGED;
                case "UNACK", "REOPEN" -> alert.status = AlertStatus.OPEN;
                case "CLOSE" -> {
                    alert.status = AlertStatus.CLOSED;
                    alert.closedAt = Instant.now();
                }
                default -> throw new ApiException(422, "ILLEGAL_TRANSITION", "未知操作: " + normalizedAction);
            }
            alert.version++;
            alert.updatedAt = Instant.now();
            alert.timeline.add(new TimelineEntry(alert.updatedAt, normalizedAction,
                    "人工流转: " + previous + " -> " + alert.status,
                    operator == null || operator.isBlank() ? "anonymous" : operator));
            alertStore.persist();
            return alert;
        });
    }
}
