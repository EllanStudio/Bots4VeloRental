package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.ApiMessages.ActionResponse;
import dev.ellan.botrental.common.ApiMessages.CreateRequest;
import dev.ellan.botrental.common.ApiMessages.OwnerSlotRequest;
import dev.ellan.botrental.common.ApiMessages.RelocateRequest;
import dev.ellan.botrental.common.ApiMessages.StatusRequest;
import dev.ellan.botrental.common.ApiMessages.StatusResponse;
import dev.ellan.botrental.common.ApiMessages.TopUpRequest;
import dev.ellan.botrental.common.LocationData;
import dev.ellan.botrental.common.SignedHttpClient;

import java.util.UUID;

final class AddonClient {
    private final SignedHttpClient client;

    AddonClient(PaperRentalConfig config) {
        client = new SignedHttpClient(config.addonBaseUrl(), config.sharedSecret());
    }

    StatusResponse status(UUID owner) throws Exception {
        return post("/v1/leases/status", new StatusRequest(owner), StatusResponse.class);
    }

    ActionResponse create(UUID owner, String name, long reserve, LocationData location) throws Exception {
        CreateRequest request = new CreateRequest(UUID.randomUUID(), owner, name, reserve, location);
        return post("/v1/leases/create", request, ActionResponse.class);
    }

    ActionResponse cancel(UUID owner, int slot) throws Exception {
        OwnerSlotRequest request = new OwnerSlotRequest(UUID.randomUUID(), owner, slot);
        return post("/v1/leases/cancel", request, ActionResponse.class);
    }

    ActionResponse topUp(UUID owner, int slot, long coins) throws Exception {
        TopUpRequest request = new TopUpRequest(UUID.randomUUID(), owner, slot, coins);
        return post("/v1/leases/topup", request, ActionResponse.class);
    }

    ActionResponse relocate(UUID owner, int slot, LocationData location) throws Exception {
        RelocateRequest request = new RelocateRequest(UUID.randomUUID(), owner, slot, location);
        return post("/v1/leases/relocate", request, ActionResponse.class);
    }

    private <T> T post(String path, Object request, Class<T> responseType) throws Exception {
        try {
            return client.post(path, request, responseType);
        }
        catch (Exception firstFailure) {
            Thread.sleep(150);
            try {
                return client.post(path, request, responseType);
            }
            catch (Exception secondFailure) {
                secondFailure.addSuppressed(firstFailure);
                throw secondFailure;
            }
        }
    }
}
