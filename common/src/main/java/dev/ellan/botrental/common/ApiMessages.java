package dev.ellan.botrental.common;

import java.util.List;
import java.util.UUID;

public final class ApiMessages {
    private ApiMessages() {
    }

    public record CreateRequest(
        UUID requestId,
        UUID ownerUuid,
        String ownerName,
        long reserveCoins,
        LocationData location
    ) {
    }

    public record OwnerSlotRequest(UUID requestId, UUID ownerUuid, int slot) {
    }

    public record TopUpRequest(UUID requestId, UUID ownerUuid, int slot, long coins) {
    }

    public record RelocateRequest(UUID requestId, UUID ownerUuid, int slot, LocationData location) {
    }

    public record StatusRequest(UUID ownerUuid) {
    }

    public record ActionResponse(boolean success, String code, String message, LeaseView lease,
                                 long refundableCoins) {
        public static ActionResponse ok(String message, LeaseView lease) {
            return new ActionResponse(true, "OK", message, lease, 0);
        }

        public static ActionResponse fail(String code, String message) {
            return new ActionResponse(false, code, message, null, 0);
        }

        public static ActionResponse failWithRefund(String code, String message, long refundableCoins) {
            return new ActionResponse(false, code, message, null, refundableCoins);
        }
    }

    public record StatusResponse(
        boolean success,
        String code,
        String message,
        int poolSize,
        int poolAvailable,
        int maxPerPlayer,
        long onlineRate,
        long offlineRate,
        List<LeaseView> leases
    ) {
    }

    public record PlaceRequest(UUID leaseId, String botUsername, UUID ownerUuid, LocationData location) {
    }

    public record RefundRequest(UUID refundId, UUID leaseId, UUID ownerUuid, long coins, String reason) {
    }

    public record BridgeResponse(boolean success, String code, String message) {
        public static BridgeResponse ok(String message) {
            return new BridgeResponse(true, "OK", message);
        }

        public static BridgeResponse fail(String code, String message) {
            return new BridgeResponse(false, code, message);
        }
    }
}
