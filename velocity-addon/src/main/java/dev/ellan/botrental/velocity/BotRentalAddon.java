package dev.ellan.botrental.velocity;

import dev.nulli0n.vbot.addon.api.AddonBotEvent;
import dev.nulli0n.vbot.addon.api.AddonContext;
import dev.nulli0n.vbot.addon.api.Bots4VeloAddon;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class BotRentalAddon implements Bots4VeloAddon {
    private AddonContext context;
    private LeaseManager leases;
    private RentalHttpServer httpServer;
    private ScheduledExecutorService scheduler;
    private Consumer<AddonBotEvent> eventListener;

    @Override
    public String id() {
        return "bot-rental";
    }

    @Override
    public String version() {
        try (InputStream input = getClass().getResourceAsStream("/bot-rental-version.properties")) {
            Properties properties = new Properties();
            if (input != null) {
                properties.load(input);
            }
            return properties.getProperty("version", "development");
        }
        catch (Exception ignored) {
            return "development";
        }
    }

    @Override
    public void onLoad(AddonContext supplied) throws Exception {
        context = supplied;
        Files.createDirectories(context.dataDirectory());
        RentalConfig config = RentalConfig.load(context.dataDirectory());
        if (config.pool().size() != 10 || config.maxPerPlayer() != 3
            || !config.allowedServer().equalsIgnoreCase("redstone")) {
            throw new IllegalArgumentException(
                "this deployment requires pool=10, max-per-player=3 and allowed-server=redstone");
        }
        LeaseStore store = new LeaseStore(context.dataDirectory().resolve("rentals.db"));
        leases = new LeaseManager(config, context.bots(), new HttpRentalBridge(config), store, context.logger());
        eventListener = leases::onBotEvent;
        context.bots().addEventListener(eventListener);

        httpServer = new RentalHttpServer(config, leases, context.logger());
        httpServer.start();
        leases.start();

        scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("bot-rental-tick").daemon(true).factory());
        scheduler.scheduleAtFixedRate(leases::tick, 1, 1, TimeUnit.SECONDS);
        context.logger().info("Rental service listening on " + config.bindAddress() + ":" + config.port()
            + " with " + config.pool().size() + " redstone bot slots");
    }

    @Override
    public void onUnload() throws Exception {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
            scheduler = null;
        }
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
        if (context != null && eventListener != null) {
            context.bots().removeEventListener(eventListener);
            eventListener = null;
        }
        if (leases != null) {
            leases.close();
            leases = null;
        }
        context = null;
    }
}
