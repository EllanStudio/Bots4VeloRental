package dev.ellan.botrental.paper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

record PaperRentalConfig(
   String serverId,
   String currency,
   String menuName,
   String addonBaseUrl,
   String bindAddress,
   int bridgePort,
   String sharedSecret,
   List<Integer> rentOptions,
   List<Long> topUpOptions,
   Set<String> botUsernames,
   Map<String, String> serverDisplayNames
) {
   static PaperRentalConfig load(FileConfiguration var0) {
      PaperRentalConfig var1 = new PaperRentalConfig(
         var0.getString("server-id", "").trim().toLowerCase(Locale.ROOT),
         var0.getString("currency", ""),
         var0.getString("menu-name", "bot_rental"),
         var0.getString("addon.base-url", ""),
         var0.getString("bridge.bind-address", ""),
         var0.getInt("bridge.port"),
         var0.getString("bridge.shared-secret", ""),
         var0.getIntegerList("rent-options-minutes"),
         var0.getLongList("topup-options-coins"),
         new HashSet<>(var0.getStringList("bot-usernames")),
         displayNames(var0)
      );
      var1.validate();
      return var1;
   }

   private void validate() {
      if (this.serverId.isBlank()) {
         throw new IllegalArgumentException("server-id must match this backend's name in Velocity");
      } else if (this.currency.isBlank() || this.menuName.isBlank() || this.addonBaseUrl.isBlank()) {
         throw new IllegalArgumentException("currency, menu-name and addon.base-url are required");
      } else if (!this.bindAddress.equals("127.0.0.1") && !this.bindAddress.equals("::1") && !this.bindAddress.equals("localhost")) {
         throw new IllegalArgumentException("bridge.bind-address must be loopback");
      } else if (this.bridgePort < 1024 || this.bridgePort > 65535 || this.sharedSecret.length() < 32 || this.sharedSecret.startsWith("CHANGE_ME")) {
         throw new IllegalArgumentException("invalid bridge port or shared secret");
      } else if (this.rentOptions.isEmpty()
         || this.rentOptions.stream().anyMatch(var0 -> var0 <= 0)
         || this.topUpOptions.isEmpty()
         || this.topUpOptions.stream().anyMatch(var0 -> var0 <= 0L)
         || this.botUsernames.isEmpty()) {
         throw new IllegalArgumentException("rent options, top-up options and at least one bot username are required");
      }
   }

   String displayName(String var1) {
      return this.serverDisplayNames.getOrDefault(var1.toLowerCase(Locale.ROOT), var1);
   }

   private static Map<String, String> displayNames(FileConfiguration var0) {
      LinkedHashMap var1 = new LinkedHashMap();
      ConfigurationSection var2 = var0.getConfigurationSection("server-display-names");
      if (var2 != null) {
         for (String var4 : var2.getKeys(false)) {
            var1.put(var4.toLowerCase(Locale.ROOT), var2.getString(var4, var4));
         }
      }

      return Map.copyOf(var1);
   }
}
