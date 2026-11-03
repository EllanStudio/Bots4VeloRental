package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.LocationData;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
   static final String PREFIX = "&6\u6302\u673a\u673a\u5668\u4eba &8| &r";
   private PaperRentalConfig rentalConfig;
   private AddonClient addon;
   private EconomyService economy;
   private StatusCache statusCache;
   private BridgeHttpServer bridgeServer;
   private ExecutorService executor;
   private RentalPlaceholders placeholders;
   private BotCombatService combat;

   public void onEnable() {
      try {
         this.saveDefaultConfig();
         this.rentalConfig = PaperRentalConfig.load(this.getConfig());
         Files.createDirectories(this.getDataFolder().toPath());
         this.addon = new AddonClient(this.rentalConfig);
         this.economy = new EconomyService(this.rentalConfig.currency());
         this.statusCache = new StatusCache();
         this.combat = new BotCombatService(
            this,
            CombatSettings.load(this.getConfig(), this.getLogger()),
            new GearStore(this.getDataFolder().toPath().resolve("bot-gear.properties")),
            this.rentalConfig.botUsernames().stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet()));
         this.combat.start();
         this.bridgeServer = new BridgeHttpServer(
            this, this.rentalConfig, this.economy, new RefundLedger(this.getDataFolder().toPath().resolve("processed-refunds.properties")),
            this.combat
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
         // Items held by combat bots go back to their owners once the lease is gone (checked once a minute).
         Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> this.combat.reconcile(owner -> {
            try {
               return this.addon.status(owner);
            } catch (Exception unavailable) {
               throw new IllegalStateException(unavailable);
            }
         }), 1200L, 1200L);
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

      if (this.combat != null) {
         this.combat.close();
         this.combat = null;
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

   /** Run something on the server thread and wait for its result (call only from a worker thread). */
   <T> T callSync(Callable<T> task) throws Exception {
      return Bukkit.getScheduler().callSyncMethod(this, task).get(5L, TimeUnit.SECONDS);
   }

   BotCombatService combat() {
      return this.combat;
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
            var2x.sendMessage(color("&6\u6302\u673a\u673a\u5668\u4eba &8| &r" + var2));
         }
      });
   }

   /**
    * A chat line ending in a clickable [confirm] button that runs {@code command}. The menu closes when a purchase
    * button is clicked, so this is what the player sees next; one click confirms.
    */
   void confirmPrompt(UUID id, String legacyText, String command) {
      Bukkit.getScheduler().runTask(this, () -> {
         Player player = Bukkit.getPlayer(id);
         if (player == null) {
            return;
         }

         Component confirm = Component.text(" [\u70b9\u8fd9\u91cc\u786e\u8ba4\u8d2d\u4e70]", NamedTextColor.GREEN, TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text("30 \u79d2\u5185\u6709\u6548\uff0c\u70b9\u51fb\u540e\u7acb\u5373\u6263\u8d39")));
         player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(PREFIX + legacyText).append(confirm));
      });
   }

   /** Open the "my packages" page, shortly after a purchase so the new package is already in the status cache. */
   void openManageMenu(UUID id) {
      this.refresh(id);
      Bukkit.getScheduler().runTaskLater(this, () -> {
         Player player = Bukkit.getPlayer(id);
         if (player != null) {
            String name = this.getConfig().getString("package-manage-menu-name", "zmenu:\u673a\u5668\u4eba\u5957\u9910\u7ba1\u7406");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "zmenu open " + name + " " + player.getName());
         }
      }, 10L);
   }

   void openMenu(Player var1) {
      boolean var2 = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "zmenu open " + this.rentalConfig.menuName() + " " + var1.getName());
      if (!var2) {
         var1.sendMessage(
            color(
               "&6\u6302\u673a\u673a\u5668\u4eba &8| &r&c\u83dc\u5355\u6682\u65f6\u65e0\u6cd5\u6253\u5f00\uff0c\u8bf7\u4f7f\u7528 &f/botrent status &c\u67e5\u770b\u72b6\u6001\u3002"
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
