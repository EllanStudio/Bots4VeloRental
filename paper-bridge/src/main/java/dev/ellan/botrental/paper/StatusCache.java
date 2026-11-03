package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.ApiMessages;
import dev.ellan.botrental.common.LeaseView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class StatusCache {
   private final Map<UUID, StatusCache.Entry> entries = new ConcurrentHashMap<>();

   void put(UUID var1, ApiMessages.StatusResponse var2) {
      this.entries.put(var1, new StatusCache.Entry(var2, System.currentTimeMillis()));
   }

   Optional<ApiMessages.StatusResponse> get(UUID var1) {
      StatusCache.Entry var2 = this.entries.get(var1);
      return var2 == null ? Optional.empty() : Optional.of(var2.status());
   }

   boolean needsRefresh(UUID var1, long var2) {
      StatusCache.Entry var4 = this.entries.get(var1);
      return var4 == null || System.currentTimeMillis() - var4.loadedAt() >= var2;
   }

   void updateLease(UUID var1, LeaseView var2) {
      if (var2 != null) {
         this.entries.computeIfPresent(var1, (var1x, var2x) -> {
            ArrayList<LeaseView> var3 = new ArrayList<>(var2x.status().leases());
            var3.removeIf(var1xx -> var1xx.slot() == var2.slot());
            var3.add(var2);
            var3.sort(Comparator.comparingInt(LeaseView::slot));
            return new StatusCache.Entry(copyWithLeases(var2x.status(), var3), System.currentTimeMillis());
         });
      }
   }

   void removeLease(UUID var1, int var2) {
      this.entries.computeIfPresent(var1, (var1x, var2x) -> {
         ArrayList<LeaseView> var3 = new ArrayList<>(var2x.status().leases());
         var3.removeIf(var1xx -> var1xx.slot() == var2);
         return new StatusCache.Entry(copyWithLeases(var2x.status(), var3), System.currentTimeMillis());
      });
   }

   void remove(UUID var1) {
      this.entries.remove(var1);
   }

   private static ApiMessages.StatusResponse copyWithLeases(ApiMessages.StatusResponse var0, List<LeaseView> var1) {
      List<LeaseView> var2 = List.copyOf(var1);
      return new ApiMessages.StatusResponse(
         var0.success(),
         var0.code(),
         var0.message(),
         var0.poolSize(),
         Math.max(0, var0.poolSize() - var2.size()),
         var0.maxPerPlayer(),
         var0.onlineRate(),
         var0.offlineRate(),
         var2
      );
   }

   private record Entry(ApiMessages.StatusResponse status, long loadedAt) {
   }
}
