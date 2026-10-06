package com.github.lechandonga.siemalertcorrelationjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务：畸形上报 —— 字段缺失、时间戳非法/残缺、非法 IP、非法 JSON，
 * 都必须按可区分原因拒绝并返回明细，且不影响同批其它合法记录。
 */
@ExtendWith(BusinessLogExtension.class)
class MalformedIngestTest extends AbstractApiTest {

    private Map<String, Object> event(String id, String type, String ip, String timestamp) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", id);
        map.put("eventType", type);
        map.put("sourceIp", ip);
        map.put("timestamp", timestamp);
        return map;
    }

    @Test
    @DisplayName("批量上报：缺失字段按字段拒绝并给出 reasonCode")
    void rejectMissingFieldsWithDistinctReasons() throws Exception {
        Map<String, Object> missingId = new LinkedHashMap<>();
        missingId.put("eventType", "LOGIN_FAILURE");
        missingId.put("sourceIp", "10.0.0.1");
        missingId.put("timestamp", ts(1700000000));

        Map<String, Object> blankType = event("e2", "  ", "10.0.0.1", ts(1700000000));
        Map<String, Object> good = event("e3", "LOGIN_FAILURE", "10.0.0.1", ts(1700000000));

        JsonNode result = post("/api/events/batch", Map.of("events",
                List.of(missingId, blankType, good)));
        assertEquals(1, result.get("accepted").asInt());
        assertEquals(2, result.get("rejected").asInt());

        JsonNode errors = result.get("errors");
        assertEquals("MISSING_FIELD", errors.get(0).get("reasonCode").asText());
        assertEquals("eventId", errors.get(0).get("field").asText());
        assertEquals("BLANK_FIELD", errors.get(1).get("reasonCode").asText());
        assertEquals("0", errors.get(0).get("index").asText());
        assertEquals("1", errors.get(1).get("index").asText());
        assertNotNull(get("/api/events/e3").get("eventId"));
    }

    @Test
    @DisplayName("时间戳非法或残缺：INVALID_TIMESTAMP，且不静默丢弃")
    void rejectInvalidTimestamp() throws Exception {
        JsonNode result = post("/api/events/batch", Map.of("events", List.of(
                event("t1", "LOGIN_FAILURE", "10.0.0.1", "not-a-time"),
                event("t2", "LOGIN_FAILURE", "10.0.0.1", "2023/13/45 99:99:99"),
                event("t3", "LOGIN_FAILURE", "10.0.0.1", "1700000000123456789"))));
        assertEquals(3, result.get("rejected").asInt());
        for (JsonNode error : result.get("errors")) {
            assertEquals("INVALID_TIMESTAMP", error.get("reasonCode").asText());
        }
    }

    @Test
 @DisplayName("合法时间戳多格式（ISO/空格/斜杠/秒/毫秒）应被接收")
    void acceptMultipleTimestampFormats() throws Exception {
        JsonNode result = post("/api/events/batch", Map.of("events", List.of(
                event("f1", "LOGIN_FAILURE", "10.0.0.1", "1700000000"),
                event("f2", "LOGIN_FAILURE", "10.0.0.2", "1700000000000"),
                event("f3", "LOGIN_FAILURE", "10.0.0.3", "2023-11-14T22:13:20Z"),
                event("f4", "LOGIN_FAILURE", "10.0.0.4", "2023-11-14 22:13:20"))));
        assertEquals(4, result.get("accepted").asInt(), () -> result.toPrettyString());
    }

    @Test
    @DisplayName("非法 sourceIp 与空记录被明确拒绝")
    void rejectInvalidIpAndEmptyRecord() throws Exception {
        String body = "{\"events\":["
                + "{\"eventId\":\"i1\",\"eventType\":\"LOGIN_FAILURE\",\"sourceIp\":\"999.1.1.1\",\"timestamp\":\""
                + ts(1700000000) + "\"},"
                + "{\"eventId\":\"i2\",\"eventType\":\"LOGIN_FAILURE\",\"sourceIp\":\"not-an-ip.example\",\"timestamp\":\""
                + ts(1700000000) + "\"},"
                + "null"
                + "]}";
        JsonNode result = mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/events/batch").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString());
        assertEquals(0, result.get("accepted").asInt());
        assertEquals(3, result.get("rejected").asInt());
        assertEquals("INVALID_SOURCE_IP", result.get("errors").get(0).get("reasonCode").asText());
        assertEquals("EMPTY_RECORD", result.get("errors").get(2).get("reasonCode").asText());
    }

    @Test
    @DisplayName("请求体非法 JSON 返回 MALFORMED_JSON 400")
    void rejectMalformedJson() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/events/batch")
                        .contentType("application/json")
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(result ->
                        assertEquals("MALFORMED_JSON", mapper.readTree(result.getResponse().getContentAsString())
                                .get("errorCode").asText()));
    }
}
