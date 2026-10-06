package com.github.lechandonga.siemalertcorrelationjava.web;

import com.github.lechandonga.siemalertcorrelationjava.engine.IngestService;
import com.github.lechandonga.siemalertcorrelationjava.model.IngestResult;
import com.github.lechandonga.siemalertcorrelationjava.model.RawEvent;
import com.github.lechandonga.siemalertcorrelationjava.model.SecurityEvent;
import com.github.lechandonga.siemalertcorrelationjava.storage.EventStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final IngestService ingestService;
    private final EventStore eventStore;

    public EventController(IngestService ingestService, EventStore eventStore) {
        this.ingestService = ingestService;
        this.eventStore = eventStore;
    }

    @PostMapping
    public IngestResult ingestOne(@RequestBody RawEvent raw) {
        return ingestService.ingest(List.of(raw));
    }

    @PostMapping("/batch")
    public IngestResult ingestBatch(@RequestBody Map<String, List<RawEvent>> body) {
        List<RawEvent> events = body == null ? null : body.get("events");
        if (events == null) {
            throw new ApiException(400, "MISSING_FIELD", "批量上报需要 {\"events\": [...]} 请求体");
        }
        return ingestService.ingest(events);
    }

    @GetMapping
    public List<SecurityEvent> list() {
        return eventStore.allOrderedByTime();
    }

    @GetMapping("/{eventId}")
    public SecurityEvent get(@PathVariable String eventId) {
        return eventStore.allOrderedByTime().stream()
                .filter(event -> event.eventId().equals(eventId))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "EVENT_NOT_FOUND", "事件不存在: " + eventId));
    }
}
