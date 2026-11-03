package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.ApiMessages;
import dev.ellan.botrental.common.LeaseView;
import dev.nulli0n.vbot.addon.api.AddonBotEvent;
import dev.nulli0n.vbot.addon.api.AddonBotService;
import dev.nulli0n.vbot.addon.api.AddonBotSnapshot;
import dev.nulli0n.vbot.addon.api.AddonBotState;
import dev.nulli0n.vbot.addon.api.AddonLogger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Map.Entry;

final class LeaseManager implements AutoCloseable {
   private static final String PREFIX = "[\u6302\u673a\u673a\u5668\u4eba] ";
   private final RentalConfig config;
   private final AddonBotService bots;
   private final RentalBridge bridge;
   private final LeaseStore store;
   private final AddonLogger logger;
   private final Clock clock;
   private final Map<UUID, Lease> leases = new HashMap<>();
   private final Map<UUID, ApiMessages.ActionResponse> recentRequests = new LinkedHashMap<UUID, ApiMessages.ActionResponse>() {
      @Override
      protected boolean removeEldestEntry(Entry<UUID, ApiMessages.ActionResponse> var1) {
         return this.size() > 2000;
      }
   };

   LeaseManager(RentalConfig var1, AddonBotService var2, RentalBridge var3, LeaseStore var4, AddonLogger var5) throws Exception {
      this(var1, var2, var3, var4, var5, Clock.systemUTC());
   }

   LeaseManager(RentalConfig var1, AddonBotService var2, RentalBridge var3, LeaseStore var4, AddonLogger var5, Clock var6) throws Exception {
      this.config = var1;
      this.bots = var2;
      this.bridge = var3;
      this.store = var4;
      this.logger = var5;
      this.clock = var6;

      for (Lease var8 : var4.loadOpen()) {
         this.leases.put(var8.id, var8);
      }
   }

   synchronized void start() {
      long var1 = this.now();

      for (Lease var4 : this.leases.values()) {
         if (var4.state != LeaseState.REFUND_PENDING) {
            var4.state = LeaseState.STARTING;
            var4.startDeadline = var1 + this.config.startupTimeoutSeconds() * 1000L;
            var4.lastActionAt = 0L;
            this.save(var4);
         }
      }

      this.tick();
   }

   synchronized ApiMessages.ActionResponse create(ApiMessages.CreateRequest var1) {
      if (var1 != null && var1.requestId() != null && this.recentRequests.containsKey(var1.requestId())) {
         return this.recentRequests.get(var1.requestId());
      }

      ApiMessages.ActionResponse var2 = this.createInternal(var1);
      this.remember(var1 == null ? null : var1.requestId(), var2);
      return var2;
   }

