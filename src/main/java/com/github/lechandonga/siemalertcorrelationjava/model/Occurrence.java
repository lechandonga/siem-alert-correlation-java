package com.github.lechandonga.siemalertcorrelationjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 一次规则命中。fingerprint 由规则 id 与命中事件 id 集合确定性派生，
 * 乱序/重放时同一次命中 fingerprint 不变，保证不重复、可复现。
 */
public record Occurrence(
        String ruleId,
        int ruleVersion,
        String dedupKey,
        String fingerprint,
        List<String> eventIds,
        Instant firstEventTime,
        Instant lastEventTime
) {
}
