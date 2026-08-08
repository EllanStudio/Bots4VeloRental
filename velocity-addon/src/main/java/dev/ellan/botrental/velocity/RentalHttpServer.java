package dev.ellan.botrental.velocity;

import com.sun.net.httpserver.HttpServer;
import dev.ellan.botrental.common.ApiMessages.CreateRequest;
import dev.ellan.botrental.common.ApiMessages.OwnerSlotRequest;
import dev.ellan.botrental.common.ApiMessages.RelocateRequest;
import dev.ellan.botrental.common.ApiMessages.StatusRequest;
import dev.ellan.botrental.common.ApiMessages.TopUpRequest;
import dev.ellan.botrental.common.HmacAuth;
import dev.ellan.botrental.common.SignedJsonHandler;
import dev.nulli0n.vbot.addon.api.AddonLogger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RentalHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    RentalHttpServer(RentalConfig config, LeaseManager manager, AddonLogger logger) throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 32);
        HmacAuth auth = new HmacAuth(config.sharedSecret());
        server.createContext("/v1/leases/create", handler(auth, CreateRequest.class, manager::create, logger));
        server.createContext("/v1/leases/cancel", handler(auth, OwnerSlotRequest.class, manager::cancel, logger));
        server.createContext("/v1/leases/topup", handler(auth, TopUpRequest.class, manager::topUp, logger));
        server.createContext("/v1/leases/relocate", handler(auth, RelocateRequest.class, manager::relocate, logger));
        server.createContext("/v1/leases/status", handler(auth, StatusRequest.class, manager::status, logger));
        server.setExecutor(executor);
    }

    void start() {
        server.start();
    }

    private static <T> SignedJsonHandler<T> handler(HmacAuth auth, Class<T> type,
                                                     Processor<T> processor, AddonLogger logger) {
        return new SignedJsonHandler<>(auth, type) {
            @Override
            protected Object process(T request) {
                return processor.process(request);
            }

            @Override
            protected void onFailure(Exception failure) {
                logger.error("Rental HTTP request failed", failure);
            }
        };
    }

    @Override
    public void close() {
        server.stop(1);
        executor.close();
    }

    @FunctionalInterface
    private interface Processor<T> {
        Object process(T request);
    }
}
