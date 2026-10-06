package com.github.lechandonga.siemalertcorrelationjava.storage;

import com.github.lechandonga.siemalertcorrelationjava.model.SecurityEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件存储。ConcurrentHashMap 语义由外层检测引擎的单一锁保证，这里只做内存态 + 落盘。
 */
@Component
public class EventStore {
    private final JsonFileStore files;
    private final Map<String, SecurityEvent> byId = new LinkedHashMap<>();

    public EventStore(JsonFileStore files) {
        this.files = files;
        loadFrom(files.read("events.json",
                new com.fasterxml.jackson.core.type.TypeReference<ArrayList<SecurityEvent>>() {}.getType(),
                new ArrayList<>()));
    }

    private void loadFrom(List<SecurityEvent> events) {
        for (SecurityEvent event : events) {
            byId.put(event.eventId(), event);
        }
    }

    public boolean exists(String eventId) {
        return byId.containsKey(eventId);
    }

    public SecurityEvent putIfAbsent(SecurityEvent event) {
        SecurityEvent previous = byId.putIfAbsent(event.eventId(), event);
        if (previous == null) {
            persist();
            return event;
        }
        return previous;
    }

    public List<SecurityEvent> allOrderedByTime() {
        List<SecurityEvent> events = new ArrayList<>(byId.values());
        // 事件时间为主、eventId 为辅：同一时刻也有确定顺序，保证乱序入库后检测结论可复现
        events.sort(Comparator.comparing(SecurityEvent::eventTime).thenComparing(SecurityEvent::eventId));
        return events;
    }

    public int size() {
        return byId.size();
    }

    public void clear() {
        byId.clear();
        persist();
    }

    private synchronized void persist() {
        files.write("events.json", new ArrayList<>(byId.values()));
    }
}
