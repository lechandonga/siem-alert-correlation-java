package com.github.lechandonga.siemalertcorrelationjava.engine;

import java.util.List;

/**
 * 一次检测重算对"新入库事件相关命中"造成的告警变化，用于上报接口的可解释回执。
 */
public record DetectionSummary(List<String> createdAlertIds,
                               List<String> mergedAlertIds,
                               List<String> reopenedAlertIds,
                               List<String> suppressedByAlertIds) {
    public static DetectionSummary empty() {
        return new DetectionSummary(List.of(), List.of(), List.of(), List.of());
    }
}
