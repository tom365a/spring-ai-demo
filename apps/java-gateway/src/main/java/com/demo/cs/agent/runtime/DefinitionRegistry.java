package com.demo.cs.agent.runtime;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DefinitionRegistry {

    private final ConcurrentHashMap<String, PublishedAgent> store = new ConcurrentHashMap<>();

    public Optional<PublishedAgent> get(String code) {
        if (code == null) return Optional.empty();
        return Optional.ofNullable(store.get(code));
    }

    public void replace(String code, PublishedAgent snap) {
        if (code == null || snap == null) return;
        store.put(code, snap);
    }

    public void remove(String code) {
        if (code != null) store.remove(code);
    }

    public Map<String, PublishedAgent> allEnabled() {
        return Map.copyOf(store);
    }

    public void clearAndLoad(Collection<PublishedAgent> agents) {
        store.clear();
        if (agents == null) return;
        for (PublishedAgent agent : agents) {
            if (agent != null && agent.code() != null) {
                store.put(agent.code(), agent);
            }
        }
    }
}
