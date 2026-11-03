package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.ApiMessages;
import dev.ellan.botrental.common.LeaseView;
import dev.ellan.botrental.common.LocationData;
import java.util.Locale;
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

            if (var4[0].equalsIgnoreCase("pack") && (var4.length == 3 || var4.length == 4)) {
               Integer packDays = positiveInt(var4[2]);
               boolean packCombat = var4.length == 4 && (var4[3].equalsIgnoreCase("combat") || var4[3].equals("\u6218\u6597"));
               if (packDays != null && (var4.length == 3 || packCombat)) {
                  String packTier = var4[1].toLowerCase(Locale.ROOT);
                  LocationData packLocation = this.plugin.location(var5.getLocation());
                  this.submit(var5, () -> this.buyPackage(var5, packTier, packDays, packCombat, packLocation));
                  return true;
               }
            }

            if (var4[0].equalsIgnoreCase("gear") && (var4.length == 2 || var4.length == 3)) {
               Integer gearSlot = slot(var4[1]);
               boolean take = var4.length == 3 && (var4[2].equalsIgnoreCase("take") || var4[2].equals("\u53d6\u56de"));
               if (gearSlot != null && (var4.length == 2 || take)) {
                  this.submit(var5, () -> this.gear(var5, gearSlot, take));
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
                     "&7\u7528\u6cd5\uff1a&f/botrent &7| &f/botrent status &7| &f/botrent rent <\u5206\u949f> &7| &f/botrent topup <\u79df\u8d41\u4f4d> <\u91d1\u5e01> &7| &f/botrent pack <\u6863\u4f4d> <\u5929\u6570> [combat] &7| &f/botrent gear <\u79df\u8d41\u4f4d> [take] &7| &f/botrent relocate <\u79df\u8d41\u4f4d> &7| &f/botrent cancel <\u79df\u8d41\u4f4d>"
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

   private void showStatus(UUID owner) {
      try {
         ApiMessages.StatusResponse status = this.plugin.addon().status(owner);
         this.plugin.statusCache().put(owner, status);
         this.plugin.message(owner, "&7\u673a\u5668\u4eba\u6c60\uff1a&f" + status.poolAvailable() + "/" + status.poolSize()
            + " &7| \u5728\u7ebf\uff1a&e" + status.onlineRate() + "/\u5206\u949f &7| \u79bb\u7ebf\uff1a&c" + status.offlineRate() + "/\u5206\u949f");
         if (status.packagePoolSize() > 0) {
            this.plugin.message(owner, "&7\u957f\u671f\u6302\u673a\u6c60\uff1a&f" + status.packagePoolAvailable() + "/" + status.packagePoolSize()
               + " &7| \u6bcf\u4eba\u6700\u591a &f" + status.maxPackagesPerPlayer() + " &7\u4e2a");
         }

         if (status.leases().isEmpty()) {
            this.plugin.message(owner, "&7\u4f60\u76ee\u524d\u6ca1\u6709\u6b63\u5728\u8fd0\u884c\u7684\u6302\u673a\u673a\u5668\u4eba\u3002");
         }

         for (LeaseView lease : status.leases()) {
            String where = this.plugin.rentalConfig().displayName(lease.location().server());
            if (lease.isPackage()) {
               String remaining = lease.expiresAt() > 0L ? "&7\u5269\u4f59 &e" + formatMinutes(lease.estimatedMinutes()) : "&7\u7b49\u5f85\u5230\u8fbe\u540e\u5f00\u59cb\u8ba1\u65f6";
               this.plugin.message(owner, "&7\u79df\u8d41\u4f4d " + lease.slot() + "\uff1a&f" + lease.botUsername() + " &d[\u957f\u671f\u00b7" + tierName(status, lease.tier())
                  + (lease.combat() ? "\u00b7\u6218\u6597" : "") + "] &7\u4f4d\u4e8e &f" + where + " &7\u72b6\u6001 &f" + stateName(lease.state()) + " " + remaining);
            } else {
               this.plugin.message(owner, "&7\u79df\u8d41\u4f4d " + lease.slot() + "\uff1a&f" + lease.botUsername() + " &7\u4f4d\u4e8e &f" + where
                  + " &7\u72b6\u6001 &f" + stateName(lease.state()) + " &7\u4f59\u989d &e" + lease.reserveCoins() + " &7\u7ea6 " + lease.estimatedMinutes() + " \u5206\u949f");
            }
         }
      } catch (Exception failure) {
         throw new RuntimeException(failure);
      }
   }

   private static String tierName(ApiMessages.StatusResponse status, String tier) {
      return status.offers().stream().filter(offer -> offer.tier().equalsIgnoreCase(tier)).map(ApiMessages.PackageOffer::tierName).findFirst().orElse(tier);
   }

   static String formatMinutes(long minutes) {
      if (minutes >= 1440L) {
         return (minutes / 1440L) + "\u5929" + ((minutes % 1440L) / 60L) + "\u5c0f\u65f6";
      }

      return minutes >= 60L ? (minutes / 60L) + "\u5c0f\u65f6" + (minutes % 60L) + "\u5206" : minutes + "\u5206\u949f";
   }

   private void gear(Player player, int slot, boolean take) {
      UUID id = player.getUniqueId();
      try {
         LeaseView lease = find(this.plugin.addon().status(id), slot);
         if (lease == null) {
            this.plugin.message(id, "&c\u6ca1\u6709\u627e\u5230\u8fd9\u4e2a\u79df\u8d41\u4f4d\u3002");
            return;
         }

         if (!lease.isPackage() || !lease.combat()) {
            this.plugin.message(id, "&c\u53ea\u6709\u6218\u6597\u578b\u957f\u671f\u5957\u9910\u7684\u673a\u5668\u4eba\u624d\u80fd\u4fdd\u7ba1\u7269\u54c1\u3002");
            return;
         }

         if (!this.plugin.rentalConfig().serverId().equalsIgnoreCase(lease.location().server())) {
            this.plugin.message(id, "&c\u8fd9\u4e2a\u673a\u5668\u4eba\u5728 " + this.plugin.rentalConfig().displayName(lease.location().server())
               + "\uff0c\u8bf7\u5230\u90a3\u4e2a\u670d\u52a1\u5668\u518d\u6267\u884c\u8fd9\u4e2a\u547d\u4ee4\u3002");
            return;
         }

         String result = this.plugin.callSync(() -> take ? this.plugin.combat().withdraw(player, lease) : this.plugin.combat().deposit(player, lease));
         this.plugin.message(id, result);
      } catch (Exception failure) {
         throw new RuntimeException(failure);
      }
   }

   private void buyPackage(Player player, String tierId, int days, boolean combat, LocationData location) {
      UUID id = player.getUniqueId();
      try {
         ApiMessages.StatusResponse status = this.plugin.addon().status(id);
         this.plugin.statusCache().put(id, status);
         ApiMessages.PackageOffer offer = status.offers().stream()
            .filter(candidate -> candidate.tier().equalsIgnoreCase(tierId) && candidate.days() == days).findFirst().orElse(null);
         if (offer == null) {
            String options = status.offers().stream().map(o -> o.tier() + "/" + o.days() + "\u5929").distinct().toList().toString();
            this.plugin.message(id, status.offers().isEmpty() ? "&c\u957f\u671f\u6302\u673a\u5957\u9910\u6682\u672a\u5f00\u653e\u3002" : "&c\u6ca1\u6709\u8fd9\u4e2a\u5957\u9910\u3002\u53ef\u9009\uff1a" + options);
            return;
         }

         if (status.leases().stream().filter(LeaseView::isPackage).count() >= status.maxPackagesPerPlayer()) {
            this.plugin.message(id, "&c\u4f60\u5df2\u7ecf\u6709 " + status.maxPackagesPerPlayer() + " \u4e2a\u957f\u671f\u6302\u673a\u5957\u9910\u4e86\u3002");
            return;
         }

         if (status.packagePoolAvailable() <= 0) {
            this.plugin.message(id, "&c\u957f\u671f\u6302\u673a\u673a\u5668\u4eba\u6682\u65f6\u6ca1\u6709\u7a7a\u4f4d\u3002");
            return;
         }

         long price = combat ? offer.combatPrice() : offer.price();
         if (this.plugin.economy().balance(id).get(8L, TimeUnit.SECONDS) < price) {
            this.plugin.message(id, "&c\u4f59\u989d\u4e0d\u8db3\uff0c\u672c\u6b21\u9700\u8981 " + price + " \u679a\u827e\u5c14\u5c9a\u91d1\u5e01\u3002");
            return;
         }

         if (!this.plugin.economy().withdraw(id, price).get(8L, TimeUnit.SECONDS)) {
            this.plugin.message(id, "&c\u6263\u6b3e\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002");
            return;
         }

         ApiMessages.ActionResponse response;
         try {
            response = this.plugin.addon().createPackage(id, player.getName(), price, location, offer.tier(), days, combat);
         } catch (Exception failure) {
            // The request may have reached the addon even though we did not get its answer: check before refunding.
            ApiMessages.StatusResponse after = this.plugin.addon().status(id);
            if (after.leases().stream().filter(LeaseView::isPackage).count() > status.leases().stream().filter(LeaseView::isPackage).count()) {
               this.plugin.statusCache().put(id, after);
               this.plugin.message(id, "&a\u5957\u9910\u5df2\u521b\u5efa\uff0c\u673a\u5668\u4eba\u6b63\u5728\u524d\u5f80\u4f60\u521a\u624d\u7684\u4f4d\u7f6e\u3002");
               return;
            }

            this.refundLocal(id, price);
            throw failure;
         }

         if (!response.success()) {
            this.refundLocal(id, Math.max(response.refundableCoins(), 0L) > 0L ? response.refundableCoins() : price);
            this.plugin.message(id, "&c" + response.message());
         } else {
            this.plugin.statusCache().updateLease(id, response.lease());
            this.plugin.message(id, "&a" + response.message() + " &7\u5df2\u652f\u4ed8 &e" + price + " &7\u91d1\u5e01\u3002");
            this.plugin.refresh(id);
         }
      } catch (Exception failure) {
         throw new RuntimeException(failure);
      }
   }

   private void rent(UUID var1, String var2, int var3, LocationData var4) {
      try {
         ApiMessages.StatusResponse var5 = this.plugin.addon().status(var1);
         this.plugin.statusCache().put(var1, var5);
         if (var5.leases().stream().filter(lease -> !lease.isPackage()).count() >= var5.maxPerPlayer()) {
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
         } else if (var6.isPackage()) {
            this.plugin.message(var1, "&c\u957f\u671f\u6302\u673a\u5957\u9910\u4e0d\u80fd\u5145\u503c\uff0c\u5230\u671f\u540e\u53ef\u4ee5\u91cd\u65b0\u8d2d\u4e70\u3002");
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
      return var1 != null && var1 <= 9 ? var1 : null;
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
         return filter(List.of("menu", "status", "rent", "pack", "gear", "topup", "relocate", "cancel"), var4[0]);
      } else if (var4.length == 2 && var4[0].equalsIgnoreCase("rent")) {
         return filter(this.plugin.rentalConfig().rentOptions().stream().map(String::valueOf).toList(), var4[1]);
      } else if (var4.length >= 2 && var4[0].equalsIgnoreCase("pack") && var1 instanceof Player completing) {
         ApiMessages.StatusResponse cached = this.plugin.statusCache().get(completing.getUniqueId()).orElse(null);
         List<ApiMessages.PackageOffer> offers = cached == null ? List.of() : cached.offers();
         if (var4.length == 2) {
            return filter(offers.stream().map(ApiMessages.PackageOffer::tier).distinct().toList(), var4[1]);
         } else if (var4.length == 3) {
            return filter(offers.stream().map(offer -> Integer.toString(offer.days())).distinct().toList(), var4[2]);
         } else {
            return filter(List.of("combat"), var4[3]);
         }
      } else if (var4.length == 3 && var4[0].equalsIgnoreCase("gear")) {
         return filter(List.of("take"), var4[2]);
      } else if (var4.length == 2 && Set.of("topup", "relocate", "cancel", "gear").contains(var4[0].toLowerCase())) {
         return filter(List.of("1", "2", "3", "4", "5", "6"), var4[1]);
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