   private ApiMessages.ActionResponse createInternal(ApiMessages.CreateRequest var1) {
      if (var1 != null
         && var1.requestId() != null
         && var1.ownerUuid() != null
         && var1.location() != null
         && var1.ownerName() != null
         && !var1.ownerName().isBlank()) {
         if (!this.config.allows(var1.location().server())) {
            return ApiMessages.ActionResponse.failWithRefund(
               "SERVER_NOT_ALLOWED", "\u5f53\u524d\u670d\u52a1\u5668\u4e0d\u652f\u6301\u79df\u8d41\u673a\u5668\u4eba\u3002", Math.max(0L, var1.reserveCoins())
            );
         }

         if (var1.reserveCoins() < this.config.onlineRate()) {
            return ApiMessages.ActionResponse.failWithRefund(
               "RESERVE_TOO_SMALL", "\u9884\u5b58\u91d1\u989d\u4e0d\u8db3\u4e00\u5206\u949f\u5728\u7ebf\u8d39\u7528\u3002", Math.max(0L, var1.reserveCoins())
            );
         }

         List var2 = this.ownerLeases(var1.ownerUuid());
         if (var2.size() >= this.config.maxPerPlayer()) {
            return ApiMessages.ActionResponse.failWithRefund(
               "OWNER_LIMIT", "\u4f60\u5df2\u7ecf\u79df\u6ee1 " + this.config.maxPerPlayer() + " \u4e2a\u673a\u5668\u4eba\u3002", var1.reserveCoins()
            );
         }

         HashSet var3 = new HashSet();
         this.leases.values().forEach(var1x -> var3.add(var1x.botId.toLowerCase()));
         Optional var4 = this.config
            .pool()
            .stream()
            .filter(var1x -> !var3.contains(var1x.toLowerCase()))
            .filter(var1x -> this.bots.bot(var1x).isPresent())
            .findFirst();
         if (var4.isEmpty()) {
            return ApiMessages.ActionResponse.failWithRefund(
               "POOL_FULL", "\u673a\u5668\u4eba\u6c60\u6682\u65f6\u6ca1\u6709\u7a7a\u4f4d\u3002", var1.reserveCoins()
            );
         }

         String var5 = (String)var4.get();
         AddonBotSnapshot var6 = (AddonBotSnapshot)this.bots.bot(var5).orElseThrow();
         int var7 = this.firstFreeSlot(var2);
         long var8 = this.now();
         Lease var10 = new Lease(
            UUID.randomUUID(),
            var1.ownerUuid(),
            var1.ownerName().trim(),
            var7,
            var6.id(),
            var6.username(),
            var1.reserveCoins(),
            0L,
            LeaseState.STARTING,
            var1.location(),
            false,
            var8,
            0L,
            var8 + this.config.startupTimeoutSeconds() * 1000L,
            0L,
            null,
            ""
         );

         try {
            this.store.save(var10);
            this.leases.put(var10.id, var10);
            boolean var11 = this.bots.start(var10.botId);
            boolean var12 = this.bots.bot(var10.botId).<AddonBotState>map(AddonBotSnapshot::state).filter(var0 -> var0 == AddonBotState.PLAY).isPresent();
            if (!var11 && !var12) {
               this.leases.remove(var10.id);
               this.store.delete(var10.id);
               return ApiMessages.ActionResponse.failWithRefund(
                  "BOT_START_FAILED", "\u673a\u5668\u4eba\u6682\u65f6\u65e0\u6cd5\u542f\u52a8\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002", var1.reserveCoins()
               );
            } else {
               var10.lastActionAt = var8;
               this.save(var10);
               return ApiMessages.ActionResponse.ok(
                  "\u79df\u8d41\u5df2\u53d7\u7406\uff0c\u673a\u5668\u4eba\u6b63\u5728\u524d\u5f80\u6307\u5b9a\u4f4d\u7f6e\u3002", this.view(var10)
               );
            }
         } catch (Exception var14) {
            this.logger.error("Could not create lease", var14);
            this.leases.remove(var10.id);

            try {
               this.store.delete(var10.id);
            } catch (Exception var13) {
               this.logger.error("Could not clean up failed lease", var13);
            }

            return ApiMessages.ActionResponse.failWithRefund(
               "INTERNAL_ERROR", "\u79df\u8d41\u521b\u5efa\u5931\u8d25\uff0c\u91d1\u5e01\u5c06\u539f\u8def\u9000\u56de\u3002", var1.reserveCoins()
            );
         }
      } else {
         return ApiMessages.ActionResponse.fail("BAD_REQUEST", "\u79df\u8d41\u4fe1\u606f\u4e0d\u5b8c\u6574\u3002");
      }
   }

   synchronized ApiMessages.ActionResponse cancel(ApiMessages.OwnerSlotRequest var1) {
      if (var1 != null && var1.requestId() != null && this.recentRequests.containsKey(var1.requestId())) {
         return this.recentRequests.get(var1.requestId());
      } else {
         Lease var2 = this.ownedSlot(var1);
         if (var2 == null) {
            ApiMessages.ActionResponse var6 = ApiMessages.ActionResponse.fail("NOT_FOUND", "\u6ca1\u6709\u627e\u5230\u8fd9\u4e2a\u79df\u8d41\u4f4d\u3002");
            this.remember(var1 == null ? null : var1.requestId(), var6);
            return var6;
         } else {
            long var3 = var2.reserveCoins;
            this.beginEnd(var2, "MANUAL_CANCEL", "\u79df\u8d41\u5df2\u53d6\u6d88\uff0c\u5269\u4f59\u91d1\u5e01\u6b63\u5728\u9000\u56de\u3002");
            ApiMessages.ActionResponse var5 = new ApiMessages.ActionResponse(true, "OK", "\u79df\u8d41\u5df2\u53d6\u6d88\u3002", null, var3);
            this.remember(var1.requestId(), var5);
            return var5;
         }
      }
   }

