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

      return switch (var4) {
         case "pool_available" -> Integer.toString(var3.poolAvailable());
         case "pool_size" -> Integer.toString(var3.poolSize());
         case "active_count" -> Integer.toString(var3.leases().size());
         case "max_per_player" -> Integer.toString(var3.maxPerPlayer());
         case "online_rate" -> Long.toString(var3.onlineRate());
         case "offline_rate" -> Long.toString(var3.offlineRate());
         default -> this.slotValue(var3, var2);
      };
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
               case "bot", "server" -> "\u6682\u65e0";
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
