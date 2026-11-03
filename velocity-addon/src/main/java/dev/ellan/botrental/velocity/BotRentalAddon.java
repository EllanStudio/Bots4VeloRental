package dev.ellan.botrental.velocity;

import dev.nulli0n.vbot.addon.api.AddonBotEvent;
import dev.nulli0n.vbot.addon.api.AddonContext;
import dev.nulli0n.vbot.addon.api.Bots4VeloAddon;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class BotRentalAddon implements Bots4VeloAddon {
   private AddonContext context;
   private LeaseManager leases;
   private RentalHttpServer httpServer;
   private ScheduledExecutorService scheduler;
   private Consumer<AddonBotEvent> eventListener;
   private Path configFile;
   private long configStamp;
   private int configChecks;

   public String id() {
      return "bot-rental";
   }

   public String version() {
      try (InputStream var2 = this.getClass().getResourceAsStream("/bot-rental-version.properties")) {
         Properties var3 = new Properties();
         if (var2 != null) {
            var3.load(var2);
         }

         return var3.getProperty("version", "development");
      } catch (Exception var7) {
         return "development";
      }
   }

   public void onLoad(AddonContext var1) throws Exception {
      this.context = var1;
      Files.createDirectories(this.context.dataDirectory());
      RentalConfig var2 = RentalConfig.load(this.context.dataDirectory());
      if (var2.pool().size() == 10 && var2.maxPerPlayer() >= 1 && var2.maxPerPlayer() <= 5) {
         LeaseStore var3 = new LeaseStore(this.context.dataDirectory().resolve("rentals.db"));
         this.leases = new LeaseManager(var2, this.context.bots(), new HttpRentalBridge(var2), var3, this.context.logger());
         this.eventListener = this.leases::onBotEvent;
         this.context.bots().addEventListener(this.eventListener);
         this.httpServer = new RentalHttpServer(var2, this.leases, this.context.logger());
         this.httpServer.start();
         this.leases.start();
         this.configFile = this.context.dataDirectory().resolve("config.yml");
         this.configStamp = this.stampOf(this.configFile);
         this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("bot-rental-tick").daemon(true).factory());
         this.scheduler.scheduleAtFixedRate(() -> {
            try {
               this.leases.tick();
               this.checkConfigReload();
            } catch (Throwable var2x) {
               this.context.logger().error("Rental tick failed; keeping the scheduler alive", var2x);
            }
         }, 1L, 1L, TimeUnit.SECONDS);
         this.context
            .logger()
            .info(
               "Rental service listening on "
                  + var2.bindAddress()
                  + ":"
                  + var2.port()
                  + " with "
                  + var2.pool().size()
                  + " bot slots shared by "
                  + var2.bridges().keySet()
            );
      } else {
         throw new IllegalArgumentException("this deployment requires pool=10 and max-per-player between 1 and 5");
      }
   }

   private long stampOf(Path file) {
      try {
         return Files.getLastModifiedTime(file).toMillis() * 31L + Files.size(file);
      } catch (Exception unreadable) {
         return -1L;
      }
   }

   /**
    * Prices live in config.yml and can be edited while the proxy runs: when the file changes (and has been quiet for two
    * seconds, so a half-written save is never read) it is parsed and, if valid, applied. A broken or restart-only edit
    * is logged and ignored; the running prices stay in force.
    */
   private void checkConfigReload() {
      if (++this.configChecks % 3 != 0 || this.configFile == null) {
         return;
      }

      try {
         long stamp = this.stampOf(this.configFile);
         if (stamp == this.configStamp || stamp < 0L) {
            return;
         }

         if (System.currentTimeMillis() - Files.getLastModifiedTime(this.configFile).toMillis() < 2000L) {
            return;
         }

         this.configStamp = stamp;
         String applied = this.leases.reloadPricing(RentalConfig.load(this.context.dataDirectory()));
         this.context.logger().info("Rental prices reloaded: " + applied);
      } catch (Exception rejected) {
         this.context.logger().warn("Rental config change ignored, current prices stay in force: " + rejected.getMessage());
      }
   }

   public void onUnload() throws Exception {
      if (this.scheduler != null) {
         this.scheduler.shutdownNow();
         this.scheduler.awaitTermination(2L, TimeUnit.SECONDS);
         this.scheduler = null;
      }

      if (this.httpServer != null) {
         this.httpServer.close();
         this.httpServer = null;
      }

      if (this.context != null && this.eventListener != null) {
         this.context.bots().removeEventListener(this.eventListener);
         this.eventListener = null;
      }

      if (this.leases != null) {
         this.leases.close();
         this.leases = null;
      }

      this.context = null;
   }
}
