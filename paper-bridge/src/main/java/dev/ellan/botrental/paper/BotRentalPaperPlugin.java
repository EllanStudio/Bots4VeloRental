package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.LocationData;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class BotRentalPaperPlugin extends JavaPlugin implements Listener {
   static final String PREFIX = "&7[&e!&7]&3&l\u6302\u673a\u673a\u5668\u4eba &r";
   private PaperRentalConfig rentalConfig;
   private AddonClient addon;
   private EconomyService economy;
   private StatusCache statusCache;
   private BridgeHttpServer bridgeServer;
   private ExecutorService executor;
   private RentalPlaceholders placeholders;

   public void onEnable() {
      try {
         this.saveDefaultConfig();
         this.rentalConfig = PaperRentalConfig.load(this.getConfig());
         Files.createDirectories(this.getDataFolder().toPath());
         this.addon = new AddonClient(this.rentalConfig);
         this.economy = new EconomyService(this.rentalConfig.currency());
         this.statusCache = new StatusCache();
         this.bridgeServer = new BridgeHttpServer(
            this, this.rentalConfig, this.economy, new RefundLedger(this.getDataFolder().toPath().resolve("processed-refunds.properties"))
         );
         this.bridgeServer.start();
         this.executor = Executors.newVirtualThreadPerTaskExecutor();
         BotRentalCommand var1 = new BotRentalCommand(this);
         PluginCommand var2 = this.getCommand("botrent");
         if (var2 == null) {
            throw new IllegalStateException("botrent command is missing from plugin.yml");
         }

         var2.setExecutor(var1);
         var2.setTabCompleter(var1);
         Bukkit.getPluginManager().registerEvents(this, this);
         if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.placeholders = new RentalPlaceholders(this);
            this.placeholders.register();
         }

         Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::refreshOnlinePlayers, 40L, 100L);
         this.getLogger().info("Bots4Velo rental bridge enabled for server " + this.rentalConfig.serverId() + ".");
      } catch (Exception var3) {
         this.getLogger().log(Level.SEVERE, "Could not enable Bots4Velo rental bridge", var3);
         Bukkit.getPluginManager().disablePlugin(this);
      }
   }

   public void onDisable() {
      if (this.placeholders != null) {
         this.placeholders.unregister();
         this.placeholders = null;
      }

      if (this.bridgeServer != null) {
         this.bridgeServer.close();
         this.bridgeServer = null;
      }

      if (this.executor != null) {
         this.executor.shutdownNow();
         this.executor = null;
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent var1) {
      this.refresh(var1.getPlayer().getUniqueId());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent var1) {
      this.statusCache.remove(var1.getPlayer().getUniqueId());
   }

   void runAsync(Runnable var1) {
      ExecutorService var2 = this.executor;
      if (var2 != null) {
         var2.execute(var1);
      }
   }

   void message(UUID var1, String var2) {
      Bukkit.getScheduler().runTask(this, () -> {
         Player var2x = Bukkit.getPlayer(var1);
         if (var2x != null) {
            var2x.sendMessage(color("&7[&e!&7]&3&l\u6302\u673a\u673a\u5668\u4eba &r" + var2));
         }
      });
   }

   void openMenu(Player var1) {
      boolean var2 = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "zmenu open " + this.rentalConfig.menuName() + " " + var1.getName());
      if (!var2) {
         var1.sendMessage(
            color(
               "&7[&e!&7]&3&l\u6302\u673a\u673a\u5668\u4eba &r&c\u83dc\u5355\u6682\u65f6\u65e0\u6cd5\u6253\u5f00\uff0c\u8bf7\u4f7f\u7528 &f/botrent status &c\u67e5\u770b\u72b6\u6001\u3002"
            )
         );
      }

      this.refresh(var1.getUniqueId());
   }

   void refresh(UUID var1) {
      this.runAsync(() -> {
         try {
            this.statusCache.put(var1, this.addon.status(var1));
         } catch (Exception var3) {
         }
      });
   }

   private void refreshOnlinePlayers() {
      for (Player var2 : Bukkit.getOnlinePlayers()) {
         UUID var3 = var2.getUniqueId();
         if (this.statusCache.needsRefresh(var3, 4000L)) {
            try {
               this.statusCache.put(var3, this.addon.status(var3));
            } catch (Exception var5) {
            }
         }
      }
   }

   LocationData location(Location var1) {
      return new LocationData(this.rentalConfig.serverId(), var1.getWorld().getName(), var1.getX(), var1.getY(), var1.getZ(), var1.getYaw(), var1.getPitch());
   }

   static String color(String var0) {
      return ChatColor.translateAlternateColorCodes('&', var0);
   }

   PaperRentalConfig rentalConfig() {
      return this.rentalConfig;
   }

   AddonClient addon() {
      return this.addon;
   }

   EconomyService economy() {
      return this.economy;
   }

   StatusCache statusCache() {
      return this.statusCache;
   }
}
