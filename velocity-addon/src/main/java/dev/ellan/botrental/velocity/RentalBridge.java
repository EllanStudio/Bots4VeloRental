package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.LocationData;

import java.util.UUID;

interface RentalBridge {
    boolean place(UUID leaseId, String botUsername, UUID ownerUuid, LocationData location) throws Exception;

    boolean refund(UUID refundId, UUID leaseId, UUID ownerUuid, long coins, String reason) throws Exception;
}
