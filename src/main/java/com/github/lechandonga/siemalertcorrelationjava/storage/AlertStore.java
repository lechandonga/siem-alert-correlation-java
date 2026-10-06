package com.github.lechandonga.siemalertcorrelationjava.storage;

import com.github.lechandonga.siemalertcorrelationjava.model.Alert;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警存储。id 确定（ruleId + dedupKey 的哈希），重放/重算时按 id 原地更新。
 */
@Component
public class AlertStore {
    private final JsonFileStore files;
    private final Map<String, Alert> byId = new LinkedHashMap<>();

    public AlertStore(JsonFileStore files) {
        this.files = files;
        ArrayList<Alert> loaded = files.read("alerts.json",
                new com.fasterxml.jackson.core.type.TypeReference<ArrayList<Alert>>() {}.getType(),
                new ArrayList<>());
        for (Alert alert : loaded) {
            byId.put(alert.id, alert);
        }
    }

    public Alert get(String id) {
        return byId.get(id);
    }

    public void put(Alert alert) {
        byId.put(alert.id, alert);
    }

    public void remove(String id) {
        byId.remove(id);
    }

    public List<Alert> all() {
        return new ArrayList<>(byId.values());
    }

    public synchronized void persist() {
        files.write("alerts.json", new ArrayList<>(byId.values()));
    }

    public void clear() {
        byId.clear();
        persist();
    }
}
