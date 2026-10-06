package com.github.lechandonga.siemalertcorrelationjava.storage;

import com.github.lechandonga.siemalertcorrelationjava.model.DetectionRule;
import com.github.lechandonga.siemalertcorrelationjava.model.RuleType;
import com.github.lechandonga.siemalertcorrelationjava.model.Severity;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则存储。首次启动内置两条示例规则（阈值 + 序列），规则可经 HTTP 调整后整体重放。
 */
@Component
public class RuleStore {
    private final JsonFileStore files;
    private final Map<String, DetectionRule> byId = new LinkedHashMap<>();

    public RuleStore(JsonFileStore files) {
        this.files = files;
        ArrayList<DetectionRule> loaded = files.read("rules.json",
                new com.fasterxml.jackson.core.type.TypeReference<ArrayList<DetectionRule>>() {}.getType(),
                new ArrayList<>());
        for (DetectionRule rule : loaded) {
            byId.put(rule.ruleId(), rule);
        }
    }

    @PostConstruct
    void seedDefaults() {
        if (!byId.isEmpty()) {
            return;
        }
        upsert(new DetectionRule("R-BRUTE-FORCE", "同源多次登录失败(暴力破解)", RuleType.THRESHOLD,
                true, List.of("LOGIN_FAILURE"), 3, List.of(), 300, 600,
                List.of("sourceIp"), Severity.HIGH, 1));
        upsert(new DetectionRule("R-FAIL-THEN-SUCCESS", "多次失败登录后紧跟成功登录(可能已失陷)",
                RuleType.SEQUENCE, true, List.of("LOGIN_FAILURE", "LOGIN_SUCCESS"), 0,
                List.of("LOGIN_FAILURE", "LOGIN_FAILURE", "LOGIN_SUCCESS"), 600, 900,
                List.of("sourceIp", "username"), Severity.CRITICAL, 1));
    }

    public synchronized DetectionRule upsert(DetectionRule rule) {
        DetectionRule existing = byId.get(rule.ruleId());
        DetectionRule toStore = existing == null ? rule : rule.withVersion(existing.version() + 1);
        byId.put(rule.ruleId(), toStore);
        persist();
        return toStore;
    }

    public synchronized List<DetectionRule> all() {
        return new ArrayList<>(byId.values());
    }

    private void persist() {
        files.write("rules.json", new ArrayList<>(byId.values()));
    }

    /**
     * 清空规则并重新种入内置规则（供测试隔离使用）。
     */
    public synchronized void resetToDefaults() {
        byId.clear();
        persist();
        seedDefaults();
    }
}
