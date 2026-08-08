package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.ApiMessages.StatusResponse;
import dev.ellan.botrental.common.LeaseView;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class RentalPlaceholders extends PlaceholderExpansion {
    private final BotRentalPaperPlugin plugin;

    RentalPlaceholders(BotRentalPaperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "botrental";
    }

    @Override
    public @NotNull String getAuthor() {
        return "EllanServer";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String parameters) {
        if (player == null) {
            return "";
        }
        StatusResponse status = plugin.statusCache().get(player.getUniqueId()).orElse(null);
        if (status == null) {
            plugin.refresh(player.getUniqueId());
            return "加载中";
        }
        return switch (parameters.toLowerCase(java.util.Locale.ROOT)) {
            case "pool_available" -> Integer.toString(status.poolAvailable());
            case "pool_size" -> Integer.toString(status.poolSize());
            case "active_count" -> Integer.toString(status.leases().size());
            case "max_per_player" -> Integer.toString(status.maxPerPlayer());
            case "online_rate" -> Long.toString(status.onlineRate());
            case "offline_rate" -> Long.toString(status.offlineRate());
            default -> slotValue(status, parameters);
        };
    }

    private static String slotValue(StatusResponse status, String parameter) {
        String[] parts = parameter.toLowerCase(java.util.Locale.ROOT).split("_", 3);
        if (parts.length != 3 || !parts[0].equals("slot")) {
            return null;
        }
        int slot;
        try {
            slot = Integer.parseInt(parts[1]);
        }
        catch (NumberFormatException ignored) {
            return "";
        }
        LeaseView lease = status.leases().stream().filter(value -> value.slot() == slot).findFirst().orElse(null);
        if (lease == null) {
            return switch (parts[2]) {
                case "status" -> "空闲";
                case "bot" -> "暂无";
                default -> "0";
            };
        }
        return switch (parts[2]) {
            case "status" -> stateName(lease.state());
            case "bot" -> lease.botUsername();
            case "reserve" -> Long.toString(lease.reserveCoins());
            case "spent" -> Long.toString(lease.spentCoins());
            case "rate" -> Long.toString(lease.currentRate());
            case "minutes" -> Long.toString(lease.estimatedMinutes());
            case "world" -> lease.location().world();
            case "location" -> String.format(java.util.Locale.ROOT, "%s %.1f %.1f %.1f",
                lease.location().world(), lease.location().x(), lease.location().y(), lease.location().z());
            default -> "";
        };
    }

    private static String stateName(String state) {
        return switch (state) {
            case "STARTING" -> "正在启动";
            case "ACTIVE" -> "运行中";
            case "REFUND_PENDING" -> "退款处理中";
            default -> state;
        };
    }
}
