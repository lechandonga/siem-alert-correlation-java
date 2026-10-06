package com.github.lechandonga.siemalertcorrelationjava.normalize;

import com.github.lechandonga.siemalertcorrelationjava.model.RawEvent;
import com.github.lechandonga.siemalertcorrelationjava.model.SecurityEvent;
import com.github.lechandonga.siemalertcorrelationjava.model.ValidationError;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * 原始事件归一化。任何问题都以 ValidationError 明确返回，绝不静默丢弃。
 * 拒绝原因码：
 *   MISSING_FIELD / BLANK_FIELD      必填字段缺失或空白
 *   INVALID_TIMESTAMP                时间戳无法解析
 *   FUTURE_TIMESTAMP                 事件时间远在未来（防止脏数据污染窗口）
 *   INVALID_SOURCE_IP                sourceIp 不是合法 IPv4/IPv6
 */
@Component
public class EventNormalizer {

    private static final Set<String> RESERVED =
            Set.of("eventId", "eventType", "sourceIp", "target", "username", "timestamp");

    private static final List<DateTimeFormatter> LOCAL_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME);

    public record Outcome(SecurityEvent event, ValidationError error) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * @param index 批量上报时的下标（字符串），单条上报传 null
     */
    public Outcome normalize(RawEvent raw, String index) {
        String eventId = trim(raw.eventId);
        String eventType = trim(raw.eventType);
        String sourceIp = trim(raw.sourceIp);
        String timestamp = trim(raw.timestamp);

        ValidationError missing = firstMissing(index, raw, eventId, eventType, sourceIp, timestamp);
        if (missing != null) {
            return new Outcome(null, missing);
        }
        if (!isValidIp(sourceIp)) {
            return new Outcome(null, error(index, eventId, "sourceIp", "INVALID_SOURCE_IP",
                    "sourceIp 不是合法的 IPv4/IPv6 地址: " + sourceIp));
        }
        Instant eventTime = parseTimestamp(timestamp);
        if (eventTime == null) {
            return new Outcome(null, error(index, eventId, "timestamp", "INVALID_TIMESTAMP",
                    "无法解析的时间戳: " + raw.timestamp + "（支持 epoch 毫秒/秒、ISO-8601、yyyy-MM-dd HH:mm:ss）"));
        }
        if (eventTime.isAfter(Instant.now().plusSeconds(300))) {
            return new Outcome(null, error(index, eventId, "timestamp", "FUTURE_TIMESTAMP",
                    "事件时间超出当前时间 5 分钟以上，疑似时钟错误的脏数据: " + eventTime));
        }

        raw.getAttributes().keySet().removeIf(RESERVED::contains);
        SecurityEvent event = new SecurityEvent(
                eventId,
                eventType,
                sourceIp,
                trim(raw.target),
                trim(raw.username),
                eventType,
                eventTime,
                Instant.now(),
                raw.getAttributes());
        return new Outcome(event, null);
    }

    private ValidationError firstMissing(String index, RawEvent raw, String eventId, String eventType,
                                         String sourceIp, String timestamp) {
        record Required(String rawValue, String value, String field) {
        }
        Required[] required = {
                new Required(raw.eventId, eventId, "eventId"),
                new Required(raw.eventType, eventType, "eventType"),
                new Required(raw.sourceIp, sourceIp, "sourceIp"),
                new Required(raw.timestamp, timestamp, "timestamp")
        };
        String knownId = eventId;
        for (Required item : required) {
            if (item.rawValue == null) {
                return error(index, knownId, item.field, "MISSING_FIELD",
                        "缺少必填字段 " + item.field);
            }
            if (item.value == null) {
                return error(index, knownId, item.field, "BLANK_FIELD",
                        "必填字段 " + item.field + " 为空白");
            }
        }
        return null;
    }

    private ValidationError error(String index, String eventId, String field, String code, String message) {
        return new ValidationError(index, eventId, field, code, message);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isValidIp(String ip) {
        return isIpv4(ip) || isIpv6(ip);
    }

    private boolean isIpv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (!part.matches("\\d{1,3}") || part.length() > 1 && part.charAt(0) == '0') {
                return false;
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 纯字符串 IPv6 校验（允许 :: 压缩，不做 DNS 查询）。
     */
    private boolean isIpv6(String ip) {
        if (!ip.contains(":")) {
            return false;
        }
        String body = ip;
        if (body.startsWith("[")) {
            return false;
        }
        int doubleColon = body.indexOf("::");
        if (doubleColon != body.lastIndexOf("::")) {
            return false;
        }
        String[] halves = doubleColon >= 0
                ? new String[]{body.substring(0, doubleColon), body.substring(doubleColon + 2)}
                : new String[]{body, null};
        int groups = 0;
        for (String half : halves) {
            if (half == null) {
                continue;
            }
            if (half.isEmpty()) {
                continue;
            }
            String[] segments = half.split(":", -1);
            for (String segment : segments) {
                if (!segment.matches("[0-9a-fA-F]{1,4}")) {
                    return false;
                }
                groups++;
            }
        }
        return doubleColon >= 0 ? groups <= 7 : groups == 8;
    }

    static Instant parseTimestamp(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.matches("\\d+")) {
            long number = Long.parseLong(text);
            // 10 位视为秒，13 位视为毫秒，其它长度无法确定，拒绝而不是猜测
            if (text.length() == 10) {
                return Instant.ofEpochSecond(number);
            }
            if (text.length() == 13) {
                return Instant.ofEpochMilli(number);
            }
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地格式
        }
        try {
            return java.time.OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        for (DateTimeFormatter formatter : LOCAL_FORMATS) {
            try {
                return LocalDateTime.parse(text, formatter).atOffset(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ignored) {
                // 尝试下一种
            }
        }
        return null;
    }
}
