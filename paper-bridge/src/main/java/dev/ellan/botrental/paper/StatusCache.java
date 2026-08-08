package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.ApiMessages.StatusResponse;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class StatusCache {
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    void put(UUID player, StatusResponse status) {
        entries.put(player, new Entry(status, System.currentTimeMillis()));
    }

    Optional<StatusResponse> get(UUID player) {
        Entry entry = entries.get(player);
        return entry == null ? Optional.empty() : Optional.of(entry.status());
    }

    boolean needsRefresh(UUID player, long maxAgeMillis) {
        Entry entry = entries.get(player);
        return entry == null || System.currentTimeMillis() - entry.loadedAt() >= maxAgeMillis;
    }

    void remove(UUID player) {
        entries.remove(player);
    }

    private record Entry(StatusResponse status, long loadedAt) {
    }
}
