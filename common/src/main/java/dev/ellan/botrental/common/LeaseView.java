package dev.ellan.botrental.common;

import java.util.UUID;

public record LeaseView(
    UUID leaseId,
    int slot,
    String botId,
    String botUsername,
    String state,
    long reserveCoins,
    long spentCoins,
    long currentRate,
    long estimatedMinutes,
    LocationData location
) {
}