   synchronized ApiMessages.ActionResponse topUp(ApiMessages.TopUpRequest var1) {
      if (var1 != null && var1.requestId() != null && this.recentRequests.containsKey(var1.requestId())) {
         return this.recentRequests.get(var1.requestId());
      }

      ApiMessages.ActionResponse var2 = this.topUpInternal(var1);
      this.remember(var1 == null ? null : var1.requestId(), var2);
      return var2;
   }

   private ApiMessages.ActionResponse topUpInternal(ApiMessages.TopUpRequest var1) {
      Lease var2 = this.ownedSlot(var1 == null ? null : new ApiMessages.OwnerSlotRequest(var1.requestId(), var1.ownerUuid(), var1.slot()));
      if (var2 != null && var2.state != LeaseState.REFUND_PENDING) {
         if (var1.coins() <= 0L) {
            return ApiMessages.ActionResponse.fail("BAD_AMOUNT", "\u5145\u503c\u91d1\u989d\u5fc5\u987b\u5927\u4e8e 0\u3002");
         }

         try {
            var2.reserveCoins = Math.addExact(var2.reserveCoins, var1.coins());
            this.save(var2);
            return ApiMessages.ActionResponse.ok("\u5df2\u8865\u5145 " + var1.coins() + " \u827e\u5c14\u5c9a\u91d1\u5e01\u3002", this.view(var2));
         } catch (ArithmeticException var4) {
            return ApiMessages.ActionResponse.failWithRefund("AMOUNT_TOO_LARGE", "\u5145\u503c\u91d1\u989d\u8fc7\u5927\u3002", var1.coins());
         }
      } else {
         return ApiMessages.ActionResponse.failWithRefund(
            "NOT_FOUND", "\u6ca1\u6709\u627e\u5230\u53ef\u5145\u503c\u7684\u79df\u8d41\u4f4d\u3002", var1 == null ? 0L : Math.max(0L, var1.coins())
         );
      }
   }

   synchronized ApiMessages.ActionResponse relocate(ApiMessages.RelocateRequest var1) {
      if (var1 != null && var1.requestId() != null && this.recentRequests.containsKey(var1.requestId())) {
         return this.recentRequests.get(var1.requestId());
      }

      ApiMessages.ActionResponse var2 = this.relocateInternal(var1);
      this.remember(var1 == null ? null : var1.requestId(), var2);
      return var2;
   }

   private ApiMessages.ActionResponse relocateInternal(ApiMessages.RelocateRequest var1) {
      Lease var2 = this.ownedSlot(var1 == null ? null : new ApiMessages.OwnerSlotRequest(var1.requestId(), var1.ownerUuid(), var1.slot()));
      if (var2 == null || var1.location() == null) {
         return ApiMessages.ActionResponse.fail("NOT_FOUND", "\u6ca1\u6709\u627e\u5230\u8fd9\u4e2a\u79df\u8d41\u4f4d\u3002");
      }

      if (var2.state == LeaseState.REFUND_PENDING) {
         return ApiMessages.ActionResponse.fail("NOT_FOUND", "\u8fd9\u4e2a\u79df\u8d41\u5df2\u7ecf\u7ed3\u675f\u3002");
      }

      if (!this.config.allows(var1.location().server())) {
         return ApiMessages.ActionResponse.fail("SERVER_NOT_ALLOWED", "\u5f53\u524d\u670d\u52a1\u5668\u4e0d\u652f\u6301\u79df\u8d41\u673a\u5668\u4eba\u3002");
      }

      if (!var1.location().server().equals(var2.location.server())) {
         var2.location = var1.location();
         var2.state = LeaseState.STARTING;
         var2.startDeadline = this.now() + this.config.startupTimeoutSeconds() * 1000L;
         var2.lastActionAt = 0L;
         var2.nextChargeAt = Math.max(var2.nextChargeAt, this.now() + this.config.billingPeriodSeconds() * 1000L);
         this.save(var2);
         return ApiMessages.ActionResponse.ok("\u673a\u5668\u4eba\u6b63\u5728\u524d\u5f80\u65b0\u7684\u670d\u52a1\u5668\u3002", this.view(var2));
      }

      try {
         if (var2.state == LeaseState.ACTIVE && !this.bridge.place(var2.id, var2.botUsername, var2.ownerUuid, var1.location())) {
            return ApiMessages.ActionResponse.fail("PLACE_FAILED", "\u673a\u5668\u4eba\u6682\u65f6\u65e0\u6cd5\u79fb\u52a8\u5230\u65b0\u4f4d\u7f6e\u3002");
         }

         var2.location = var1.location();
         this.save(var2);
         return ApiMessages.ActionResponse.ok("\u673a\u5668\u4eba\u4f4d\u7f6e\u5df2\u66f4\u65b0\u3002", this.view(var2));
      } catch (Exception var4) {
         this.logger.error("Could not relocate lease " + var2.id, var4);
         return ApiMessages.ActionResponse.fail("BRIDGE_UNAVAILABLE", "\u4f4d\u7f6e\u670d\u52a1\u6682\u65f6\u4e0d\u53ef\u7528\u3002");
      }
   }

