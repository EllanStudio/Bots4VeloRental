package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.ApiMessages.BridgeResponse;
import dev.ellan.botrental.common.ApiMessages.PlaceRequest;
import dev.ellan.botrental.common.ApiMessages.RefundRequest;
import dev.ellan.botrental.common.LocationData;
import dev.ellan.botrental.common.SignedHttpClient;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class HttpRentalBridge implements RentalBridge {
    private final Map<String, SignedHttpClient> clients = new LinkedHashMap<>();

    HttpRentalBridge(RentalConfig config) {
        config.bridges().forEach((server, baseUrl) ->
            clients.put(server, new SignedHttpClient(baseUrl, config.sharedSecret())));
    }

    @Override
    public boolean place(UUID leaseId, String botUsername, UUID ownerUuid, LocationData location) throws Exception {
        SignedHttpClient client = clients.get(location.server());
        if (client == null) {
            return false;
        }
        BridgeResponse response = client.post("/v1/bridge/place",
            new PlaceRequest(leaseId, botUsername, ownerUuid, location), BridgeResponse.class);
        return response.success();
    }

    @Override
    public boolean refund(String server, UUID refundId, UUID leaseId, UUID ownerUuid, long coins, String reason)
        throws Exception {
        // A lease whose server was later removed from the config must still be
        // refundable; the economy is shared, so any configured bridge will do.
        SignedHttpClient client = clients.getOrDefault(server == null ? "" : server.toLowerCase(Locale.ROOT),
            clients.values().iterator().next());
        BridgeResponse response = client.post("/v1/bridge/refund",
            new RefundRequest(refundId, leaseId, ownerUuid, coins, reason), BridgeResponse.class);
        return response.success();
    }
}
