package com.github.lechandonga.siemalertcorrelationjava.web;

import com.github.lechandonga.siemalertcorrelationjava.storage.AlertStore;
import com.github.lechandonga.siemalertcorrelationjava.storage.EventStore;
import com.github.lechandonga.siemalertcorrelationjava.storage.RuleStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 测试/本地重置接口，仅在 siem.reset-enabled=true 时注册（测试配置开启）。
 */
@RestController
@RequestMapping("/api/admin")
@ConditionalOnProperty(name = "siem.reset-enabled", havingValue = "true")
public class AdminController {
    private final EventStore eventStore;
    private final AlertStore alertStore;
    private final RuleStore ruleStore;

    public AdminController(EventStore eventStore, AlertStore alertStore, RuleStore ruleStore) {
        this.eventStore = eventStore;
        this.alertStore = alertStore;
        this.ruleStore = ruleStore;
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        eventStore.clear();
        alertStore.clear();
        ruleStore.resetToDefaults();
        return Map.of("result", "reset");
    }
}
