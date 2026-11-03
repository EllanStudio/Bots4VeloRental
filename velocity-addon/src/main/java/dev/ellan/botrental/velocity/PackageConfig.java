package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.ApiMessages.PackageOffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Prepaid long-term "package" rentals. The addon owns the price table; the paper bridge only displays what the
 * addon reports and charges exactly that amount, and the addon re-validates it on every create request.
 */
record PackageConfig(
   boolean enabled,
   List<String> pool,
   int maxPerPlayer,
   int maxCombat,
   List<String> allowedServers,
   int refundPercent,
   int combatSurchargePercent,
   Map<Integer, Integer> chargedDays,
   Map<String, Tier> tiers
) {
   /** A chunk-load radius tier. {@code dayPrice} is the price of one charged day, in coins. */
   record Tier(String id, String name, int simulationDistance, int viewDistance, long dayPrice) {
   }

   static PackageConfig disabled() {
      return new PackageConfig(false, List.of(), 0, 0, List.of(), 0, 0, Map.of(), Map.of());
   }

   boolean allows(String server) {
      return server != null && this.allowedServers.contains(server.toLowerCase(Locale.ROOT));
   }

   Optional<Tier> tier(String id) {
      return id == null ? Optional.empty() : Optional.ofNullable(this.tiers.get(id.toLowerCase(Locale.ROOT)));
   }

   boolean offersDays(int days) {
      return this.chargedDays.containsKey(days);
   }

   /** Price in coins; with {@code combat} the surcharge is added (rounded to the nearest coin). */
   long price(Tier tier, int days, boolean combat) {
      long base = Math.multiplyExact(tier.dayPrice(), (long)this.chargedDays.get(days));
      return combat ? (base * (100L + this.combatSurchargePercent) + 50L) / 100L : base;
   }

   List<PackageOffer> offers() {
      List<PackageOffer> result = new ArrayList<>();
      if (!this.enabled) {
         return result;
      }

      for (Tier tier : this.tiers.values()) {
         for (int days : this.chargedDays.keySet()) {
            result.add(new PackageOffer(tier.id(), tier.name(), days, this.price(tier, days, false),
               this.price(tier, days, true), tier.simulationDistance(), tier.viewDistance()));
         }
      }

      return result;
   }

   /** Parse the optional {@code packages:} section; a missing section means the feature is off. */
   @SuppressWarnings("unchecked")
   static PackageConfig parse(Map<String, Object> root) {
      Object raw = root == null ? null : root.get("packages");
      if (!(raw instanceof Map<?, ?> section) || !Boolean.TRUE.equals(section.get("enabled"))) {
         return disabled();
      }

      List<String> pool = new ArrayList<>();
      if (section.get("pool") instanceof List<?> poolRaw) {
         for (Object entry : poolRaw) {
            String id = String.valueOf(entry).trim();
            if (!id.isEmpty()) {
               pool.add(id);
            }
         }
      }

      List<String> servers = new ArrayList<>();
      if (section.get("allowed-servers") instanceof List<?> serversRaw) {
         for (Object entry : serversRaw) {
            String name = String.valueOf(entry).trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
               servers.add(name);
            }
         }
      }

      Map<Integer, Integer> durations = new LinkedHashMap<>();
      if (section.get("durations") instanceof Map<?, ?> durationRaw) {
         durationRaw.forEach((key, value) -> durations.put(Integer.parseInt(String.valueOf(key).trim()),
            Integer.parseInt(String.valueOf(value).trim())));
      }

      Map<String, Tier> tiers = new LinkedHashMap<>();
      if (section.get("tiers") instanceof Map<?, ?> tiersRaw) {
         tiersRaw.forEach((key, value) -> {
            String id = String.valueOf(key).trim().toLowerCase(Locale.ROOT);
            Map<String, Object> t = (Map<String, Object>)value;
            tiers.put(id, new Tier(id, String.valueOf(t.getOrDefault("name", id)),
               Integer.parseInt(String.valueOf(t.get("simulation-distance"))),
               Integer.parseInt(String.valueOf(t.get("view-distance"))),
               Long.parseLong(String.valueOf(t.get("day-price")))));
         });
      }

      PackageConfig config = new PackageConfig(true, Collections.unmodifiableList(pool),
         intOr(section.get("max-per-player"), 3), intOr(section.get("max-combat"), 4),
         Collections.unmodifiableList(servers), intOr(section.get("refund-percent"), 80),
         intOr(section.get("combat-surcharge-percent"), 50), Collections.unmodifiableMap(durations),
         Collections.unmodifiableMap(tiers));
      config.validate();
      return config;
   }

   private static int intOr(Object value, int fallback) {
      return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
   }

   private void validate() {
      if (this.pool.isEmpty() || new LinkedHashSet<>(this.pool).size() != this.pool.size()) {
         throw new IllegalArgumentException("packages.pool must contain unique bot ids");
      }

      if (this.maxPerPlayer < 1 || this.maxCombat < 0 || this.refundPercent < 0 || this.refundPercent > 100
         || this.combatSurchargePercent < 0) {
         throw new IllegalArgumentException("packages limits out of range");
      }

      if (this.allowedServers.isEmpty() || this.tiers.isEmpty() || this.chargedDays.isEmpty()) {
         throw new IllegalArgumentException("packages needs allowed-servers, tiers and durations");
      }

      this.chargedDays.forEach((days, charged) -> {
         if (days < 1 || charged < 1 || charged > days) {
            throw new IllegalArgumentException("packages.durations: purchased days >= charged days >= 1");
         }
      });
      for (Tier tier : this.tiers.values()) {
         if (tier.dayPrice() < 1 || tier.simulationDistance() < 2 || tier.viewDistance() < tier.simulationDistance()) {
            throw new IllegalArgumentException("packages.tiers." + tier.id() + " has invalid values");
         }
      }
   }
}
