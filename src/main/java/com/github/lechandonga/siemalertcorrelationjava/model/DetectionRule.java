package com.github.lechandonga.siemalertcorrelationjava.model;

/**
 * 检测规则。
 *
 * THRESHOLD：在 windowSeconds 滑动窗口内，eventTypes 全部计数，某分组计数达到 threshold 即命中一次。
 *            分组键由 groupBy 指定（sourceIp / username / target，逗号分隔，默认 sourceIp）。
 * SEQUENCE：按事件时间顺序匹配 sequence 事件类型链（如 ["LOGIN_FAILURE","LOGIN_FAILURE","LOGIN_SUCCESS"]），
 *           链中相邻事件时间差不超过 windowSeconds；groupBy 相同才算同一序列。
 * 抑制语义：同一 dedupKey 的命中，若距上一次命中不足 suppressSeconds，则合并进同一条告警
 *           （不新建告警，但在告警上记录一次命中并解释 suppress 原因）。
 */
public record DetectionRule(
        String ruleId,
        String name,
        RuleType type,
        boolean enabled,
        java.util.List<String> eventTypes,
        int threshold,
        java.util.List<String> sequence,
        long windowSeconds,
        long suppressSeconds,
        java.util.List<String> groupBy,
        Severity severity,
        int version
) {
    public DetectionRule withVersion(int newVersion) {
        return new DetectionRule(ruleId, name, type, enabled, eventTypes, threshold, sequence,
                windowSeconds, suppressSeconds, groupBy, severity, newVersion);
    }
}
