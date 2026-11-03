package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.ApiMessages;
import dev.ellan.botrental.common.LocationData;
import dev.ellan.botrental.common.SignedHttpClient;
import java.util.UUID;

final class AddonClient {
   private final SignedHttpClient client;

   AddonClient(PaperRentalConfig var1) {
      this.client = new SignedHttpClient(var1.addonBaseUrl(), var1.sharedSecret());
   }

   ApiMessages.StatusResponse status(UUID var1) throws Exception {
      return this.post("/v1/leases/status", new ApiMessages.StatusRequest(var1), ApiMessages.StatusResponse.class);
   }

   ApiMessages.ActionResponse create(UUID var1, String var2, long var3, LocationData var5) throws Exception {
      ApiMessages.CreateRequest var6 = new ApiMessages.CreateRequest(UUID.randomUUID(), var1, var2, var3, var5);
      return this.post("/v1/leases/create", var6, ApiMessages.ActionResponse.class);
   }

   ApiMessages.ActionResponse cancel(UUID var1, int var2) throws Exception {
      ApiMessages.OwnerSlotRequest var3 = new ApiMessages.OwnerSlotRequest(UUID.randomUUID(), var1, var2);
      return this.post("/v1/leases/cancel", var3, ApiMessages.ActionResponse.class);
   }

   ApiMessages.ActionResponse topUp(UUID var1, int var2, long var3) throws Exception {
      ApiMessages.TopUpRequest var5 = new ApiMessages.TopUpRequest(UUID.randomUUID(), var1, var2, var3);
      return this.post("/v1/leases/topup", var5, ApiMessages.ActionResponse.class);
   }

   ApiMessages.ActionResponse relocate(UUID var1, int var2, LocationData var3) throws Exception {
      ApiMessages.RelocateRequest var4 = new ApiMessages.RelocateRequest(UUID.randomUUID(), var1, var2, var3);
      return this.post("/v1/leases/relocate", var4, ApiMessages.ActionResponse.class);
   }

   private <T> T post(String var1, Object var2, Class<T> var3) throws Exception {
      try {
         return this.client.post(var1, var2, var3);
      } catch (Exception var7) {
         Thread.sleep(150L);

         try {
            return this.client.post(var1, var2, var3);
         } catch (Exception var6) {
            var6.addSuppressed(var7);
            throw var6;
         }
      }
   }
}
