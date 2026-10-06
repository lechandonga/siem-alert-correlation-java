package com.github.lechandonga.siemalertcorrelationjava.model;

import java.util.List;

/**
 * 本次入库后检测引擎给出的结果摘要，方便上报方立即看到可解释结论。
 */
public record InstantDetectionSummary(List<String> createdAlertIds,
                                      List<String> mergedAlertIds,
                                      List<String> reopenedAlertIds,
                                      List<String> suppressedByAlertIds) {
}
