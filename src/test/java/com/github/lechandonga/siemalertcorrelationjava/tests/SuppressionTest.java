package com.github.lechandonga.siemalertcorrelationjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：告警抑制与合并 —— 抑制窗口内的同类命中合并进同一条告警，
 * 合并/抑制原因写在告警 occurrences 与 timeline 中，可解释。
 */
@ExtendWith(BusinessLogExtension.class)
class SuppressionTest extends AbstractApiTest {

    private Map<String, Object> failure(String id, String ip, long t) {
        return Map.of("eventId", id, "eventType", "LOGIN_FAILURE", "sourceIp", ip, "timestamp", ts(t));
    }

    @Test
    @DisplayName("抑制窗口内连续命中合并为一条并给出 SUPPRESSED 说明；窗口外记为新命中块(仍同一条)")
    void suppressWithinWindowAndExplain() throws Exception {
        // 抑制窗口 600s：第 1 批 3 条命中；第 2 批 3 条在抑制窗口内 -> 合并；
        // 第 3 批距离上一命中块超过 600s -> 新命中块，但是同一规则同一分组，仍只有一条告警
        post("/api/events/batch", Map.of("events", List.of(
                failure("p1", "10.3.0.1", 1700000000),
                failure("p2", "10.3.0.1", 1700000020),
                failure("p3", "10.3.0.1", 1700000040))));

        post("/api/events/batch", Map.of("events", List.of(
                failure("p4", "10.3.0.1", 1700000500),
                failure("p5", "10.3.0.1", 1700000520),
                failure("p6", "10.3.0.1", 1700000540))));

        JsonNode alertsAfterSuppressed = get("/api/alerts");
        assertEquals(1, alertsAfterSuppressed.size());
        JsonNode alert = alertsAfterSuppressed.get(0);
        JsonNode refs = alert.get("occurrences");
        assertEquals(2, refs.size());
        assertTrue(refs.get(1).get("merged").asText().startsWith("SUPPRESSED"),
                "第二次命中应带 SUPPRESSED 可解释说明");

        post("/api/events/batch", Map.of("events", List.of(
                failure("p7", "10.3.0.1", 1700001500),
                failure("p8", "10.3.0.1", 1700001520),
                failure("p9", "10.3.0.1", 1700001540))));

        JsonNode finalAlerts = get("/api/alerts");
        assertEquals(1, finalAlerts.size(), "同一规则同一分组永远只有一条告警");
        JsonNode finalRefs = finalAlerts.get(0).get("occurrences");
        assertEquals(3, finalRefs.size());
        assertTrue(finalRefs.get(2).get("merged").asText().startsWith("NEW_BLOCK"),
                "抑制窗口外的命中应标为 NEW_BLOCK");
    }

    @Test
    @DisplayName("不同分组(源IP)的命中不合并，各自一条告警")
    void doNotSuppressAcrossGroups() throws Exception {
        post("/api/events/batch", Map.of("events", List.of(
                failure("g1", "10.3.1.1", 1700002000),
                failure("g2", "10.3.1.1", 1700002020),
                failure("g3", "10.3.1.1", 1700002040),
                failure("h1", "10.3.2.2", 1700002000),
                failure("h2", "10.3.2.2", 1700002020),
                failure("h3", "10.3.2.2", 1700002040))));
        assertEquals(2, get("/api/alerts").size());
    }
}
