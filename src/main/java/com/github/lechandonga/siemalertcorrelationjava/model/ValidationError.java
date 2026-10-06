package com.github.lechandonga.siemalertcorrelationjava.model;

/**
 * 归一化失败明细。reasonCode 可机读，field 指明出错字段（整条错误时为空）。
 */
public record ValidationError(String index, String eventId, String field, String reasonCode, String message) {
}
