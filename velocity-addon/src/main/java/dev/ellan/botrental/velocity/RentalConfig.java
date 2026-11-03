package dev.ellan.botrental.velocity;

import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

record RentalConfig(
   String bindAddress,
   int port,
   String sharedSecret,
   Map<String, String> bridges,
   String currency,
   long onlineRate,
   long offlineRate,
   int maxPerPlayer,
   long billingPeriodSeconds,
   long startupTimeoutSeconds,
   List<String> pool,
   PackageConfig packages
) {
   /** Original constructor: no long-term packages. */
   RentalConfig(
      String bindAddress, int port, String sharedSecret, Map<String, String> bridges, String currency,
      long onlineRate, long offlineRate, int maxPerPlayer, long billingPeriodSeconds, long startupTimeoutSeconds,
      List<String> pool
   ) {
      this(bindAddress, port, sharedSecret, bridges, currency, onlineRate, offlineRate, maxPerPlayer,
         billingPeriodSeconds, startupTimeoutSeconds, pool, PackageConfig.disabled());
   }

   static RentalConfig load(Path var0) throws IOException {
      Path var1 = var0.resolve("config.yml");
      if (Files.notExists(var1)) {
         try (InputStream var2 = RentalConfig.class.getResourceAsStream("/config.yml")) {
            if (var2 == null) {
               throw new IOException("bundled config.yml is missing");
            }

            Files.copy(var2, var1);
         }
      }

      Map<String, Object> var12;
      try (InputStream var3 = Files.newInputStream(var1)) {
         var12 = (Map<String, Object>)new Yaml().load(var3);
      }

      Map<String, Object> var13 = section(var12, "http");
      Map<String, Object> var4 = section(var12, "bridge");
      Map<String, Object> var5 = section(var12, "rental");
      List<String> var6 = list(var5, "pool").stream().map(String::trim).filter(var0x -> !var0x.isEmpty()).toList();
      RentalConfig var7 = new RentalConfig(
         string(var13, "bind-address"),
         integer(var13, "port"),
         string(var4, "shared-secret"),
         bridges(var4, var5),
         string(var5, "currency"),
         number(var5, "online-rate-per-minute"),
         number(var5, "offline-rate-per-minute"),
         integer(var5, "max-per-player"),
         number(var5, "billing-period-seconds"),
         number(var5, "startup-timeout-seconds"),
         var6,
         PackageConfig.parse(var12)
      );
      var7.validate();
      return var7;
   }

   private void validate() {
      if (!this.bindAddress.equals("127.0.0.1") && !this.bindAddress.equals("::1") && !this.bindAddress.equals("localhost")) {
         throw new IllegalArgumentException("http.bind-address must be loopback");
      }

      if (this.port >= 1024 && this.port <= 65535 && !this.bridges.isEmpty() && !this.currency.isBlank()) {
         if (this.sharedSecret.length() < 32 || this.sharedSecret.startsWith("CHANGE_ME")) {
            throw new IllegalArgumentException("bridge.shared-secret must be replaced with a random secret");
         }

         if (this.onlineRate > 0L && this.offlineRate > 0L && this.maxPerPlayer > 0 && this.billingPeriodSeconds > 0L && this.startupTimeoutSeconds >= 10L) {
            if (this.pool.isEmpty() || new LinkedHashSet<>(this.pool).size() != this.pool.size()) {
               throw new IllegalArgumentException("rental.pool must contain unique bot ids");
            }

            if (this.packages.enabled()) {
               for (String botId : this.packages.pool()) {
                  if (this.pool.stream().anyMatch(botId::equalsIgnoreCase)) {
                     throw new IllegalArgumentException("packages.pool overlaps rental.pool: " + botId);
                  }
               }

               for (String server : this.packages.allowedServers()) {
                  if (!this.bridges.containsKey(server)) {
                     throw new IllegalArgumentException("packages.allowed-servers has no bridge: " + server);
                  }
               }
            }
         } else {
            throw new IllegalArgumentException("rental rates and limits must be positive");
         }
      } else {
         throw new IllegalArgumentException("invalid port, bridge servers, or currency");
      }
   }

   boolean allows(String var1) {
      return var1 != null && this.bridges.containsKey(var1.toLowerCase(Locale.ROOT));
   }

   private static Map<String, String> bridges(Map<String, Object> var0, Map<String, Object> var1) {
      LinkedHashMap var2 = new LinkedHashMap();
      if (var0.get("servers") instanceof Map var3) {
         var3.forEach((var1x, var2x) -> {
            String var3x = var1x == null ? "" : var1x.toString().trim().toLowerCase(Locale.ROOT);
            String var4 = var2x == null ? "" : var2x.toString().trim();
            if (!var3x.isEmpty() && !var4.isEmpty()) {
               var2.put(var3x, var4);
            } else {
               throw new IllegalArgumentException("bridge.servers entries need a name and a base URL");
            }
         });
      } else {
         var2.put(string(var1, "allowed-server").toLowerCase(Locale.ROOT), string(var0, "base-url"));
      }

      return Collections.unmodifiableMap(var2);
   }

   private static Map<String, Object> section(Map<String, Object> var0, String var1) {
      if ((var0 == null ? null : var0.get(var1)) instanceof Map var2) {
         return var2;
      } else {
         throw new IllegalArgumentException("missing config section: " + var1);
      }
   }

   private static String string(Map<String, Object> var0, String var1) {
      Object var2 = var0.get(var1);
      if (var2 != null && !var2.toString().trim().isEmpty()) {
         return var2.toString().trim();
      } else {
         throw new IllegalArgumentException("missing config value: " + var1);
      }
   }

   private static long number(Map<String, Object> var0, String var1) {
      return var0.get(var1) instanceof Number var2 ? var2.longValue() : Long.parseLong(string(var0, var1));
   }

   private static int integer(Map<String, Object> var0, String var1) {
      return Math.toIntExact(number(var0, var1));
   }

   private static List<String> list(Map<String, Object> var0, String var1) {
      if (var0.get(var1) instanceof List var2) {
         return var2;
      } else {
         throw new IllegalArgumentException("missing list: " + var1);
      }
   }
}
