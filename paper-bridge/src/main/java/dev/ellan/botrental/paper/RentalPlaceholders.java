package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.ApiMessages;
import dev.ellan.botrental.common.LeaseView;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class RentalPlaceholders extends PlaceholderExpansion {
   private final BotRentalPaperPlugin plugin;

   RentalPlaceholders(BotRentalPaperPlugin var1) {
      this.plugin = var1;
   }

   @NotNull
   public String getIdentifier() {
      return "botrental";
   }

   @NotNull
   public String getAuthor() {
      return "EllanServer";
   }

   @NotNull
   public String getVersion() {
      return this.plugin.getPluginMeta().getVersion();
   }

   public boolean persist() {
      return true;
   }

   @Nullable
   public String onRequest(OfflinePlayer var1, @NotNull String var2) {
      if (var1 == null) {
         return "";
      }

      ApiMessages.StatusResponse var3 = this.plugin.statusCache().get(var1.getUniqueId()).orElse(null);
      if (var3 == null) {
         this.plugin.refresh(var1.getUniqueId());
         return "\u52a0\u8f7d\u4e2d";
      }

      String var4 = var2.toLowerCase(Locale.ROOT);
      if (var4.startsWith("offer_")) {
         return offerValue(var3, var4);
      }


      return switch (var4) {
         case "pool_available" -> Integer.toString(var3.poolAvailable());
         case "pool_size" -> Integer.toString(var3.poolSize());
         case "active_count" -> Long.toString(var3.leases().stream().filter(lease -> !lease.isPackage()).count());
         case "max_per_player" -> Integer.toString(var3.maxPerPlayer());
         case "online_rate" -> Long.toString(var3.onlineRate());
         case "offline_rate" -> Long.toString(var3.offlineRate());
         case "pack_pool_available" -> Integer.toString(var3.packagePoolAvailable());
         case "pack_pool_size" -> Integer.toString(var3.packagePoolSize());
         case "pack_max_per_player" -> Integer.toString(var3.maxPackagesPerPlayer());
         case "pack_active_count" -> Long.toString(var3.leases().stream().filter(LeaseView::isPackage).count());
         default -> this.slotValue(var3, var2);
      };
   }

   /**
    * offer_&lt;tier&gt;_name, offer_&lt;tier&gt;_radius, offer_&lt;tier&gt;_&lt;days&gt;_price and
    * offer_&lt;tier&gt;_&lt;days&gt;_combat_price. Prices come from the addon, so the menu can never disagree with it.
    */
   private static String offerValue(ApiMessages.StatusResponse status, String key) {
      String[] parts = key.split("_");
      if (parts.length < 3) {
         return "";
      }

      String tier = parts[1];
      if (parts.length == 3 && parts[2].equals("name")) {
         return status.offers().stream().filter(o -> o.tier().equalsIgnoreCase(tier)).map(ApiMessages.PackageOffer::tierName).findFirst().orElse("-");
      }

      if (parts.length == 3 && parts[2].equals("radius")) {
         return status.offers().stream().filter(o -> o.tier().equalsIgnoreCase(tier)).map(o -> {
            int side = o.simulationDistance() * 2 + 1;
            return side + "\u00d7" + side + " \u533a\u5757";
         }).findFirst().orElse("-");
      }

      boolean combat = parts.length == 5 && parts[3].equals("combat") && parts[4].equals("price");
      boolean plain = parts.length == 4 && parts[3].equals("price");
      if (!combat && !plain) {
         return "";
      }

      int days;
      try {
         days = Integer.parseInt(parts[2]);
      } catch (NumberFormatException notADay) {
         return "";
      }

      return status.offers().stream().filter(o -> o.tier().equalsIgnoreCase(tier) && o.days() == days).findFirst()
         .map(o -> Long.toString(combat ? o.combatPrice() : o.price())).orElse("-");
   }

   private String slotValue(ApiMessages.StatusResponse var1, String var2) {
      String[] var3 = var2.toLowerCase(Locale.ROOT).split("_", 3);
      if (var3.length == 3 && var3[0].equals("slot")) {
         int var4;
         try {
            var4 = Integer.parseInt(var3[1]);
         } catch (NumberFormatException var9) {
            return "";
         }

         LeaseView var5 = var1.leases().stream().filter(var1x -> var1x.slot() == var4).findFirst().orElse(null);
         if (var5 == null) {
            String var10 = var3[2];

            return switch (var10) {
               case "status" -> "\u7a7a\u95f2";
               case "bot", "server", "plan", "tier", "remaining" -> "\u6682\u65e0";
               case "combat" -> "\u5426";
               default -> "0";
            };
         } else {
            String var6 = var3[2];

            return switch (var6) {
               case "status" -> stateName(var5.state());
               case "bot" -> var5.botUsername();
               case "reserve" -> Long.toString(var5.reserveCoins());
               case "spent" -> Long.toString(var5.spentCoins());
               case "rate" -> Long.toString(var5.currentRate());
               case "minutes" -> Long.toString(var5.estimatedMinutes());
               case "plan" -> var5.isPackage() ? "\u957f\u671f\u5957\u9910" : "\u6309\u5206\u949f";
               case "tier" -> var5.tier() == null || var5.tier().isEmpty() ? "" : var1.offers().stream().filter(o -> o.tier().equalsIgnoreCase(var5.tier())).map(ApiMessages.PackageOffer::tierName).findFirst().orElse(var5.tier());
               case "combat" -> var5.combat() ? "\u662f" : "\u5426";
               case "remaining" -> var5.isPackage() ? (var5.expiresAt() > 0L ? BotRentalCommand.formatMinutes(var5.estimatedMinutes()) : "\u7b49\u5f85\u5230\u8fbe") : "";
               case "days" -> Integer.toString(var5.packageDays());
               case "world" -> var5.location().world();
               case "server" -> this.plugin.rentalConfig().displayName(var5.location().server());
               case "location" -> String.format(
                  Locale.ROOT, "%s %.1f %.1f %.1f", var5.location().world(), var5.location().x(), var5.location().y(), var5.location().z()
               );
               default -> "";
            };
         }
      } else {
         return null;
      }
   }

   private static String stateName(String var0) {
      return switch (var0) {
         case "STARTING" -> "\u6b63\u5728\u542f\u52a8";
         case "ACTIVE" -> "\u8fd0\u884c\u4e2d";
         case "REFUND_PENDING" -> "\u9000\u6b3e\u5904\u7406\u4e2d";
         default -> var0;
      };
   }
}