   synchronized ApiMessages.StatusResponse status(ApiMessages.StatusRequest var1) {
      if (var1 != null && var1.ownerUuid() != null) {
         List var2 = this.ownerLeases(var1.ownerUuid()).stream().sorted(Comparator.comparingInt(var0 -> var0.slot)).map(this::view).toList();
         return new ApiMessages.StatusResponse(
            true,
            "OK",
            "OK",
            this.config.pool().size(),
            this.poolAvailable(),
            this.config.maxPerPlayer(),
            this.config.onlineRate(),
            this.config.offlineRate(),
            var2
         );
      } else {
         return new ApiMessages.StatusResponse(
            false,
            "BAD_REQUEST",
            "\u7f3a\u5c11\u73a9\u5bb6\u4fe1\u606f\u3002",
            this.config.pool().size(),
            this.poolAvailable(),
            this.config.maxPerPlayer(),
            this.config.onlineRate(),
            this.config.offlineRate(),
            List.of()
         );
      }
   }

   synchronized void onBotEvent(AddonBotEvent var1) {
      if (var1 != null) {
         Lease var2 = this.leases.values().stream().filter(var1x -> var1x.botId.equalsIgnoreCase(var1.botId())).findFirst().orElse(null);
         if (var2 != null && var2.state != LeaseState.REFUND_PENDING && ("DISCONNECTED".equals(var1.type()) || "STOPPED".equals(var1.type()))) {
            var2.state = LeaseState.STARTING;
            var2.startDeadline = this.now() + this.config.startupTimeoutSeconds() * 1000L;
            var2.lastActionAt = 0L;
            var2.nextChargeAt = Math.max(var2.nextChargeAt, this.now() + this.config.billingPeriodSeconds() * 1000L);
            this.save(var2);
         }
      }
   }

   synchronized void tick() {
      this.reconcileUnusedPool();

      for (Lease var2 : new ArrayList<>(this.leases.values())) {
         try {
            switch (var2.state) {
               case STARTING:
                  this.tickStarting(var2);
                  break;
               case ACTIVE:
                  this.tickActive(var2);
                  break;
               case REFUND_PENDING:
                  this.tryRefund(var2);
                  break;
               case ENDED:
                  this.leases.remove(var2.id);
            }
         } catch (Exception var4) {
            this.logger.error("Lease tick failed for " + var2.id, var4);
         }
      }
   }

