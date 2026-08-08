package dev.ellan.botrental.paper;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    Set<String> botUsernames
) {
    static PaperRentalConfig load(FileConfiguration config) {
        PaperRentalConfig result = new PaperRentalConfig(
            config.getString("server-id", ""),
            config.getString("currency", ""),
            config.getString("menu-name", "bot_rental"),
            config.getString("addon.base-url", ""),
            config.getString("bridge.bind-address", ""),
            config.getInt("bridge.port"),
            config.getString("bridge.shared-secret", ""),
            config.getIntegerList("rent-options-minutes"),
            config.getLongList("topup-options-coins"),
            new HashSet<>(config.getStringList("bot-usernames")));
        result.validate();
        return result;
    }

    private void validate() {
        if (!serverId.equalsIgnoreCase("redstone")) {
            throw new IllegalArgumentException("server-id must be redstone");
        }
        if (currency.isBlank() || menuName.isBlank() || addonBaseUrl.isBlank()) {
            throw new IllegalArgumentException("currency, menu-name and addon.base-url are required");
        }
        if (!bindAddress.equals("127.0.0.1") && !bindAddress.equals("::1")
            && !bindAddress.equals("localhost")) {
            throw new IllegalArgumentException("bridge.bind-address must be loopback");
        }
        if (bridgePort < 1024 || bridgePort > 65535 || sharedSecret.length() < 32
            || sharedSecret.startsWith("CHANGE_ME")) {
            throw new IllegalArgumentException("invalid bridge port or shared secret");
        }
        if (rentOptions.isEmpty() || rentOptions.stream().anyMatch(value -> value <= 0)
            || topUpOptions.isEmpty() || topUpOptions.stream().anyMatch(value -> value <= 0)
            || botUsernames.size() != 10) {
            throw new IllegalArgumentException("rent options, top-up options and exactly 10 bot usernames are required");
        }
    }
}
