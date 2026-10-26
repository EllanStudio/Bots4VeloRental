package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.LocationData;

import java.util.UUID;

interface RentalBridge {
    /** Places the bot through the bridge of {@code location.server()}. */
    boolean place(UUID leaseId, String botUsername, UUID ownerUuid, LocationData location) throws Exception;

    /** Refunds through the bridge of {@code server}; every bridge shares the same economy database. */
    boolean refund(String server, UUID refundId, UUID leaseId, UUID ownerUuid, long coins, String reason)
        throws Exception;
}
