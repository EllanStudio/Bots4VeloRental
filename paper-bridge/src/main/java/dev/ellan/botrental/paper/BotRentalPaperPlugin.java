package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.ApiMessages.StatusResponse;
import dev.ellan.botrental.common.LocationData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BotRentalPaperPlugin extends JavaPlugin implements Listener {
    static final String PREFIX = "&7[&e!&7]&3&l挂机机器人 &r";

    private PaperRentalConfig rentalConfig;
    private AddonClient addon;
    private EconomyService economy;
    private StatusCache statusCache;
    private BridgeHttpServer bridgeServer;
    private ExecutorService executor;
    private RentalPlaceholders placeholders;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            rentalConfig = PaperRentalConfig.load(getConfig());
            Files.createDirectories(getDataFolder().toPath());
            addon = new AddonClient(rentalConfig);
            economy = new EconomyService(rentalConfig.currency());
            statusCache = new StatusCache();
            bridgeServer = new BridgeHttpServer(this, rentalConfig, economy,
                new RefundLedger(getDataFolder().toPath().resolve("processed-refunds.properties")));
            bridgeServer.start();
            executor = Executors.newVirtualThreadPerTaskExecutor();

            BotRentalCommand command = new BotRentalCommand(this);
            var registered = getCommand("botrent");
            if (registered == null) {
                throw new IllegalStateException("botrent command is missing from plugin.yml");
            }
            registered.setExecutor(command);
            registered.setTabCompleter(command);
            Bukkit.getPluginManager().registerEvents(this, this);
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                placeholders = new RentalPlaceholders(this);
                placeholders.register();
            }
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::refreshOnlinePlayers, 40L, 100L);
            getLogger().info("Bots4Velo rental bridge enabled for redstone only.");
        }
        catch (Exception failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not enable Bots4Velo rental bridge", failure);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (placeholders != null) {
            placeholders.unregister();
            placeholders = null;
        }
        if (bridgeServer != null) {
            bridgeServer.close();
            bridgeServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        statusCache.remove(event.getPlayer().getUniqueId());
    }

    void runAsync(Runnable action) {
        ExecutorService current = executor;
        if (current != null) {
            current.execute(action);
        }
    }

    void message(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(color(PREFIX + message));
            }
        });
    }

    void openMenu(Player player) {
        boolean opened = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "zmenu open " + rentalConfig.menuName() + " " + player.getName());
        if (!opened) {
            player.sendMessage(color(PREFIX + "&c菜单暂时无法打开，请使用 &f/botrent status &c查看状态。"));
        }
        refresh(player.getUniqueId());
    }

    void refresh(UUID playerId) {
        runAsync(() -> {
            try {
                statusCache.put(playerId, addon.status(playerId));
            }
            catch (Exception ignored) {
            }
        });
    }

    private void refreshOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            if (!statusCache.needsRefresh(playerId, 4_000)) {
                continue;
            }
            try {
                statusCache.put(playerId, addon.status(playerId));
            }
            catch (Exception ignored) {
            }
        }
    }

    static LocationData location(Location location) {
        return new LocationData(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch());
    }

    static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    PaperRentalConfig rentalConfig() {
        return rentalConfig;
    }

    AddonClient addon() {
        return addon;
    }

    EconomyService economy() {
        return economy;
    }

    StatusCache statusCache() {
        return statusCache;
    }
}
