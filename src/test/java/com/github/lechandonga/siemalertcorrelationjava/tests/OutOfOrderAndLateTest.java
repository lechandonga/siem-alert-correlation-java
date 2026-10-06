package com.github.lechandonga.siemalertcorrelationjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 业务：乱序与迟到事件 —— 阈值与序列规则按事件时间重算，
 * 迟到事件既不漏报也不重复告警。
 */
@ExtendWith(BusinessLogExtension.class)
class OutOfOrderAndLateTest extends AbstractApiTest {

    private Map<String, Object> event(String id, String type, String ip, long t) {
        return Map.of("eventId", id, "eventType", type, "sourceIp", ip, "timestamp", ts(t));
    }

    @Test
    @DisplayName("阈值规则：第3条失败迟到到达，补齐后只产生一条告警")
    void lateEventCompletesThresholdWithoutDuplicate() throws Exception {
        JsonNode first = post("/api/events/batch", Map.of("events", List.of(
                event("o1", "LOGIN_FAILURE", "10.2.0.1", 1700000100),
                event("o2", "LOGIN_FAILURE", "10.2.0.1", 1700000000))));
        assertEquals(0, get("/api/alerts").size(), "只有2条失败，不应有告警");
        assertEquals(0, first.get("errors").size());

        JsonNode late = post("/api/events", event("o3", "LOGIN_FAILURE", "10.2.0.1", 1700000050));
        assertEquals(1, late.get("acceptedEvents").get(0).get("detection").get("createdAlertIds").size(),
                "迟到的第3条事件补齐阈值，应新建告警");

        JsonNode again = post("/api/events", event("o3", "LOGIN_FAILURE", "10.2.0.1", 1700000050));
        assertEquals(1, again.get("duplicate").asInt());
        assertEquals(1, get("/api/alerts").size(), "重复迟到事件不得再开告警");
    }

    @Test
    @DisplayName("序列规则：先到1次失败+成功(不命中)，第2次失败迟到后按事件时间命中且只命中一次")
    void sequenceDetectedAcrossOutOfOrderDelivery() throws Exception {
        post("/api/events/batch", Map.of("events", List.of(
                event("s4", "LOGIN_SUCCESS", "10.2.0.9", 1700000200),
                event("s1", "LOGIN_FAILURE", "10.2.0.9", 1700000000))));
        long seqBefore = toStream(get("/api/alerts"))
                .filter(a -> "R-FAIL-THEN-SUCCESS".equals(a.get("ruleId").asText()))
                .count();
        assertEquals(0, seqBefore, "只有1次失败，序列规则不应命中");

        post("/api/events", event("s3", "LOGIN_FAILURE", "10.2.0.9", 1700000120));

        JsonNode alerts = get("/api/alerts");
        long seqAlerts = toStream(alerts)
                .filter(a -> "R-FAIL-THEN-SUCCESS".equals(a.get("ruleId").asText()))
                .count();
        assertEquals(1, seqAlerts, "乱序补齐后序列规则应恰好命中一条告警");

        post("/api/events", event("s3", "LOGIN_FAILURE", "10.2.0.9", 1700000120));
        assertEquals(1, toStream(get("/api/alerts"))
                .filter(a -> "R-FAIL-THEN-SUCCESS".equals(a.get("ruleId").asText())).count());
    }

    private java.util.stream.Stream<JsonNode> toStream(JsonNode array) {
        java.util.List<JsonNode> list = new java.util.ArrayList<>();
        array.forEach(list::add);
        return list.stream();
    }
}
