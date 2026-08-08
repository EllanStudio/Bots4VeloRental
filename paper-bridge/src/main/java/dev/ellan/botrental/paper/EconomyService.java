package dev.ellan.botrental.paper;

import org.bukkit.Bukkit;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.operation.NotificationTarget;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationContext;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class EconomyService {
    private final ExcellentEconomyAPI api;
    private final String currency;
    private final OperationContext context = OperationContext.custom("Bots4VeloRental")
        .silentFor(NotificationTarget.EXECUTOR);

    EconomyService(String currency) {
        var registration = Bukkit.getServicesManager().getRegistration(ExcellentEconomyAPI.class);
        if (registration == null) {
            throw new IllegalStateException("ExcellentEconomy API service is unavailable");
        }
        api = registration.getProvider();
        this.currency = currency;
        if (!api.hasCurrency(currency)) {
            throw new IllegalStateException("ExcellentEconomy currency does not exist: " + currency);
        }
    }

    CompletableFuture<Double> balance(UUID player) {
        return api.getBalanceAsync(player, currency);
    }

    CompletableFuture<Boolean> withdraw(UUID player, long coins) {
        if (coins <= 0 || !api.canPerformOperations()) {
            return CompletableFuture.completedFuture(false);
        }
        return api.withdrawAsync(player, currency, coins, context).thenApply(OperationResult::success);
    }

    CompletableFuture<Boolean> deposit(UUID player, long coins) {
        if (coins <= 0) {
            return CompletableFuture.completedFuture(true);
        }
        if (!api.canPerformOperations()) {
            return CompletableFuture.completedFuture(false);
        }
        return api.depositAsync(player, currency, coins, context).thenApply(OperationResult::success);
    }
}
