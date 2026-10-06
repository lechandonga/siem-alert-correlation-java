package com.github.lechandonga.siemalertcorrelationjava.engine;

import com.github.lechandonga.siemalertcorrelationjava.model.IngestResult;
import com.github.lechandonga.siemalertcorrelationjava.model.InstantDetectionSummary;
import com.github.lechandonga.siemalertcorrelationjava.model.RawEvent;
import com.github.lechandonga.siemalertcorrelationjava.model.SecurityEvent;
import com.github.lechandonga.siemalertcorrelationjava.model.ValidationError;
import com.github.lechandonga.siemalertcorrelationjava.normalize.EventNormalizer;
import com.github.lechandonga.siemalertcorrelationjava.storage.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 上报服务：归一化 -> 幂等去重 -> 单次全量检测重算。
 * 整个批次在检测引擎的同一把锁内完成，并发上报不会重复计数或重复触发。
 */
@Service
public class IngestService {
    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final EventNormalizer normalizer;
    private final EventStore eventStore;
    private final DetectionEngine engine;

    public IngestService(EventNormalizer normalizer, EventStore eventStore, DetectionEngine engine) {
        this.normalizer = normalizer;
        this.eventStore = eventStore;
        this.engine = engine;
    }

    public IngestResult ingest(List<RawEvent> raws) {
        return engine.withLock(() -> {
            List<ValidationError> errors = new ArrayList<>();
            List<IngestResult.AcceptedEvent> accepted = new ArrayList<>();
            Set<String> touched = new HashSet<>();
            int duplicate = 0;
            Set<String> seenInBatch = new HashSet<>();

            for (int i = 0; i < raws.size(); i++) {
                String index = String.valueOf(i);
                RawEvent raw = raws.get(i);
                if (raw == null) {
                    errors.add(new ValidationError(index, null, null, "EMPTY_RECORD", "上报记录为空"));
                    continue;
                }
                EventNormalizer.Outcome outcome = normalizer.normalize(raw, index);
                if (!outcome.ok()) {
                    errors.add(outcome.error());
                    log.info("[业务:畸形上报] 拒绝 index={} eventId={} 原因={}",
                            index, outcome.error().eventId(), outcome.error().reasonCode());
                    continue;
                }
                SecurityEvent event = outcome.event();
                if (!seenInBatch.add(event.eventId()) || eventStore.exists(event.eventId())) {
                    duplicate++;
                    log.info("[业务:重复上报] eventId={} 已存在，跳过(不计数不触发)", event.eventId());
                    accepted.add(new IngestResult.AcceptedEvent(event.eventId(), "DUPLICATE", null));
                    continue;
                }
                eventStore.putIfAbsent(event);
                touched.add(event.eventId());
                accepted.add(new IngestResult.AcceptedEvent(event.eventId(), "ACCEPTED", null));
            }

            DetectionSummary summary = touched.isEmpty()
                    ? DetectionSummary.empty() : engine.recompute(touched);

            InstantDetectionSummary instant = new InstantDetectionSummary(
                    summary.createdAlertIds(), summary.mergedAlertIds(),
                    summary.reopenedAlertIds(), summary.suppressedByAlertIds());
            List<IngestResult.AcceptedEvent> enriched = accepted.stream()
                    .map(item -> item.status().equals("ACCEPTED")
                            ? new IngestResult.AcceptedEvent(item.eventId(), item.status(), instant) : item)
                    .toList();

            int acceptedCount = raws.size() - errors.size() - duplicate;
            log.info("[业务:事件接入] 批次完成 接收={} 拒绝={} 重复={} 新建告警={} 合并={} 重开={}",
                    acceptedCount, errors.size(), duplicate,
                    summary.createdAlertIds().size(), summary.mergedAlertIds().size(),
                    summary.reopenedAlertIds().size());
            return new IngestResult(acceptedCount, errors.size(), duplicate, enriched, errors);
        });
    }
}
