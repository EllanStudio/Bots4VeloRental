package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.ApiMessages.ActionResponse;
import dev.ellan.botrental.common.ApiMessages.StatusResponse;
import dev.ellan.botrental.common.LeaseView;
import dev.ellan.botrental.common.LocationData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class BotRentalCommand implements CommandExecutor, TabCompleter {
    private final BotRentalPaperPlugin plugin;
    private final Set<UUID> busy = java.util.Collections.synchronizedSet(new HashSet<>());

    BotRentalCommand(BotRentalPaperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command must be used by a player.");
            return true;
        }
        if (!player.hasPermission("botrental.use")) {
            player.sendMessage(BotRentalPaperPlugin.color(BotRentalPaperPlugin.PREFIX + "&c你没有使用权限。"));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("menu") || args[0].equalsIgnoreCase("open")) {
            plugin.openMenu(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("status")) {
            submit(player, () -> showStatus(player.getUniqueId()));
            return true;
        }
        if (args[0].equalsIgnoreCase("rent") && args.length == 2) {
            Integer minutes = positiveInt(args[1]);
            if (minutes == null || !plugin.rentalConfig().rentOptions().contains(minutes)) {
                plugin.message(player.getUniqueId(), "&c可选租期：" + plugin.rentalConfig().rentOptions());
                return true;
            }
            LocationData location = BotRentalPaperPlugin.location(player.getLocation());
            submit(player, () -> rent(player.getUniqueId(), player.getName(), minutes, location));
            return true;
        }
        if (args[0].equalsIgnoreCase("cancel") && args.length == 2) {
            Integer slot = slot(args[1]);
            if (slot != null) {
                submit(player, () -> cancel(player.getUniqueId(), slot));
                return true;
            }
        }
        if (args[0].equalsIgnoreCase("relocate") && args.length == 2) {
            Integer slot = slot(args[1]);
            if (slot != null) {
                LocationData location = BotRentalPaperPlugin.location(player.getLocation());
                submit(player, () -> relocate(player.getUniqueId(), slot, location));
                return true;
            }
        }
        if (args[0].equalsIgnoreCase("topup") && args.length == 3) {
            Integer slot = slot(args[1]);
            Long coins = positiveLong(args[2]);
            if (slot != null && coins != null && plugin.rentalConfig().topUpOptions().contains(coins)) {
                submit(player, () -> topUp(player.getUniqueId(), slot, coins));
                return true;
            }
            plugin.message(player.getUniqueId(), "&c可选充值金额：" + plugin.rentalConfig().topUpOptions());
            return true;
        }
        plugin.message(player.getUniqueId(),
            "&7用法：&f/botrent &7| &f/botrent status &7| &f/botrent rent <分钟>"
                + " &7| &f/botrent topup <租赁位> <金币> &7| &f/botrent relocate <租赁位>"
                + " &7| &f/botrent cancel <租赁位>");
        return true;
    }

    private void submit(Player player, Runnable operation) {
        UUID playerId = player.getUniqueId();
        if (!busy.add(playerId)) {
            plugin.message(playerId, "&e上一个租赁操作仍在处理中，请稍候。");
            return;
        }
        plugin.runAsync(() -> {
            try {
                operation.run();
            }
            catch (Exception failure) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Rental command failed for " + playerId, failure);
                plugin.message(playerId, "&c租赁服务暂时不可用，请稍后再试。");
            }
            finally {
                busy.remove(playerId);
            }
        });
    }

    private void showStatus(UUID playerId) {
        try {
            StatusResponse status = plugin.addon().status(playerId);
            plugin.statusCache().put(playerId, status);
            plugin.message(playerId, "&7机器人池：&f" + status.poolAvailable() + "/" + status.poolSize()
                + " &7| 在线：&e" + status.onlineRate() + "/分钟 &7| 离线：&c"
                + status.offlineRate() + "/分钟");
            if (status.leases().isEmpty()) {
                plugin.message(playerId, "&7你目前没有正在运行的挂机机器人。");
            }
            for (LeaseView lease : status.leases()) {
                plugin.message(playerId, "&7租赁位 " + lease.slot() + "：&f" + lease.botUsername()
                    + " &7状态 &f" + stateName(lease.state()) + " &7余额 &e" + lease.reserveCoins()
                    + " &7约 " + lease.estimatedMinutes() + " 分钟");
            }
        }
        catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private void rent(UUID playerId, String playerName, int minutes, LocationData location) {
        try {
            StatusResponse before = plugin.addon().status(playerId);
            plugin.statusCache().put(playerId, before);
            if (before.leases().size() >= before.maxPerPlayer()) {
                plugin.message(playerId, "&c你已经租满 " + before.maxPerPlayer() + " 个机器人。");
                return;
            }
            if (before.poolAvailable() <= 0) {
                plugin.message(playerId, "&c机器人池暂时没有空位。");
                return;
            }
            long coins = Math.multiplyExact(minutes, before.onlineRate());
            if (plugin.economy().balance(playerId).get(8, TimeUnit.SECONDS) < coins) {
                plugin.message(playerId, "&c余额不足，本次需要 " + coins + " 枚艾尔岚金币。");
                return;
            }
            if (!plugin.economy().withdraw(playerId, coins).get(8, TimeUnit.SECONDS)) {
                plugin.message(playerId, "&c扣款失败，请稍后再试。");
                return;
            }
            ActionResponse response;
            try {
                response = plugin.addon().create(playerId, playerName, coins, location);
            }
            catch (Exception ambiguous) {
                StatusResponse after = plugin.addon().status(playerId);
                if (after.leases().size() > before.leases().size()) {
                    plugin.statusCache().put(playerId, after);
                    plugin.message(playerId, "&a租赁已创建，机器人正在前往你刚才的位置。");
                    return;
                }
                refundLocal(playerId, coins);
                throw ambiguous;
            }
            if (!response.success()) {
                refundLocal(playerId, coins);
                plugin.message(playerId, "&c" + response.message());
                return;
            }
            plugin.message(playerId, "&a" + response.message() + " &7已预存 &e" + coins + " &7金币。");
            plugin.refresh(playerId);
        }
        catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private void topUp(UUID playerId, int slot, long coins) {
        try {
            StatusResponse before = plugin.addon().status(playerId);
            LeaseView old = find(before, slot);
            if (old == null) {
                plugin.message(playerId, "&c没有找到这个租赁位。");
                return;
            }
            if (plugin.economy().balance(playerId).get(8, TimeUnit.SECONDS) < coins) {
                plugin.message(playerId, "&c余额不足，本次需要 " + coins + " 枚艾尔岚金币。");
                return;
            }
            if (!plugin.economy().withdraw(playerId, coins).get(8, TimeUnit.SECONDS)) {
                plugin.message(playerId, "&c扣款失败，请稍后再试。");
                return;
            }
            ActionResponse response;
            try {
                response = plugin.addon().topUp(playerId, slot, coins);
            }
            catch (Exception ambiguous) {
                LeaseView current = find(plugin.addon().status(playerId), slot);
                if (current != null && current.reserveCoins() >= old.reserveCoins() + coins) {
                    plugin.message(playerId, "&a充值成功。");
                    plugin.refresh(playerId);
                    return;
                }
                refundLocal(playerId, coins);
                throw ambiguous;
            }
            if (!response.success()) {
                refundLocal(playerId, coins);
                plugin.message(playerId, "&c" + response.message());
                return;
            }
            plugin.message(playerId, "&a" + response.message());
            plugin.refresh(playerId);
        }
        catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private void cancel(UUID playerId, int slot) {
        try {
            ActionResponse response = plugin.addon().cancel(playerId, slot);
            plugin.message(playerId, (response.success() ? "&a" : "&c") + response.message());
            plugin.refresh(playerId);
        }
        catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private void relocate(UUID playerId, int slot, LocationData location) {
        try {
            ActionResponse response = plugin.addon().relocate(playerId, slot, location);
            plugin.message(playerId, (response.success() ? "&a" : "&c") + response.message());
            plugin.refresh(playerId);
        }
        catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private void refundLocal(UUID playerId, long coins) throws Exception {
        if (!plugin.economy().deposit(playerId, coins).get(8, TimeUnit.SECONDS)) {
            plugin.getLogger().severe("URGENT: could not return " + coins + " ellan_coin to " + playerId);
            plugin.message(playerId, "&c退款暂时失败，管理员已收到记录，请勿重复操作。");
        }
    }

    private static LeaseView find(StatusResponse status, int slot) {
        return status.leases().stream().filter(lease -> lease.slot() == slot).findFirst().orElse(null);
    }

    private static String stateName(String state) {
        return switch (state) {
            case "STARTING" -> "正在启动";
            case "ACTIVE" -> "运行中";
            case "REFUND_PENDING" -> "退款处理中";
            default -> state;
        };
    }

    private static Integer slot(String value) {
        Integer parsed = positiveInt(value);
        return parsed != null && parsed <= 3 ? parsed : null;
    }

    private static Integer positiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("menu", "status", "rent", "topup", "relocate", "cancel"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("rent")) {
            return filter(plugin.rentalConfig().rentOptions().stream().map(String::valueOf).toList(), args[1]);
        }
        if (args.length == 2 && Set.of("topup", "relocate", "cancel").contains(args[0].toLowerCase())) {
            return filter(List.of("1", "2", "3"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("topup")) {
            return filter(plugin.rentalConfig().topUpOptions().stream().map(String::valueOf).toList(), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lowered = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        values.stream().filter(value -> value.toLowerCase().startsWith(lowered)).forEach(result::add);
        return result;
    }
}
