package com.github.lechandonga.siemalertcorrelationjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：并发场景 —— 并发状态流转与乐观锁不丢更新；并发重复上报同一事件只计数一次。
 */
@ExtendWith(BusinessLogExtension.class)
class ConcurrentUpdateTest extends AbstractApiTest {

    private String createAlert(String prefix) throws Exception {
        post("/api/events/batch", Map.of("events", List.of(
                Map.of("eventId", prefix + "1", "eventType", "LOGIN_FAILURE",
                        "sourceIp", "10.5.0.1", "timestamp", ts(1700005000)),
                Map.of("eventId", prefix + "2", "eventType", "LOGIN_FAILURE",
                        "sourceIp", "10.5.0.1", "timestamp", ts(1700005020)),
                Map.of("eventId", prefix + "3", "eventType", "LOGIN_FAILURE",
                        "sourceIp", "10.5.0.1", "timestamp", ts(1700005040)))));
        return get("/api/alerts").get(0).get("id").asText();
    }

    @Test
    @DisplayName("并发流转 + 乐观锁：只有一个 CLOSE 成功，其余冲突或非法操作被拒绝，最终状态一致")
    void concurrentTransitionsDoNotLoseState() throws Exception {
        String id = createAlert("c");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger versionsSeen = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    JsonNode response = post("/api/alerts/" + id + "/transition",
                            Map.of("action", "CLOSE", "operator", "t" + Thread.currentThread().getId()));
                    if ("CLOSED".equals(response.path("status").asText(null))) {
                        closed.incrementAndGet();
                    } else if ("VERSION_CONFLICT".equals(response.path("errorCode").asText(null))
                            || "ILLEGAL_TRANSITION".equals(response.path("errorCode").asText(null))) {
                        rejected.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    rejected.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        assertEquals(1, closed.get(), "只能有一个 CLOSE 生效");
        assertEquals(threads - 1, rejected.get(), "其余并发写必须被拒绝，状态不丢失");
        assertEquals(1, get("/api/alerts/" + id).get("version").asInt());

        JsonNode stale = post("/api/alerts/" + id + "/transition",
                Map.of("action", "REOPEN", "expectedVersion", 0));
        assertEquals("VERSION_CONFLICT", stale.get("errorCode").asText());
        JsonNode fresh = post("/api/alerts/" + id + "/transition",
                Map.of("action", "REOPEN", "expectedVersion", 1));
        assertEquals("OPEN", fresh.get("status").asText());
    }

    @Test
    @DisplayName("并发重复上报同一事件：只入库一次，阈值只产生一条告警")
    void concurrentDuplicateIngestCountsOnce() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    JsonNode result = post("/api/events", Map.of(
                            "eventId", "same-id", "eventType", "LOGIN_FAILURE",
                            "sourceIp", "10.5.0.9", "timestamp", ts(1700006000)));
                    accepted.addAndGet(result.get("accepted").asInt());
                    duplicate.addAndGet(result.get("duplicate").asInt());
                } catch (Exception ignored) {
                    // 并发下 HTTP 层不应抛异常；若抛了视为失败
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        assertEquals(1, accepted.get());
        assertEquals(threads - 1, duplicate.get());
        assertEquals(1, get("/api/events").size());
    }
}
