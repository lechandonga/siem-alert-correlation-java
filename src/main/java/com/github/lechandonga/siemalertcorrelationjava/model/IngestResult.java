package com.github.lechandonga.siemalertcorrelationjava.model;

import java.util.List;

/**
 * 上报结果。重复上报被明确标记为 duplicate，不重新计数、不重新检测。
 */
public record IngestResult(int accepted, int rejected, int duplicate,
                           List<AcceptedEvent> acceptedEvents,
                           List<ValidationError> errors) {

    public record AcceptedEvent(String eventId, String status, InstantDetectionSummary detection) {
    }
}
