package dev.ellan.botrental.common;

import java.util.List;
import java.util.UUID;

public final class ApiMessages {
   private ApiMessages() {
   }

   public record ActionResponse(boolean success, String code, String message, LeaseView lease, long refundableCoins) {
      public static ApiMessages.ActionResponse ok(String var0, LeaseView var1) {
         return new ApiMessages.ActionResponse(true, "OK", var0, var1, 0L);
      }

      public static ApiMessages.ActionResponse fail(String var0, String var1) {
         return new ApiMessages.ActionResponse(false, var0, var1, null, 0L);
      }

      public static ApiMessages.ActionResponse failWithRefund(String var0, String var1, long var2) {
         return new ApiMessages.ActionResponse(false, var0, var1, null, var2);
      }
   }

   public record BridgeResponse(boolean success, String code, String message) {
      public static ApiMessages.BridgeResponse ok(String var0) {
         return new ApiMessages.BridgeResponse(true, "OK", var0);
      }

      public static ApiMessages.BridgeResponse fail(String var0, String var1) {
         return new ApiMessages.BridgeResponse(false, var0, var1);
      }
   }

   /**
    * Request to start a lease. {@code plan} is null/"HOURLY" for the original per-minute rental; for "PACKAGE"
    * {@code tier}, {@code days} and {@code combat} select the prepaid long-term plan. The addon recomputes the price
    * itself and only accepts the request when {@code reserveCoins} equals it.
    */
   public record CreateRequest(
      UUID requestId, UUID ownerUuid, String ownerName, long reserveCoins, LocationData location,
      String plan, String tier, int days, boolean combat
   ) {
      public CreateRequest(UUID requestId, UUID ownerUuid, String ownerName, long reserveCoins, LocationData location) {
         this(requestId, ownerUuid, ownerName, reserveCoins, location, null, null, 0, false);
      }

      public boolean isPackage() {
         return LeaseView.PACKAGE.equalsIgnoreCase(plan);
      }
   }

   public record OwnerSlotRequest(UUID requestId, UUID ownerUuid, int slot) {
   }

   /** Ask the backend to put a bot at a location. {@code tier} (empty for hourly) selects its chunk-load radius. */
   public record PlaceRequest(
      UUID leaseId, String botUsername, UUID ownerUuid, LocationData location, String tier, boolean combat,
      int simulationDistance, int viewDistance
   ) {
      public PlaceRequest(UUID leaseId, String botUsername, UUID ownerUuid, LocationData location) {
         this(leaseId, botUsername, ownerUuid, location, "", false, 0, 0);
      }
   }

   /** One purchasable long-term package, as computed by the addon (the single source of truth for prices). */
   public record PackageOffer(
      String tier, String tierName, int days, long price, long combatPrice, int simulationDistance, int viewDistance
   ) {
   }

   public record RefundRequest(UUID refundId, UUID leaseId, UUID ownerUuid, long coins, String reason) {
   }

   public record RelocateRequest(UUID requestId, UUID ownerUuid, int slot, LocationData location) {
   }

   public record StatusRequest(UUID ownerUuid) {
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
      List<LeaseView> leases,
      int packagePoolSize,
      int packagePoolAvailable,
      int maxPackagesPerPlayer,
      List<PackageOffer> offers
   ) {
      public StatusResponse(
         boolean success, String code, String message, int poolSize, int poolAvailable, int maxPerPlayer,
         long onlineRate, long offlineRate, List<LeaseView> leases
      ) {
         this(success, code, message, poolSize, poolAvailable, maxPerPlayer, onlineRate, offlineRate, leases,
            0, 0, 0, List.of());
      }
   }

   public record TopUpRequest(UUID requestId, UUID ownerUuid, int slot, long coins) {
   }
}