   private void tickStarting(Lease var1) throws Exception {
      long var2 = this.now();
      if (var2 >= var1.startDeadline) {
         this.beginEnd(var1, "START_TIMEOUT", "\u673a\u5668\u4eba\u542f\u52a8\u8d85\u65f6\uff0c\u9884\u5b58\u91d1\u5e01\u5c06\u5168\u90e8\u9000\u56de\u3002");
      } else {
         AddonBotSnapshot var4 = (AddonBotSnapshot)this.bots.bot(var1.botId).orElse(null);
         if (var4 == null) {
            this.beginEnd(var1, "BOT_MISSING", "\u673a\u5668\u4eba\u914d\u7f6e\u7f3a\u5931\uff0c\u9884\u5b58\u91d1\u5e01\u5c06\u5168\u90e8\u9000\u56de\u3002");
         } else if (!this.config.allows(var1.location.server())) {
            this.beginEnd(
               var1,
               "SERVER_REMOVED",
               "\u8be5\u670d\u52a1\u5668\u5df2\u505c\u6b62\u673a\u5668\u4eba\u79df\u8d41\uff0c\u9884\u5b58\u91d1\u5e01\u5c06\u5168\u90e8\u9000\u56de\u3002"
            );
         } else if (var4.state() == AddonBotState.PLAY) {
            String var5 = this.bots.currentServer(var1.botId).orElse("");
            if (!var5.equalsIgnoreCase(var1.location.server())) {
               if (var2 - var1.lastActionAt >= 5000L) {
                  this.bots.switchServer(var1.botId, var1.location.server());
                  var1.lastActionAt = var2;
                  this.save(var1);
               }
            } else if (this.bridge.place(var1.id, var1.botUsername, var1.ownerUuid, var1.location)) {
               this.activate(var1);
            }
         } else if (var2 - var1.lastActionAt >= 10000L) {
            if (var4.state() == AddonBotState.RECONNECT_WAIT || var4.state() == AddonBotState.FAILED) {
               this.bots.reconnect(var1.botId);
            } else if (var4.state() == AddonBotState.STOPPED) {
               this.bots.start(var1.botId);
            }

            var1.lastActionAt = var2;
            this.save(var1);
         }
      }
   }

   private void activate(Lease var1) {
      long var2 = this.now();
      if (!var1.chargedOnce) {
         long var4 = this.currentRate(var1);
         if (var1.reserveCoins < var4) {
            this.beginEnd(var1, "OUT_OF_FUNDS", "\u9884\u5b58\u91d1\u5e01\u4e0d\u8db3\uff0c\u673a\u5668\u4eba\u79df\u8d41\u5df2\u7ecf\u7ed3\u675f\u3002");
            return;
         }

         var1.reserveCoins -= var4;
         var1.spentCoins += var4;
         var1.chargedOnce = true;
      }

      var1.state = LeaseState.ACTIVE;
      var1.nextChargeAt = var2 + this.config.billingPeriodSeconds() * 1000L;
      var1.lastActionAt = var2;
      this.save(var1);
      this.bots
         .sendPlayerMessage(
            var1.ownerUuid, "[\u6302\u673a\u673a\u5668\u4eba] \u673a\u5668\u4eba " + var1.botUsername + " \u5df2\u5230\u8fbe\u6307\u5b9a\u4f4d\u7f6e\u3002"
         );
   }

   private void tickActive(Lease var1) {
      AddonBotSnapshot var2 = (AddonBotSnapshot)this.bots.bot(var1.botId).orElse(null);
      String var3 = this.bots.currentServer(var1.botId).orElse("");
      if (var2 == null || var2.state() != AddonBotState.PLAY || !var3.equalsIgnoreCase(var1.location.server())) {
         var1.state = LeaseState.STARTING;
         var1.startDeadline = this.now() + this.config.startupTimeoutSeconds() * 1000L;
         var1.lastActionAt = 0L;
         var1.nextChargeAt = this.now() + this.config.billingPeriodSeconds() * 1000L;
         this.save(var1);
      } else if (this.now() >= var1.nextChargeAt) {
         long var4 = this.currentRate(var1);
         if (var1.reserveCoins < var4) {
            this.beginEnd(var1, "OUT_OF_FUNDS", "\u9884\u5b58\u91d1\u5e01\u4e0d\u8db3\uff0c\u673a\u5668\u4eba\u79df\u8d41\u5df2\u7ecf\u7ed3\u675f\u3002");
         } else {
            var1.reserveCoins -= var4;
            var1.spentCoins += var4;
            var1.nextChargeAt = this.now() + this.config.billingPeriodSeconds() * 1000L;
            this.save(var1);
         }
      }
   }

