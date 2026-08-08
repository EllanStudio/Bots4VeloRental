package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.ApiMessages.BridgeResponse;
import dev.ellan.botrental.common.ApiMessages.PlaceRequest;
import dev.ellan.botrental.common.ApiMessages.RefundRequest;
import dev.ellan.botrental.common.LocationData;
import dev.ellan.botrental.common.SignedHttpClient;

import java.util.UUID;

final class HttpRentalBridge implements RentalBridge {
    private final SignedHttpClient client;

    HttpRentalBridge(RentalConfig config) {
        client = new SignedHttpClient(config.bridgeBaseUrl(), config.sharedSecret());
    }

    @Override
    public boolean place(UUID leaseId, String botUsername, UUID ownerUuid, LocationData location) throws Exception {
        BridgeResponse response = client.post("/v1/bridge/place",
            new PlaceRequest(leaseId, botUsername, ownerUuid, location), BridgeResponse.class);
        return response.success();
    }

    @Override
    public boolean refund(UUID refundId, UUID leaseId, UUID ownerUuid, long coins, String reason) throws Exception {
        BridgeResponse response = client.post("/v1/bridge/refund",
            new RefundRequest(refundId, leaseId, ownerUuid, coins, reason), BridgeResponse.class);
        return response.success();
    }
}
