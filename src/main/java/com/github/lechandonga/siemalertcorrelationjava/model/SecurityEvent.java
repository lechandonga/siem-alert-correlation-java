package com.github.lechandonga.siemalertcorrelationjava.model;

import java.time.Instant;
import java.util.Map;

/**
 * 归一化后的安全事件。eventId 为外部上报方提供的全局唯一标识，用于幂等去重。
 */
public record SecurityEvent(
        String eventId,
        String eventType,
        String sourceIp,
        String target,
        String username,
        String rawType,
        Instant eventTime,
        Instant receivedAt,
        Map<String, String> attributes
) {
}
