package com.github.lechandonga.siemalertcorrelationjava.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部上报的原始事件，字段全部可选，由归一化器做校验与补全。
 */
public class RawEvent {
    public String eventId;
    public String eventType;
    public String sourceIp;
    public String target;
    public String username;
    public String timestamp;

    private final Map<String, String> attributes = new LinkedHashMap<>();

    @JsonAnySetter
    public void set(String key, Object value) {
        if (value != null) {
            attributes.put(key, value.toString());
        }
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }
}
