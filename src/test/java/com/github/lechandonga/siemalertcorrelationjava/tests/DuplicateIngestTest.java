package com.github.lechandonga.siemalertcorrelationjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 业务：重复上报 —— 同一 eventId 重复（跨请求/同批次）不得重复计数或重复触发告警。
 */
@ExtendWith(BusinessLogExtension.class)
class DuplicateIngestTest extends AbstractApiTest {

    private Map<String, Object> failure(String id, String ip, long t) {
        return Map.of("eventId", id, "eventType", "LOGIN_FAILURE", "sourceIp", ip, "timestamp", ts(t));
    }

    @Test
    @DisplayName("跨请求与同批次重复 eventId 都标记 DUPLICATE，不产生第二条告警")
    void duplicatesAreIdempotent() throws Exception {
        post("/api/events/batch", Map.of("events", List.of(
                failure("d1", "10.1.0.1", 1700000000),
                failure("d1", "10.1.0.1", 1700000000),
                failure("d2", "10.1.0.1", 1700000010),
                failure("d3", "10.1.0.1", 1700000020))));

        JsonNode second = post("/api/events/batch", Map.of("events", List.of(
                failure("d1", "10.1.0.1", 1700000000),
                failure("d2", "10.1.0.1", 1700000010))));
        assertEquals(0, second.get("accepted").asInt());
        assertEquals(2, second.get("duplicate").asInt());

        JsonNode alerts = get("/api/alerts");
        assertEquals(1, alerts.size(), "阈值只应命中一次，只有一条告警");
        JsonNode occurrences = alerts.get(0).get("occurrences");
        assertEquals(1, occurrences.size(), "重复事件不得产生第二次命中");
    }
}
