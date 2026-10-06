package com.github.lechandonga.siemalertcorrelationjava.model;

import java.time.Instant;
import java.util.Map;

public record NormalizedEvent(
        String eventId,
        Instant timestamp,
        String sourceIp,
        String eventType,
        String username,
        String severity,
        Map<String, String> attributes) {
}
