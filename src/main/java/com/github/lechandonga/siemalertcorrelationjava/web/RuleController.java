package com.github.lechandonga.siemalertcorrelationjava.web;

import com.github.lechandonga.siemalertcorrelationjava.engine.DetectionEngine;
import com.github.lechandonga.siemalertcorrelationjava.model.DetectionRule;
import com.github.lechandonga.siemalertcorrelationjava.model.InstantDetectionSummary;
import com.github.lechandonga.siemalertcorrelationjava.model.RuleType;
import com.github.lechandonga.siemalertcorrelationjava.storage.RuleStore;
import com.github.lechandonga.siemalertcorrelationjava.web.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/rules")
public class RuleController {
    private final RuleStore ruleStore;
    private final DetectionEngine engine;

    public RuleController(RuleStore ruleStore, DetectionEngine engine) {
        this.ruleStore = ruleStore;
        this.engine = engine;
    }

    @GetMapping
    public List<DetectionRule> list() {
        return ruleStore.all();
    }

    /**
     * 新增或调整规则。调整后立即基于全量历史事件重放，返回与本次重放相关的告警变化。
     */
    @PostMapping
    public InstantDetectionSummary upsert(@RequestBody DetectionRule rule) {
        validate(rule);
        return engine.withLock(() -> {
            DetectionRule stored = ruleStore.upsert(rule);
            return toInstant(engine.recompute(Set.of()));
        });
    }

    /**
     * 显式重放：不修改规则，只对当前全量事件重跑检测，结论必须与规则调整时一致、可复现。
     */
    @PostMapping("/{ruleId}/replay")
    public InstantDetectionSummary replay(@PathVariable String ruleId) {
        if (ruleStore.all().stream().noneMatch(rule -> rule.ruleId().equals(ruleId))) {
            throw new ApiException(404, "RULE_NOT_FOUND", "规则不存在: " + ruleId);
        }
        return engine.withLock(() -> toInstant(engine.recompute(Set.of())));
    }

    private InstantDetectionSummary toInstant(com.github.lechandonga.siemalertcorrelationjava.engine.DetectionSummary summary) {
        return new InstantDetectionSummary(summary.createdAlertIds(), summary.mergedAlertIds(),
                summary.reopenedAlertIds(), summary.suppressedByAlertIds());
    }

    private void validate(DetectionRule rule) {
        if (rule == null || rule.ruleId() == null || rule.ruleId().isBlank()) {
            throw new ApiException(400, "MISSING_FIELD", "规则缺少 ruleId");
        }
        if (rule.type() == null) {
            throw new ApiException(400, "MISSING_FIELD", "规则缺少 type (THRESHOLD/SEQUENCE)");
        }
        if (rule.type() == RuleType.THRESHOLD && rule.threshold() < 1) {
            throw new ApiException(400, "INVALID_RULE", "THRESHOLD 规则的 threshold 必须 >= 1");
        }
        if (rule.type() == RuleType.SEQUENCE
                && (rule.sequence() == null || rule.sequence().size() < 2)) {
            throw new ApiException(400, "INVALID_RULE", "SEQUENCE 规则至少需要 2 个事件类型");
        }
        if (rule.windowSeconds() <= 0 || rule.suppressSeconds() < 0) {
            throw new ApiException(400, "INVALID_RULE", "windowSeconds 必须 > 0，suppressSeconds 必须 >= 0");
        }
    }
}
