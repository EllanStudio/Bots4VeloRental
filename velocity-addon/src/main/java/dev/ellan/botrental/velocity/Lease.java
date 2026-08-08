package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.LocationData;

import java.util.UUID;

final class Lease {
    final UUID id;
    final UUID ownerUuid;
    final String ownerName;
    final int slot;
    final String botId;
    final String botUsername;
    final long createdAt;
    long reserveCoins;
    long spentCoins;
    LeaseState state;
    LocationData location;
    boolean chargedOnce;
    long nextChargeAt;
    long startDeadline;
    long lastActionAt;
    UUID refundId;
    String endReason;

    Lease(UUID id, UUID ownerUuid, String ownerName, int slot, String botId, String botUsername,
          long reserveCoins, long spentCoins, LeaseState state, LocationData location,
          boolean chargedOnce, long createdAt, long nextChargeAt, long startDeadline,
          long lastActionAt, UUID refundId, String endReason) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.slot = slot;
        this.botId = botId;
        this.botUsername = botUsername;
        this.reserveCoins = reserveCoins;
        this.spentCoins = spentCoins;
        this.state = state;
        this.location = location;
        this.chargedOnce = chargedOnce;
        this.createdAt = createdAt;
        this.nextChargeAt = nextChargeAt;
        this.startDeadline = startDeadline;
        this.lastActionAt = lastActionAt;
        this.refundId = refundId;
        this.endReason = endReason == null ? "" : endReason;
    }
}
