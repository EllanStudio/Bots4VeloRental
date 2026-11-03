package dev.ellan.botrental.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;

public abstract class SignedJsonHandler<T> implements HttpHandler {
   private final HmacAuth auth;
   private final Class<T> requestType;

   protected SignedJsonHandler(HmacAuth var1, Class<T> var2) {
      this.auth = var1;
      this.requestType = var2;
   }

   @Override
   public final void handle(HttpExchange var1) throws IOException {
      byte[] var2 = var1.getRequestBody().readAllBytes();
      String var3 = var1.getRequestURI().getPath();
      if ("POST".equalsIgnoreCase(var1.getRequestMethod())
         && this.auth
            .verify(
               var1.getRequestMethod(),
               var3,
               var2,
               var1.getRequestHeaders().getFirst("X-BotRental-Timestamp"),
               var1.getRequestHeaders().getFirst("X-BotRental-Nonce"),
               var1.getRequestHeaders().getFirst("X-BotRental-Signature")
            )) {
         try {
            Object var4 = Json.read(var2, this.requestType);
            write(var1, 200, this.process((T)var4));
         } catch (IllegalArgumentException var5) {
            write(var1, 400, ApiMessages.BridgeResponse.fail("BAD_REQUEST", var5.getMessage()));
         } catch (Exception var6) {
            write(var1, 500, ApiMessages.BridgeResponse.fail("INTERNAL_ERROR", "Internal error"));
            this.onFailure(var6);
         }
      } else {
         write(var1, 401, ApiMessages.BridgeResponse.fail("UNAUTHORIZED", "Unauthorized"));
      }
   }

   protected abstract Object process(T var1) throws Exception;

   protected void onFailure(Exception var1) {
   }

   private static void write(HttpExchange var0, int var1, Object var2) throws IOException {
      byte[] var3 = Json.write(var2);
      var0.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      var0.sendResponseHeaders(var1, var3.length);

      try (OutputStream var4 = var0.getResponseBody()) {
         var4.write(var3);
      }
   }
}
