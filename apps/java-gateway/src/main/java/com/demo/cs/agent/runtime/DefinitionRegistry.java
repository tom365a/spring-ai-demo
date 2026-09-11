package com.demo.cs.agent.runtime;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DefinitionRegistry {

    private volatile Map<String, PublishedAgent> store = Map.of();

    public Optional<PublishedAgent> get(String code) {
        if (code == null) return Optional.empty();
        return Optional.ofNullable(store.get(code));
    }

    public synchronized void replace(String code, PublishedAgent snap) {
        if (code == null || snap == null) return;
        var next = new java.util.HashMap<>(store);
        next.put(code, snap);
        store = Map.copyOf(next);
    }

    public synchronized void remove(String code) {
        var next = new java.util.HashMap<>(store);
        if (code != null) next.remove(code);
        store = Map.copyOf(next);
    }

    public Map<String, PublishedAgent> allEnabled() {
        return Map.copyOf(store);
    }

    public synchronized void clearAndLoad(Collection<PublishedAgent> agents) {
        var next = new java.util.HashMap<String, PublishedAgent>();
        if (agents == null) { store = Map.of(); return; }
        for (PublishedAgent agent : agents) {
            if (agent != null && agent.code() != null) {
                next.put(agent.code(), agent);
            }
        }
        store = Map.copyOf(next);
    }
}
