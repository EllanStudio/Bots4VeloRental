package dev.ellan.botrental.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

public final class SignedHttpClient {
   private final URI baseUri;
   private final HmacAuth auth;
   private final HttpClient client;

   public SignedHttpClient(String var1, String var2) {
      this.baseUri = URI.create(var1.endsWith("/") ? var1.substring(0, var1.length() - 1) : var1);
      this.auth = new HmacAuth(var2);
      this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3L)).build();
   }

   public <T> T post(String var1, Object var2, Class<T> var3) throws Exception {
      byte[] var4 = Json.write(var2);
      HmacAuth.SignedHeaders var5 = this.auth.sign("POST", var1, var4);
      HttpRequest var6 = HttpRequest.newBuilder(this.baseUri.resolve(var1))
         .timeout(Duration.ofSeconds(8L))
         .header("Content-Type", "application/json; charset=utf-8")
         .header("X-BotRental-Timestamp", Long.toString(var5.timestamp()))
         .header("X-BotRental-Nonce", var5.nonce())
         .header("X-BotRental-Signature", var5.signature())
         .POST(BodyPublishers.ofByteArray(var4))
         .build();
      HttpResponse var7 = this.client.send(var6, BodyHandlers.ofByteArray());
      if (var7.statusCode() >= 200 && var7.statusCode() < 300) {
         return Json.read((byte[])var7.body(), var3);
      } else {
         throw new IllegalStateException("HTTP " + var7.statusCode() + " from " + var1);
      }
   }
}
