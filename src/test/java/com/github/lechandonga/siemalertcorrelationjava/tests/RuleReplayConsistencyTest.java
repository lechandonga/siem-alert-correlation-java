package com.github.lechandonga.siemalertcorrelationjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 业务：规则调整与历史重放 —— 规则改参数后重放同一批事件得到一致、可复现的告警结论。
 */
@ExtendWith(BusinessLogExtension.class)
class RuleReplayConsistencyTest extends AbstractApiTest {

    private Map<String, Object> failure(String id, String ip, long t) {
        return Map.of("eventId", id, "eventType", "LOGIN_FAILURE", "sourceIp", ip, "timestamp", ts(t));
    }

    private Map<String, Object> thresholdRule(int threshold, long window) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleId", "R-BRUTE-FORCE");
        rule.put("name", "暴力破解阈值(测试调整)");
        rule.put("type", "THRESHOLD");
        rule.put("enabled", true);
        rule.put("eventTypes", List.of("LOGIN_FAILURE"));
        rule.put("threshold", threshold);
        rule.put("sequence", List.of());
        rule.put("windowSeconds", window);
        rule.put("suppressSeconds", 3600);
        rule.put("groupBy", List.of("sourceIp"));
        rule.put("severity", "HIGH");
        return rule;
    }

    @Test
    @DisplayName("规则阈值从3调到2后重放：结论变化一次；连续重放结论稳定不变")
    void replayProducesStableConclusions() throws Exception {
        post("/api/events/batch", Map.of("events", List.of(
                failure("z1", "10.6.0.1", 1700007000),
                failure("z2", "10.6.0.1", 1700007010),
                failure("z3", "10.6.0.1", 1700007020),
                failure("z4", "10.6.0.1", 1700007030),
                failure("z5", "10.6.0.1", 1700007040))));
        assertEquals(1, get("/api/alerts").size(), "阈值3：3条事件命中1条告警");

        post("/api/rules", thresholdRule(2, 300));
        JsonNode alertsAtThreshold2 = get("/api/alerts");
        assertEquals(1, alertsAtThreshold2.size());
        JsonNode alert = alertsAtThreshold2.get(0);
        int occurrenceBlocks = alert.get("occurrences").size();
        assertEquals(2, occurrenceBlocks, "阈值2：5条事件产生 2 次命中块(第二次落在抑制窗口内)");

        JsonNode replay1 = post("/api/rules/R-BRUTE-FORCE/replay", Map.of());
        JsonNode alertsAfterReplay1 = get("/api/alerts");
        assertEquals(1, alertsAfterReplay1.size());
        assertEquals(occurrenceBlocks, alertsAfterReplay1.get(0).get("occurrences").size());
        assertEquals(alert.get("id").asText(), alertsAfterReplay1.get(0).get("id").asText(),
                "告警 id 确定性派生，重放不得另开");

        post("/api/rules/R-BRUTE-FORCE/replay", Map.of());
        JsonNode alertsAfterReplay2 = get("/api/alerts");
        assertEquals(1, alertsAfterReplay2.size());
        assertEquals(occurrenceBlocks, alertsAfterReplay2.get(0).get("occurrences").size(),
                "再次重放结论必须完全一致");

        post("/api/rules", thresholdRule(10, 300));
        assertEquals(0, get("/api/alerts").size(), "阈值调到5后重放：无命中，旧告警应被确定性移除");
    }
}
