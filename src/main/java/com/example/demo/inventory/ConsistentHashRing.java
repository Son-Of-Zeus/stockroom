package com.example.demo.inventory;

import java.util.Collection;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class ConsistentHashRing {
    private final NavigableMap<String, String> ring = new TreeMap<>();

    public ConsistentHashRing(Collection<String> nodes) {
        for (String node : nodes) {
            for (int i = 0; i < 128; i++) ring.put(Checks.hash(node + "#" + i), node);
        }
        if (ring.isEmpty()) throw new IllegalArgumentException("At least one node is required.");
    }

    public String node(String key) {
        var entry = ring.ceilingEntry(Checks.hash(key));
        return (entry == null ? ring.firstEntry() : entry).getValue();
    }
}
