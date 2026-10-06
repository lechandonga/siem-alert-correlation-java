package com.github.lechandonga.siemalertcorrelationjava.model;

import java.time.Instant;

public record AlertHistoryEntry(Instant at, String action, String reason) {
}
