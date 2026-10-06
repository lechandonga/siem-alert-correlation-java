package com.github.lechandonga.siemalertcorrelationjava.model;

import java.time.Instant;

/**
 * 告警时间线，记录告警上每一次可解释的动作：命中、抑制、状态流转、重放修正等。
 */
public record TimelineEntry(Instant at, String action, String detail, String operator) {
}
