package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.ApiMessages;
import dev.ellan.botrental.common.LeaseView;
import dev.ellan.botrental.common.LocationData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

final class BotRentalCommand implements CommandExecutor, TabCompleter {
   private final BotRentalPaperPlugin plugin;
   private final Set<UUID> busy = Collections.synchronizedSet(new HashSet<>());

   BotRentalCommand(BotRentalPaperPlugin var1) {
      this.plugin = var1;
   }

   public boolean onCommand(CommandSender var1, Command var2, String var3, String[] var4) {
      if (var1 instanceof Player var5) {
         if (!var5.hasPermission("botrental.use")) {
            var5.sendMessage(BotRentalPaperPlugin.color("&7[&e!&7]&3&l\u6302\u673a\u673a\u5668\u4eba &r&c\u4f60\u6ca1\u6709\u4f7f\u7528\u6743\u9650\u3002"));
            return true;
         }

         if (var4.length == 0 || var4[0].equalsIgnoreCase("menu") || var4[0].equalsIgnoreCase("open")) {
            this.plugin.openMenu(var5);
            return true;
         }

         if (var4[0].equalsIgnoreCase("status")) {
            this.submit(var5, () -> this.showStatus(var5.getUniqueId()));
            return true;
         }

         if (var4[0].equalsIgnoreCase("rent") && var4.length == 2) {
            Integer var10 = positiveInt(var4[1]);
            if (var10 != null && this.plugin.rentalConfig().rentOptions().contains(var10)) {
               LocationData var12 = this.plugin.location(var5.getLocation());
               this.submit(var5, () -> this.rent(var5.getUniqueId(), var5.getName(), var10, var12));
               return true;
            } else {
               this.plugin.message(var5.getUniqueId(), "&c\u53ef\u9009\u79df\u671f\uff1a" + this.plugin.rentalConfig().rentOptions());
               return true;
            }
         } else {
            if (var4[0].equalsIgnoreCase("cancel") && var4.length == 2) {
               Integer var6 = slot(var4[1]);
               if (var6 != null) {
                  this.submit(var5, () -> this.cancel(var5.getUniqueId(), var6));
                  return true;
               }
            }

            if (var4[0].equalsIgnoreCase("relocate") && var4.length == 2) {
               Integer var8 = slot(var4[1]);
               if (var8 != null) {
                  LocationData var11 = this.plugin.location(var5.getLocation());
                  this.submit(var5, () -> this.relocate(var5.getUniqueId(), var8, var11));
                  return true;
               }
            }

            if (var4[0].equalsIgnoreCase("topup") && var4.length == 3) {
               Integer var9 = slot(var4[1]);
               Long var7 = positiveLong(var4[2]);
               if (var9 != null && var7 != null && this.plugin.rentalConfig().topUpOptions().contains(var7)) {
                  this.submit(var5, () -> this.topUp(var5.getUniqueId(), var9, var7));
                  return true;
               } else {
                  this.plugin.message(var5.getUniqueId(), "&c\u53ef\u9009\u5145\u503c\u91d1\u989d\uff1a" + this.plugin.rentalConfig().topUpOptions());
                  return true;
               }
            } else {
               this.plugin
                  .message(
                     var5.getUniqueId(),
                     "&7\u7528\u6cd5\uff1a&f/botrent &7| &f/botrent status &7| &f/botrent rent <\u5206\u949f> &7| &f/botrent topup <\u79df\u8d41\u4f4d> <\u91d1\u5e01> &7| &f/botrent relocate <\u79df\u8d41\u4f4d> &7| &f/botrent cancel <\u79df\u8d41\u4f4d>"
                  );
               return true;
            }
         }
      } else {
         var1.sendMessage("This command must be used by a player.");
         return true;
      }
   }

   private void submit(Player var1, Runnable var2) {
      UUID var3 = var1.getUniqueId();
      if (!this.busy.add(var3)) {
         this.plugin.message(var3, "&e\u4e0a\u4e00\u4e2a\u79df\u8d41\u64cd\u4f5c\u4ecd\u5728\u5904\u7406\u4e2d\uff0c\u8bf7\u7a0d\u5019\u3002");
      } else {
         this.plugin.runAsync(() -> {
            try {
               var2.run();
            } catch (Exception var7) {
               this.plugin.getLogger().log(Level.WARNING, "Rental command failed for " + var3, var7);
               this.plugin.message(var3, "&c\u79df\u8d41\u670d\u52a1\u6682\u65f6\u4e0d\u53ef\u7528\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002");
            } finally {
               this.busy.remove(var3);
            }
         });
      }
   }

