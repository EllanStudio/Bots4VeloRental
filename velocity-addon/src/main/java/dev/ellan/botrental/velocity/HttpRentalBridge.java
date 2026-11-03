package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.ApiMessages;
import dev.ellan.botrental.common.LocationData;
import dev.ellan.botrental.common.SignedHttpClient;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class HttpRentalBridge implements RentalBridge {
   private final Map<String, SignedHttpClient> clients = new LinkedHashMap<>();

   HttpRentalBridge(RentalConfig var1) {
      var1.bridges().forEach((var2, var3) -> this.clients.put(var2, new SignedHttpClient(var3, var1.sharedSecret())));
   }

   @Override
   public boolean place(UUID var1, String var2, UUID var3, LocationData var4, String tier, boolean combat) throws Exception {
      SignedHttpClient var5 = this.clients.get(var4.server());
      if (var5 == null) {
         return false;
      }

      ApiMessages.BridgeResponse var6 = var5.post("/v1/bridge/place", new ApiMessages.PlaceRequest(var1, var2, var3, var4, tier, combat), ApiMessages.BridgeResponse.class);
      return var6.success();
   }

   @Override
   public boolean refund(String var1, UUID var2, UUID var3, UUID var4, long var5, String var7) throws Exception {
      SignedHttpClient var8 = this.clients.getOrDefault(var1 == null ? "" : var1.toLowerCase(Locale.ROOT), this.clients.values().iterator().next());
      ApiMessages.BridgeResponse var9 = var8.post(
         "/v1/bridge/refund", new ApiMessages.RefundRequest(var2, var3, var4, var5, var7), ApiMessages.BridgeResponse.class
      );
      return var9.success();
   }
}