   private void beginEnd(Lease var1, String var2, String var3) {
      var1.state = LeaseState.REFUND_PENDING;
      var1.endReason = var2;
      if (var1.refundId == null) {
         var1.refundId = UUID.randomUUID();
      }

      this.bots.stop(var1.botId);
      this.save(var1);
      this.bots.sendPlayerMessage(var1.ownerUuid, "[\u6302\u673a\u673a\u5668\u4eba] " + var3);
      this.tryRefund(var1);
   }

   private void tryRefund(Lease var1) {
      try {
         if (var1.reserveCoins > 0L && !this.bridge.refund(var1.location.server(), var1.refundId, var1.id, var1.ownerUuid, var1.reserveCoins, var1.endReason)) {
            return;
         }

         long var2 = var1.reserveCoins;
         var1.reserveCoins = 0L;
         var1.state = LeaseState.ENDED;
         this.save(var1);
         this.leases.remove(var1.id);
         if (var2 > 0L) {
            this.bots
               .sendPlayerMessage(var1.ownerUuid, "[\u6302\u673a\u673a\u5668\u4eba] \u5df2\u9000\u56de " + var2 + " \u679a\u827e\u5c14\u5c9a\u91d1\u5e01\u3002");
         }
      } catch (Exception var4) {
         this.logger.warn("Refund remains pending for " + var1.id + ": " + var4.getMessage());
      }
   }

   private void reconcileUnusedPool() {
      HashSet var1 = new HashSet();
      this.leases.values().forEach(var1x -> var1.add(var1x.botId.toLowerCase()));

      for (String var3 : this.config.pool()) {
         if (!var1.contains(var3.toLowerCase())) {
            this.bots
               .bot(var3)
               .filter(var0 -> var0.state() != AddonBotState.STOPPED && var0.state() != AddonBotState.STOPPING)
               .ifPresent(var1x -> this.bots.stop(var1x.id()));
         }
      }
   }

   private Lease ownedSlot(ApiMessages.OwnerSlotRequest var1) {
      return var1 != null && var1.ownerUuid() != null && var1.slot() >= 1 && var1.slot() <= this.config.maxPerPlayer()
         ? this.leases.values().stream().filter(var1x -> var1x.ownerUuid.equals(var1.ownerUuid()) && var1x.slot == var1.slot()).findFirst().orElse(null)
         : null;
   }

   private List<Lease> ownerLeases(UUID var1) {
      return this.leases.values().stream().filter(var1x -> var1x.ownerUuid.equals(var1) && var1x.state != LeaseState.ENDED).toList();
   }

   private int firstFreeSlot(List<Lease> var1) {
      HashSet var2 = new HashSet();
      var1.forEach(var1x -> var2.add(var1x.slot));

      for (int var3 = 1; var3 <= this.config.maxPerPlayer(); var3++) {
         if (!var2.contains(var3)) {
            return var3;
         }
      }

      throw new IllegalStateException("no free owner slot");
   }

   private int poolAvailable() {
      return Math.max(0, this.config.pool().size() - this.leases.size());
   }

   private LeaseView view(Lease var1) {
      long var2 = this.currentRate(var1);
      return new LeaseView(
         var1.id,
         var1.slot,
         var1.botId,
         var1.botUsername,
         var1.state.name(),
         var1.reserveCoins,
         var1.spentCoins,
         var2,
         var2 <= 0L ? 0L : var1.reserveCoins / var2,
         var1.location
      );
   }

   private long currentRate(Lease var1) {
      return this.bots.isPlayerOnline(var1.ownerUuid) ? this.config.onlineRate() : this.config.offlineRate();
   }

   private void save(Lease var1) {
      try {
         this.store.save(var1);
      } catch (Exception var3) {
         throw new IllegalStateException("could not persist lease " + var1.id, var3);
      }
   }

   private void remember(UUID var1, ApiMessages.ActionResponse var2) {
      if (var1 != null) {
         this.recentRequests.put(var1, var2);
      }
   }

   private long now() {
      return this.clock.millis();
   }

   @Override
   public synchronized void close() throws Exception {
      this.store.close();
   }
}
