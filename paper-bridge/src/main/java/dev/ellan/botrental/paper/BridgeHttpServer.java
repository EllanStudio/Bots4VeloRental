package dev.ellan.botrental.paper;

import com.sun.net.httpserver.HttpServer;
import dev.ellan.botrental.common.ApiMessages.BridgeResponse;
import dev.ellan.botrental.common.ApiMessages.PlaceRequest;
import dev.ellan.botrental.common.ApiMessages.RefundRequest;
import dev.ellan.botrental.common.HmacAuth;
import dev.ellan.botrental.common.LocationData;
import dev.ellan.botrental.common.SignedJsonHandler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class BridgeHttpServer implements AutoCloseable {
    private final JavaPlugin plugin;
    private final EconomyService economy;
    private final RefundLedger refunds;
    private final Set<String> botUsernames;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    BridgeHttpServer(JavaPlugin plugin, PaperRentalConfig config,
                     EconomyService economy, RefundLedger refunds) throws IOException {
        this.plugin = plugin;
        this.economy = economy;
        this.refunds = refunds;
        this.botUsernames = config.botUsernames().stream()
            .map(name -> name.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.bridgePort()), 16);
        HmacAuth auth = new HmacAuth(config.sharedSecret());
        server.createContext("/v1/bridge/place", new SignedJsonHandler<>(auth, PlaceRequest.class) {
            @Override
            protected Object process(PlaceRequest request) throws Exception {
                return place(request);
            }

            @Override
            protected void onFailure(Exception failure) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Bot placement request failed", failure);
            }
        });
        server.createContext("/v1/bridge/refund", new SignedJsonHandler<>(auth, RefundRequest.class) {
            @Override
            protected Object process(RefundRequest request) throws Exception {
                return refund(request);
            }

            @Override
            protected void onFailure(Exception failure) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Bot refund request failed", failure);
            }
        });
        server.setExecutor(executor);
    }

    void start() {
        server.start();
    }

    private BridgeResponse place(PlaceRequest request) throws Exception {
        if (request == null || request.leaseId() == null || request.ownerUuid() == null
            || request.location() == null || request.botUsername() == null
            || !botUsernames.contains(request.botUsername().toLowerCase(Locale.ROOT))) {
            return BridgeResponse.fail("INVALID_BOT", "Invalid rental bot");
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                result.complete(placeNow(request.botUsername(), request.location()));
            }
            catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(5, TimeUnit.SECONDS)
            ? BridgeResponse.ok("Bot placed")
            : BridgeResponse.fail("BOT_NOT_READY", "Bot is not online on redstone");
    }

    private boolean placeNow(String username, LocationData requested) {
        Player bot = Bukkit.getPlayerExact(username);
        World world = Bukkit.getWorld(requested.world());
        if (bot == null || !bot.isOnline() || world == null) {
            return false;
        }
        if (requested.y() < world.getMinHeight() || requested.y() > world.getMaxHeight() + 16) {
            return false;
        }
        Location destination = new Location(world, requested.x(), requested.y(), requested.z(),
            requested.yaw(), requested.pitch());
        if (!world.getWorldBorder().isInside(destination)) {
            return false;
        }
        boolean teleported = bot.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (teleported) {
            bot.setInvulnerable(true);
            bot.setCollidable(false);
            bot.setCanPickupItems(false);
            bot.setSleepingIgnored(true);
        }
        return teleported;
    }

    private BridgeResponse refund(RefundRequest request) throws Exception {
        if (request == null || request.refundId() == null || request.leaseId() == null
            || request.ownerUuid() == null || request.coins() < 0 || request.coins() > 1_000_000_000L) {
            return BridgeResponse.fail("BAD_REFUND", "Invalid refund");
        }
        if (refunds.contains(request.refundId())) {
            return BridgeResponse.ok("Refund already processed");
        }
        if (request.coins() == 0) {
            refunds.record(request.refundId(), 0);
            return BridgeResponse.ok("Nothing to refund");
        }
        boolean deposited = economy.deposit(request.ownerUuid(), request.coins()).get(8, TimeUnit.SECONDS);
        if (!deposited) {
            return BridgeResponse.fail("ECONOMY_REJECTED", "Economy rejected refund");
        }
        refunds.record(request.refundId(), request.coins());
        plugin.getLogger().info("Refunded " + request.coins() + " ellan_coin to "
            + request.ownerUuid() + " for lease " + request.leaseId());
        return BridgeResponse.ok("Refund processed");
    }

    @Override
    public void close() {
        server.stop(1);
        executor.close();
    }
}
