package com.github.lechandonga.siemalertcorrelationjava.web;

import com.github.lechandonga.siemalertcorrelationjava.engine.AlertLifecycleService;
import com.github.lechandonga.siemalertcorrelationjava.model.Alert;
import com.github.lechandonga.siemalertcorrelationjava.model.AlertStatus;
import com.github.lechandonga.siemalertcorrelationjava.storage.AlertStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    private final AlertStore alertStore;
    private final AlertLifecycleService lifecycle;

    public AlertController(AlertStore alertStore, AlertLifecycleService lifecycle) {
        this.alertStore = alertStore;
        this.lifecycle = lifecycle;
    }

    @GetMapping
    public List<Alert> list(@RequestParam(required = false) AlertStatus status) {
        return alertStore.all().stream()
                .filter(alert -> status == null || alert.status == status)
                .sorted(Comparator.comparing((Alert a) -> a.lastOccurrenceAt).reversed())
                .toList();
    }

    @GetMapping("/{id}")
    public Alert get(@PathVariable String id) {
        Alert alert = alertStore.get(id);
        if (alert == null) {
            throw new ApiException(404, "ALERT_NOT_FOUND", "告警不存在: " + id);
        }
        return alert;
    }

    /**
     * 状态流转。body: {"action":"ACK|UNACK|CLOSE|REOPEN","operator":"...","expectedVersion":3}
     * expectedVersion 可选，传入即启用乐观锁并发检查。
     */
    @PostMapping("/{id}/transition")
    public Alert transition(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String action = body.get("action") == null ? null : body.get("action").toString();
        String operator = body.get("operator") == null ? null : body.get("operator").toString();
        Long expectedVersion = body.get("expectedVersion") == null
                ? null : Long.valueOf(body.get("expectedVersion").toString());
        return lifecycle.transition(id, action, operator, expectedVersion);
    }
}
