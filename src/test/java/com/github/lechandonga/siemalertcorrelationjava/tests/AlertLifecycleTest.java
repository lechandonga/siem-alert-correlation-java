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
 * 业务：告警生命周期 —— OPEN/ACKNOWLEDGED/CLOSED 合法流转、非法操作被拒绝，
 * 已关闭告警再次命中时重开原告警而不是另开一条。
 */
@ExtendWith(BusinessLogExtension.class)
class AlertLifecycleTest extends AbstractApiTest {

    private String createOneAlert(String prefix) throws Exception {
        post("/api/events/batch", Map.of("events", List.of(
                Map.of("eventId", prefix + "1", "eventType", "LOGIN_FAILURE",
                        "sourceIp", "10.4.0.1", "timestamp", ts(1700003000)),
                Map.of("eventId", prefix + "2", "eventType", "LOGIN_FAILURE",
                        "sourceIp", "10.4.0.1", "timestamp", ts(1700003020)),
                Map.of("eventId", prefix + "3", "eventType", "LOGIN_FAILURE",
                        "sourceIp", "10.4.0.1", "timestamp", ts(1700003040)))));
        return get("/api/alerts").get(0).get("id").asText();
    }

    private JsonNode transition(String id, String action, Long expectedVersion) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("action", action);
        body.put("operator", "secops");
        if (expectedVersion != null) {
            body.put("expectedVersion", expectedVersion);
        }
        return post("/api/alerts/" + id + "/transition", body);
    }

    @Test
    @DisplayName("合法流转 OPEN->ACK->CLOSE->REOPEN，timeline 全程可追溯")
    void validTransitionsRecordTimeline() throws Exception {
        String id = createOneAlert("v");
        assertEquals("ACKNOWLEDGED", transition(id, "ACK", null).get("status").asText());
        assertEquals("OPEN", transition(id, "UNACK", null).get("status").asText());
        JsonNode closed = transition(id, "CLOSE", null);
        assertEquals("CLOSED", closed.get("status").asText());
        JsonNode reopened = transition(id, "REOPEN", null);
        assertEquals("OPEN", reopened.get("status").asText());
        assertTrue(reopened.get("timeline").size() >= 4);
    }

    @Test
    @DisplayName("非法流转(OPEN 直接 REOPEN / CLOSED 再 CLOSE)被拒绝并给出原因")
    void illegalTransitionsRejected() throws Exception {
        String id = createOneAlert("x");
        JsonNode reject1 = transition(id, "REOPEN", null);
        assertEquals("ILLEGAL_TRANSITION", reject1.get("errorCode").asText());
        transition(id, "CLOSE", null);
        JsonNode reject2 = transition(id, "CLOSE", null);
        assertEquals("ILLEGAL_TRANSITION", reject2.get("errorCode").asText());
        JsonNode reject3 = transition("no-such-alert", "ACK", null);
        assertEquals("ALERT_NOT_FOUND", reject3.get("errorCode").asText());
    }

    @Test
    @DisplayName("已关闭告警再次命中：重开同一条(REOPENED_BY_MATCH)，不另开")
    void closedAlertReopensOnNewMatch() throws Exception {
        String id = createOneAlert("r");
        transition(id, "CLOSE", null);
        assertEquals(0, get("/api/alerts?status=OPEN").size());

        // 距上一命中块 1700003040 已超过抑制窗口 600s，属于新命中块，应触发自动重开
        post("/api/events/batch", Map.of("events", List.of(
                Map.of("eventId", "r4", "eventType", "LOGIN_FAILURE",
                        "sourceIp", "10.4.0.1", "timestamp", ts(1700004000)),
                Map.of("eventId", "r5", "eventType", "LOGIN_FAILURE",
                        "sourceIp", "10.4.0.1", "timestamp", ts(1700004020)),
                Map.of("eventId", "r6", "eventType", "LOGIN_FAILURE",
                        "sourceIp", "10.4.0.1", "timestamp", ts(1700004040)))));

        JsonNode alerts = get("/api/alerts");
        assertEquals(1, alerts.size());
        JsonNode alert = alerts.get(0);
        assertEquals(id, alert.get("id").asText(), "必须是原告警被重开");
        assertEquals("OPEN", alert.get("status").asText());
        assertTrue(alert.get("timeline").toString().contains("REOPENED_BY_MATCH"));
    }
}
