package dev.ellan.botrental.velocity;

import com.sun.net.httpserver.HttpServer;
import dev.ellan.botrental.common.ApiMessages;
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

   RentalHttpServer(RentalConfig var1, LeaseManager var2, AddonLogger var3) throws IOException {
      this.server = HttpServer.create(new InetSocketAddress(var1.bindAddress(), var1.port()), 32);
      HmacAuth var4 = new HmacAuth(var1.sharedSecret());
      this.server.createContext("/v1/leases/create", handler(var4, ApiMessages.CreateRequest.class, var2::create, var3));
      this.server.createContext("/v1/leases/cancel", handler(var4, ApiMessages.OwnerSlotRequest.class, var2::cancel, var3));
      this.server.createContext("/v1/leases/topup", handler(var4, ApiMessages.TopUpRequest.class, var2::topUp, var3));
      this.server.createContext("/v1/leases/relocate", handler(var4, ApiMessages.RelocateRequest.class, var2::relocate, var3));
      this.server.createContext("/v1/leases/status", handler(var4, ApiMessages.StatusRequest.class, var2::status, var3));
      this.server.setExecutor(this.executor);
   }

   void start() {
      this.server.start();
   }

   private static <T> SignedJsonHandler<T> handler(HmacAuth var0, Class<T> var1, final RentalHttpServer.Processor<T> var2, final AddonLogger var3) {
      return new SignedJsonHandler<T>(var0, var1) {
         @Override
         protected Object process(T var1) {
            return var2.process(var1);
         }

         @Override
         protected void onFailure(Exception var1) {
            var3.error("Rental HTTP request failed", var1);
         }
      };
   }

   @Override
   public void close() {
      this.server.stop(1);
      this.executor.close();
   }

   @FunctionalInterface
   private interface Processor<T> {
      Object process(T var1);
   }
}
