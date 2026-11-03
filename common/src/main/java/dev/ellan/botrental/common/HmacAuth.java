package dev.ellan.botrental.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacAuth {
   public static final String TIMESTAMP_HEADER = "X-BotRental-Timestamp";
   public static final String NONCE_HEADER = "X-BotRental-Nonce";
   public static final String SIGNATURE_HEADER = "X-BotRental-Signature";
   private static final Duration MAX_SKEW = Duration.ofSeconds(30L);
   private final byte[] secret;
   private final Clock clock;
   private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

   public HmacAuth(String var1) {
      this(var1, Clock.systemUTC());
   }

   HmacAuth(String var1, Clock var2) {
      if (var1 != null && var1.length() >= 32) {
         this.secret = var1.getBytes(StandardCharsets.UTF_8);
         this.clock = var2;
      } else {
         throw new IllegalArgumentException("shared secret must contain at least 32 characters");
      }
   }

   public HmacAuth.SignedHeaders sign(String var1, String var2, byte[] var3) {
      long var4 = this.clock.millis();
      String var6 = UUID.randomUUID().toString();
      return new HmacAuth.SignedHeaders(var4, var6, this.signature(var1, var2, var4, var6, var3));
   }

   public boolean verify(String var1, String var2, byte[] var3, String var4, String var5, String var6) {
      if (var4 != null && var5 != null && var6 != null && var5.length() <= 80) {
         long var7;
         try {
            var7 = Long.parseLong(var4);
         } catch (NumberFormatException var12) {
            return false;
         }

         long var9 = this.clock.millis();
         if (Math.abs(var9 - var7) > MAX_SKEW.toMillis()) {
            return false;
         }

         this.seenNonces.entrySet().removeIf(var2x -> var9 - var2x.getValue() > MAX_SKEW.toMillis() * 2L);
         if (this.seenNonces.putIfAbsent(var5, var9) != null) {
            return false;
         }

         String var11 = this.signature(var1, var2, var7, var5, var3);
         return MessageDigest.isEqual(var11.getBytes(StandardCharsets.US_ASCII), var6.getBytes(StandardCharsets.US_ASCII));
      } else {
         return false;
      }
   }

   private String signature(String var1, String var2, long var3, String var5, byte[] var6) {
      try {
         byte[] var7 = MessageDigest.getInstance("SHA-256").digest(var6 == null ? new byte[0] : var6);
         String var8 = var1.toUpperCase() + "\n" + var2 + "\n" + var3 + "\n" + var5 + "\n" + Base64.getEncoder().encodeToString(var7);
         Mac var9 = Mac.getInstance("HmacSHA256");
         var9.init(new SecretKeySpec(this.secret, "HmacSHA256"));
         return Base64.getEncoder().encodeToString(var9.doFinal(var8.getBytes(StandardCharsets.UTF_8)));
      } catch (Exception var10) {
         throw new IllegalStateException("could not sign request", var10);
      }
   }

   public record SignedHeaders(long timestamp, String nonce, String signature) {
   }
}
