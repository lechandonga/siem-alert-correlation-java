package com.github.lechandonga.siemalertcorrelationjava.service;

import com.github.lechandonga.siemalertcorrelationjava.model.NormalizedEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EventStore {

    private final ConcurrentHashMap<String, NormalizedEvent> events = new ConcurrentHashMap<>();

    public boolean addIfAbsent(NormalizedEvent event) {
        return events.putIfAbsent(event.eventId(), event) == null;
    }

    public List<NormalizedEvent> all() {
        return events.values().stream()
                .sorted(Comparator.comparing(NormalizedEvent::timestamp).thenComparing(NormalizedEvent::eventId))
                .toList();
    }

    public List<NormalizedEvent> query(String eventType, String groupBy, String groupKey,
                                       Instant fromInclusive, Instant toInclusive) {
        List<NormalizedEvent> result = new ArrayList<>();
        for (NormalizedEvent e : events.values()) {
            if (eventType != null && !eventType.equals(e.eventType())) {
                continue;
            }
            String key = groupValue(e, groupBy);
            if (groupKey != null && !groupKey.equals(key)) {
                continue;
            }
            if (e.timestamp().isBefore(fromInclusive) || e.timestamp().isAfter(toInclusive)) {
                continue;
            }
            result.add(e);
        }
        result.sort(Comparator.comparing(NormalizedEvent::timestamp).thenComparing(NormalizedEvent::eventId));
        return result;
    }

    public static String groupValue(NormalizedEvent event, String groupBy) {
        return switch (groupBy == null ? "sourceIp" : groupBy) {
            case "sourceIp" -> event.sourceIp();
            case "username" -> event.username();
            default -> null;
        };
    }

    public int size() {
        return events.size();
    }

    public void clear() {
        events.clear();
    }
}
