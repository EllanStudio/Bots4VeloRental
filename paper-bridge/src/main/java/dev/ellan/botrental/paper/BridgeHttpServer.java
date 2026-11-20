package dev.ellan.botrental.paper;

import com.sun.net.httpserver.HttpServer;
import dev.ellan.botrental.common.ApiMessages;
import dev.ellan.botrental.common.HmacAuth;
import dev.ellan.botrental.common.LocationData;
import dev.ellan.botrental.common.SignedJsonHandler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

final class BridgeHttpServer implements AutoCloseable {
   private final JavaPlugin plugin;
   private final EconomyService economy;
   private final RefundLedger refunds;
   private final Set<String> botUsernames;
   private final String serverId;
   private final BotCombatService combat;
   private final FrozenRingSweeper ringSweeper;
   private final HttpServer server;
   private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

   BridgeHttpServer(final JavaPlugin var1, PaperRentalConfig var2, EconomyService var3, RefundLedger var4, BotCombatService combat,
                    FrozenRingSweeper ringSweeper) throws IOException {
      this.plugin = var1;
      this.combat = combat;
      this.ringSweeper = ringSweeper;
      this.economy = var3;
      this.refunds = var4;
      this.serverId = var2.serverId();
      this.botUsernames = var2.botUsernames().stream().map(var0 -> var0.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
      this.server = HttpServer.create(new InetSocketAddress(var2.bindAddress(), var2.bridgePort()), 16);
      HmacAuth var5 = new HmacAuth(var2.sharedSecret());
      this.server.createContext("/v1/bridge/place", new SignedJsonHandler<ApiMessages.PlaceRequest>(var5, ApiMessages.PlaceRequest.class) {
         {
            Objects.requireNonNull(BridgeHttpServer.this);
         }

         protected Object process(ApiMessages.PlaceRequest var1x) throws Exception {
            return BridgeHttpServer.this.place(var1x);
         }

         @Override
         protected void onFailure(Exception var1x) {
            var1.getLogger().log(Level.WARNING, "Bot placement request failed", var1x);
         }
      });
      this.server.createContext("/v1/bridge/refund", new SignedJsonHandler<ApiMessages.RefundRequest>(var5, ApiMessages.RefundRequest.class) {
         {
            Objects.requireNonNull(BridgeHttpServer.this);
         }

         protected Object process(ApiMessages.RefundRequest var1x) throws Exception {
            return BridgeHttpServer.this.refund(var1x);
         }

         @Override
         protected void onFailure(Exception var1x) {
            var1.getLogger().log(Level.WARNING, "Bot refund request failed", var1x);
         }
      });
      this.server.setExecutor(this.executor);
   }

   void start() {
      this.server.start();
   }

   private ApiMessages.BridgeResponse place(ApiMessages.PlaceRequest var1) throws Exception {
      if (var1 == null
         || var1.leaseId() == null
         || var1.ownerUuid() == null
         || var1.location() == null
         || var1.botUsername() == null
         || !this.botUsernames.contains(var1.botUsername().toLowerCase(Locale.ROOT))) {
         return ApiMessages.BridgeResponse.fail("INVALID_BOT", "Invalid rental bot");
      }

      if (!this.serverId.equals(var1.location().server())) {
         return ApiMessages.BridgeResponse.fail("WRONG_SERVER", "Placement is for " + var1.location().server() + ", this bridge is " + this.serverId);
      }

      CompletableFuture var2 = new CompletableFuture();
      Bukkit.getScheduler().runTask(this.plugin, () -> {
         try {
            var2.complete(this.placeNow(var1.botUsername(), var1.location(), var1));
         } catch (Throwable var4) {
            var2.completeExceptionally(var4);
         }
      });
      return ((BridgeHttpServer.PlaceOutcome)var2.get(5L, TimeUnit.SECONDS)).response();
   }

   private BridgeHttpServer.PlaceOutcome placeNow(String var1, LocationData var2, ApiMessages.PlaceRequest request) {
      Player var3 = Bukkit.getPlayerExact(var1);
      World var4 = Bukkit.getWorld(var2.world());
      if (var3 == null || !var3.isOnline()) {
         return BridgeHttpServer.PlaceOutcome.fail("BOT_NOT_READY", "Bot " + var1 + " is not online on " + this.serverId);
      }

      if (var4 == null) {
         return BridgeHttpServer.PlaceOutcome.fail("WORLD_NOT_FOUND", "World " + var2.world() + " is not loaded on " + this.serverId);
      }

      if (!(var2.y() < var4.getMinHeight()) && !(var2.y() > var4.getMaxHeight() + 16)) {
         Location var5 = new Location(var4, var2.x(), var2.y(), var2.z(), var2.yaw(), var2.pitch());
         if (!var4.getWorldBorder().isInside(var5)) {
            return BridgeHttpServer.PlaceOutcome.fail("OUTSIDE_WORLD_BORDER", "Target is outside the world border for " + var2.world());
         } else {
            var3.setInvulnerable(true);
            // A headless bot never sends the movement packets that would land it, so a spot even slightly above the
            // ground reads as 'floating' and the server kicks it after 80 ticks. Stand it on the block and allow flight.
            var3.setAllowFlight(true);
            double standY = snapToGround(var4, var2.x(), var2.y(), var2.z());
            var3.setCollidable(false);
            var3.setCanPickupItems(false);
            var3.setSleepingIgnored(true);
            this.applyLoadRadius(var3, request);
            this.combat.configure(var1, request);
            String var6 = String.format(
               Locale.ROOT,
               "minecraft:execute as %s in %s run minecraft:tp @s %.4f %.4f %.4f %.4f %.4f",
               var1,
               var4.getKey(),
               var2.x(),
               standY,
               var2.z(),
               var2.yaw(),
               var2.pitch()
            );
            boolean var7 = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), var6);
            if (!var7) {
               this.plugin
                  .getLogger()
                  .warning(
                     "Could not teleport rental bot "
                        + var1
                        + " to "
                        + var2.world()
                        + " "
                        + var2.x()
                        + ", "
                        + var2.y()
                        + ", "
                        + var2.z()
                        + " (command rejected)"
                  );
               return BridgeHttpServer.PlaceOutcome.fail("TELEPORT_REJECTED", "Teleport command was rejected");
            } else {
               return BridgeHttpServer.PlaceOutcome.ok();
            }
         }
      } else {
         return BridgeHttpServer.PlaceOutcome.fail("OUT_OF_BOUNDS", "Target Y is outside the world height for " + var2.world());
      }
   }

   /**
    * The Y of the top of the first solid block within 3 blocks below the point (so a player who bought the package
    * mid-jump gets a bot standing on the floor); the original Y when there is nothing close underneath.
    */
   static double snapToGround(World world, double x, double y, double z) {
      org.bukkit.util.RayTraceResult hit = world.rayTraceBlocks(
         new Location(world, x, y + 0.2D, z), new org.bukkit.util.Vector(0.0D, -1.0D, 0.0D), 3.2D,
         org.bukkit.FluidCollisionMode.NEVER, true);
      return hit != null && hit.getHitPosition() != null ? hit.getHitPosition().getY() : y;
   }

   /**
    * Long-term bots keep an area loaded; the smaller the radius, the cheaper they are for the server. Hourly
    * rentals send no distances and keep the server defaults. Safe to call on every (re)placement.
    */
   private void applyLoadRadius(Player bot, ApiMessages.PlaceRequest request) {
      int simulation = request.simulationDistance();
      int view = request.viewDistance();
      if (simulation >= 2) {
         bot.setSimulationDistance(simulation);
         // Chunks between the simulation and the view distance load but never tick, and mobs that wander into them
         // freeze there for good. Keeping the two equal leaves only the thin border the server always adds.
         if (view > simulation && this.plugin.getConfig().getBoolean("load-radius.view-equals-simulation", true)) {
            view = simulation;
         }
         this.ringSweeper.track(bot);
      }

      if (view >= 2) {
         bot.setViewDistance(view);
         bot.setSendViewDistance(view);
      }
   }

   private ApiMessages.BridgeResponse refund(ApiMessages.RefundRequest var1) throws Exception {
      if (var1 == null || var1.refundId() == null || var1.leaseId() == null || var1.ownerUuid() == null || var1.coins() < 0L || var1.coins() > 1000000000L) {
         return ApiMessages.BridgeResponse.fail("BAD_REFUND", "Invalid refund");
      }

      if (this.refunds.contains(var1.refundId())) {
         return ApiMessages.BridgeResponse.ok("Refund already processed");
      }

      if (var1.coins() == 0L) {
         this.refunds.record(var1.refundId(), 0L);
         return ApiMessages.BridgeResponse.ok("Nothing to refund");
      }

      boolean var2 = this.economy.deposit(var1.ownerUuid(), var1.coins()).get(8L, TimeUnit.SECONDS);
      if (!var2) {
         return ApiMessages.BridgeResponse.fail("ECONOMY_REJECTED", "Economy rejected refund");
      }

      this.refunds.record(var1.refundId(), var1.coins());
      this.plugin.getLogger().info("Refunded " + var1.coins() + " ellan_coin to " + var1.ownerUuid() + " for lease " + var1.leaseId());
      return ApiMessages.BridgeResponse.ok("Refund processed");
   }

   @Override
   public void close() {
      this.server.stop(1);
      this.executor.close();
   }

   private record PlaceOutcome(boolean success, String code, String message) {
      private static BridgeHttpServer.PlaceOutcome ok() {
         return new BridgeHttpServer.PlaceOutcome(true, "OK", "Bot placed");
      }

      private static BridgeHttpServer.PlaceOutcome fail(String var0, String var1) {
         return new BridgeHttpServer.PlaceOutcome(false, var0, var1);
      }

      private ApiMessages.BridgeResponse response() {
         return this.success ? ApiMessages.BridgeResponse.ok(this.message) : ApiMessages.BridgeResponse.fail(this.code, this.message);
      }
   }
}
