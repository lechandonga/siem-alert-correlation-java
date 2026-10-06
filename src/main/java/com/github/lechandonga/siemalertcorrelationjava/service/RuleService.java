package com.github.lechandonga.siemalertcorrelationjava.service;

import com.github.lechandonga.siemalertcorrelationjava.model.Rule;
import com.github.lechandonga.siemalertcorrelationjava.model.RuleType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RuleService {

    private final ConcurrentHashMap<String, Rule> rules = new ConcurrentHashMap<>();

    public RuleService() {
        Rule threshold = new Rule();
        threshold.setRuleId("failed-login-threshold");
        threshold.setType(RuleType.THRESHOLD);
        threshold.setEventType("LOGIN_FAILURE");
        threshold.setGroupBy("sourceIp");
        threshold.setThreshold(5);
        threshold.setWindowSeconds(300);
        threshold.setSuppressionSeconds(600);
        save(threshold);

        Rule sequence = new Rule();
        sequence.setRuleId("brute-force-then-success");
        sequence.setType(RuleType.SEQUENCE);
        sequence.setGroupBy("sourceIp");
        sequence.setThreshold(3);
        sequence.setWindowSeconds(600);
        sequence.setSuppressionSeconds(1800);
        sequence.setFailureEventType("LOGIN_FAILURE");
        sequence.setSuccessEventType("LOGIN_SUCCESS");
        save(sequence);
    }

    public Rule save(Rule rule) {
        if (rule.getRuleId() == null || rule.getRuleId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_FIELD", "ruleId is required");
        }
        if (rule.getType() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_FIELD", "type is required");
        }
        if (rule.getThreshold() <= 0 || rule.getWindowSeconds() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RULE",
                    "threshold and windowSeconds must be positive");
        }
        rules.put(rule.getRuleId(), rule);
        return rule;
    }

    public List<Rule> list() {
        return rules.values().stream().sorted(Comparator.comparing(Rule::getRuleId)).toList();
    }

    public List<Rule> enabledRules() {
        return rules.values().stream().filter(Rule::isEnabled)
                .sorted(Comparator.comparing(Rule::getRuleId)).toList();
    }
}