   private void showStatus(UUID var1) {
      try {
         ApiMessages.StatusResponse var2 = this.plugin.addon().status(var1);
         this.plugin.statusCache().put(var1, var2);
         this.plugin
            .message(
               var1,
               "&7\u673a\u5668\u4eba\u6c60\uff1a&f"
                  + var2.poolAvailable()
                  + "/"
                  + var2.poolSize()
                  + " &7| \u5728\u7ebf\uff1a&e"
                  + var2.onlineRate()
                  + "/\u5206\u949f &7| \u79bb\u7ebf\uff1a&c"
                  + var2.offlineRate()
                  + "/\u5206\u949f"
            );
         if (var2.leases().isEmpty()) {
            this.plugin.message(var1, "&7\u4f60\u76ee\u524d\u6ca1\u6709\u6b63\u5728\u8fd0\u884c\u7684\u6302\u673a\u673a\u5668\u4eba\u3002");
         }

         for (LeaseView var4 : var2.leases()) {
            this.plugin
               .message(
                  var1,
                  "&7\u79df\u8d41\u4f4d "
                     + var4.slot()
                     + "\uff1a&f"
                     + var4.botUsername()
                     + " &7\u4f4d\u4e8e &f"
                     + this.plugin.rentalConfig().displayName(var4.location().server())
                     + " &7\u72b6\u6001 &f"
                     + stateName(var4.state())
                     + " &7\u4f59\u989d &e"
                     + var4.reserveCoins()
                     + " &7\u7ea6 "
                     + var4.estimatedMinutes()
                     + " \u5206\u949f"
               );
         }
      } catch (Exception var5) {
         throw new RuntimeException(var5);
      }
   }

   private void rent(UUID var1, String var2, int var3, LocationData var4) {
      try {
         ApiMessages.StatusResponse var5 = this.plugin.addon().status(var1);
         this.plugin.statusCache().put(var1, var5);
         if (var5.leases().size() >= var5.maxPerPlayer()) {
            this.plugin.message(var1, "&c\u4f60\u5df2\u7ecf\u79df\u6ee1 " + var5.maxPerPlayer() + " \u4e2a\u673a\u5668\u4eba\u3002");
         } else if (var5.poolAvailable() <= 0) {
            this.plugin.message(var1, "&c\u673a\u5668\u4eba\u6c60\u6682\u65f6\u6ca1\u6709\u7a7a\u4f4d\u3002");
         } else {
            long var6 = Math.multiplyExact(var3, var5.onlineRate());
            if (this.plugin.economy().balance(var1).get(8L, TimeUnit.SECONDS) < var6) {
               this.plugin.message(var1, "&c\u4f59\u989d\u4e0d\u8db3\uff0c\u672c\u6b21\u9700\u8981 " + var6 + " \u679a\u827e\u5c14\u5c9a\u91d1\u5e01\u3002");
            } else if (!this.plugin.economy().withdraw(var1, var6).get(8L, TimeUnit.SECONDS)) {
               this.plugin.message(var1, "&c\u6263\u6b3e\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002");
            } else {
               ApiMessages.ActionResponse var8;
               try {
                  var8 = this.plugin.addon().create(var1, var2, var6, var4);
               } catch (Exception var11) {
                  ApiMessages.StatusResponse var10 = this.plugin.addon().status(var1);
                  if (var10.leases().size() > var5.leases().size()) {
                     this.plugin.statusCache().put(var1, var10);
                     this.plugin
                        .message(
                           var1, "&a\u79df\u8d41\u5df2\u521b\u5efa\uff0c\u673a\u5668\u4eba\u6b63\u5728\u524d\u5f80\u4f60\u521a\u624d\u7684\u4f4d\u7f6e\u3002"
                        );
                     return;
                  }

                  this.refundLocal(var1, var6);
                  throw var11;
               }

               if (!var8.success()) {
                  this.refundLocal(var1, var6);
                  this.plugin.message(var1, "&c" + var8.message());
               } else {
                  this.plugin.statusCache().updateLease(var1, var8.lease());
                  this.plugin.message(var1, "&a" + var8.message() + " &7\u5df2\u9884\u5b58 &e" + var6 + " &7\u91d1\u5e01\u3002");
                  this.plugin.refresh(var1);
               }
            }
         }
      } catch (Exception var12) {
         throw new RuntimeException(var12);
      }
   }

