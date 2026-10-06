package com.github.lechandonga.siemalertcorrelationjava.engine;

import com.github.lechandonga.siemalertcorrelationjava.model.DetectionRule;
import com.github.lechandonga.siemalertcorrelationjava.model.Occurrence;
import com.github.lechandonga.siemalertcorrelationjava.model.SecurityEvent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 规则匹配器。输入始终是"按 (eventTime, eventId) 排好序的全量事件"，
 * 因此无论事件以何种顺序、是否迟到入库，输出的命中集合都完全一致（确定性重放的基础）。
 */
@Component
public class RuleMatcher {

    public List<Occurrence> match(DetectionRule rule, List<SecurityEvent> orderedEvents) {
        if (!rule.enabled()) {
            return List.of();
        }
        Map<String, List<SecurityEvent>> groups = groupBy(rule, orderedEvents);
        List<Occurrence> occurrences = new ArrayList<>();
        for (Map.Entry<String, List<SecurityEvent>> entry : groups.entrySet()) {
            String dedupKey = rule.ruleId() + "|" + entry.getKey();
            List<SecurityEvent> grouped = entry.getValue();
            switch (rule.type()) {
                case THRESHOLD -> occurrences.addAll(matchThreshold(rule, dedupKey, grouped));
                case SEQUENCE -> occurrences.addAll(matchSequence(rule, dedupKey, grouped));
            }
        }
        return occurrences;
    }

    private Map<String, List<SecurityEvent>> groupBy(DetectionRule rule, List<SecurityEvent> events) {
        List<String> fields = rule.groupBy() == null || rule.groupBy().isEmpty()
                ? List.of("sourceIp") : rule.groupBy();
        Map<String, List<SecurityEvent>> groups = new TreeMap<>();
        for (SecurityEvent event : events) {
            String groupKey = groupKey(fields, event);
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(event);
        }
        return groups;
    }

    private String groupKey(List<String> fields, SecurityEvent event) {
        StringBuilder key = new StringBuilder();
        for (String field : fields) {
            if (!key.isEmpty()) {
                key.append(';');
            }
            key.append(field).append('=').append(extract(event, field));
        }
        return key.toString();
    }

    private String extract(SecurityEvent event, String field) {
        String value = switch (field) {
            case "sourceIp" -> event.sourceIp();
            case "username" -> event.username();
            case "target" -> event.target();
            case "eventType" -> event.eventType();
            default -> event.attributes().get(field);
        };
        return value == null ? "" : value;
    }

    /**
     * 阈值规则：滑动窗口统计。窗口内计数到达阈值时产生一次命中，随后该批事件出窗重新累计，
     * 保证每个事件最多参与一次命中 —— 重放时命中集合不因扫描顺序而变化。
     */
    private List<Occurrence> matchThreshold(DetectionRule rule, String dedupKey, List<SecurityEvent> events) {
        List<Occurrence> result = new ArrayList<>();
        List<SecurityEvent> window = new ArrayList<>();
        for (SecurityEvent current : events) {
            if (!rule.eventTypes().contains(current.eventType())) {
                continue;
            }
            window.removeIf(old -> Duration.between(old.eventTime(), current.eventTime())
                    .getSeconds() > rule.windowSeconds());
            window.add(current);
            if (window.size() >= rule.threshold()) {
                List<SecurityEvent> hit = new ArrayList<>(window);
                result.add(toOccurrence(rule, dedupKey, hit));
                // 命中批次出窗，后续计数重新累计，避免同批事件重复触发
                window.clear();
            }
        }
        return result;
    }

    /**
     * 序列规则：贪心 NFA。每个事件优先延伸"进度最深、起点最早"的候选部分链，
     * 同时若其等于链首类型则另起一条新链，允许重叠匹配。
     */
    private List<Occurrence> matchSequence(DetectionRule rule, String dedupKey, List<SecurityEvent> events) {
        List<Occurrence> result = new ArrayList<>();
        List<List<SecurityEvent>> chains = new ArrayList<>();
        List<String> pattern = rule.sequence();
        for (SecurityEvent current : events) {
            if (!rule.eventTypes().contains(current.eventType())) {
                continue;
            }
            int best = -1;
            for (int i = 0; i < chains.size(); i++) {
                List<SecurityEvent> chain = chains.get(i);
                String expected = pattern.get(chain.size());
                if (!expected.equals(current.eventType())) {
                    continue;
                }
                long gap = Duration.between(chain.get(chain.size() - 1).eventTime(), current.eventTime())
                        .getSeconds();
                if (gap < 0 || gap > rule.windowSeconds()) {
                    continue;
                }
                if (best == -1 || chains.get(i).size() > chains.get(best).size()
                        || (chains.get(i).size() == chains.get(best).size()
                        && chains.get(i).get(0).eventTime().isBefore(chains.get(best).get(0).eventTime()))) {
                    best = i;
                }
            }
            if (best >= 0) {
                List<SecurityEvent> chain = chains.get(best);
                chain.add(current);
                if (chain.size() == pattern.size()) {
                    result.add(toOccurrence(rule, dedupKey, chain));
                    chains.remove(best);
                }
            }
            if (pattern.get(0).equals(current.eventType())) {
                List<SecurityEvent> fresh = new ArrayList<>();
                fresh.add(current);
                chains.add(fresh);
            }
        }
        return result;
    }

    private Occurrence toOccurrence(DetectionRule rule, String dedupKey, List<SecurityEvent> hitEvents) {
        List<String> ids = hitEvents.stream().map(SecurityEvent::eventId).sorted().toList();
        String fingerprint = sha8(rule.ruleId() + "|" + String.join(",", ids));
        Instant first = hitEvents.get(0).eventTime();
        Instant last = hitEvents.get(hitEvents.size() - 1).eventTime();
        for (SecurityEvent event : hitEvents) {
            if (event.eventTime().isBefore(first)) {
                first = event.eventTime();
            }
            if (event.eventTime().isAfter(last)) {
                last = event.eventTime();
            }
        }
        return new Occurrence(rule.ruleId(), rule.version(), dedupKey, fingerprint, ids, first, last);
    }

    static String sha8(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