   private void topUp(UUID var1, int var2, long var3) {
      try {
         ApiMessages.StatusResponse var5 = this.plugin.addon().status(var1);
         LeaseView var6 = find(var5, var2);
         if (var6 == null) {
            this.plugin.message(var1, "&c\u6ca1\u6709\u627e\u5230\u8fd9\u4e2a\u79df\u8d41\u4f4d\u3002");
         } else if (this.plugin.economy().balance(var1).get(8L, TimeUnit.SECONDS) < var3) {
            this.plugin.message(var1, "&c\u4f59\u989d\u4e0d\u8db3\uff0c\u672c\u6b21\u9700\u8981 " + var3 + " \u679a\u827e\u5c14\u5c9a\u91d1\u5e01\u3002");
         } else if (!this.plugin.economy().withdraw(var1, var3).get(8L, TimeUnit.SECONDS)) {
            this.plugin.message(var1, "&c\u6263\u6b3e\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002");
         } else {
            ApiMessages.ActionResponse var7;
            try {
               var7 = this.plugin.addon().topUp(var1, var2, var3);
            } catch (Exception var10) {
               LeaseView var9 = find(this.plugin.addon().status(var1), var2);
               if (var9 != null && var9.reserveCoins() >= var6.reserveCoins() + var3) {
                  this.plugin.message(var1, "&a\u5145\u503c\u6210\u529f\u3002");
                  this.plugin.refresh(var1);
                  return;
               }

               this.refundLocal(var1, var3);
               throw var10;
            }

            if (!var7.success()) {
               this.refundLocal(var1, var3);
               this.plugin.message(var1, "&c" + var7.message());
            } else {
               this.plugin.statusCache().updateLease(var1, var7.lease());
               this.plugin.message(var1, "&a" + var7.message());
               this.plugin.refresh(var1);
            }
         }
      } catch (Exception var11) {
         throw new RuntimeException(var11);
      }
   }

   private void cancel(UUID var1, int var2) {
      try {
         ApiMessages.ActionResponse var3 = this.plugin.addon().cancel(var1, var2);
         if (var3.success()) {
            this.plugin.statusCache().removeLease(var1, var2);
         }

         this.plugin.message(var1, (var3.success() ? "&a" : "&c") + var3.message());
         this.plugin.refresh(var1);
      } catch (Exception var4) {
         throw new RuntimeException(var4);
      }
   }

   private void relocate(UUID var1, int var2, LocationData var3) {
      try {
         ApiMessages.ActionResponse var4 = this.plugin.addon().relocate(var1, var2, var3);
         if (var4.success()) {
            this.plugin.statusCache().updateLease(var1, var4.lease());
         }

         this.plugin.message(var1, (var4.success() ? "&a" : "&c") + var4.message());
         this.plugin.refresh(var1);
      } catch (Exception var5) {
         throw new RuntimeException(var5);
      }
   }

   private void refundLocal(UUID var1, long var2) throws Exception {
      if (!this.plugin.economy().deposit(var1, var2).get(8L, TimeUnit.SECONDS)) {
         this.plugin.getLogger().severe("URGENT: could not return " + var2 + " ellan_coin to " + var1);
         this.plugin
            .message(
               var1,
               "&c\u9000\u6b3e\u6682\u65f6\u5931\u8d25\uff0c\u7ba1\u7406\u5458\u5df2\u6536\u5230\u8bb0\u5f55\uff0c\u8bf7\u52ff\u91cd\u590d\u64cd\u4f5c\u3002"
            );
      }
   }

   private static LeaseView find(ApiMessages.StatusResponse var0, int var1) {
      return var0.leases().stream().filter(var1x -> var1x.slot() == var1).findFirst().orElse(null);
   }

   private static String stateName(String var0) {
      return switch (var0) {
         case "STARTING" -> "\u6b63\u5728\u542f\u52a8";
         case "ACTIVE" -> "\u8fd0\u884c\u4e2d";
         case "REFUND_PENDING" -> "\u9000\u6b3e\u5904\u7406\u4e2d";
         default -> var0;
      };
   }

   private static Integer slot(String var0) {
      Integer var1 = positiveInt(var0);
      return var1 != null && var1 <= 3 ? var1 : null;
   }

   private static Integer positiveInt(String var0) {
      try {
         int var1 = Integer.parseInt(var0);
         return var1 > 0 ? var1 : null;
      } catch (NumberFormatException var2) {
         return null;
      }
   }

   private static Long positiveLong(String var0) {
      try {
         long var1 = Long.parseLong(var0);
         return var1 > 0L ? var1 : null;
      } catch (NumberFormatException var3) {
         return null;
      }
   }

   public List<String> onTabComplete(CommandSender var1, Command var2, String var3, String[] var4) {
      if (var4.length == 1) {
         return filter(List.of("menu", "status", "rent", "topup", "relocate", "cancel"), var4[0]);
      } else if (var4.length == 2 && var4[0].equalsIgnoreCase("rent")) {
         return filter(this.plugin.rentalConfig().rentOptions().stream().map(String::valueOf).toList(), var4[1]);
      } else if (var4.length == 2 && Set.of("topup", "relocate", "cancel").contains(var4[0].toLowerCase())) {
         return filter(List.of("1", "2", "3"), var4[1]);
      } else {
         return var4.length == 3 && var4[0].equalsIgnoreCase("topup")
            ? filter(this.plugin.rentalConfig().topUpOptions().stream().map(String::valueOf).toList(), var4[2])
            : List.of();
      }
   }

   private static List<String> filter(List<String> var0, String var1) {
      String var2 = var1.toLowerCase();
      ArrayList var3 = new ArrayList();
      var0.stream().filter(var1x -> var1x.toLowerCase().startsWith(var2)).forEach(var3::add);
      return var3;
   }
}
