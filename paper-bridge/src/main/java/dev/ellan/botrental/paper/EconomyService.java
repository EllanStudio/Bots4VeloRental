package dev.ellan.botrental.paper;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.operation.NotificationTarget;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationContext;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationResult;

final class EconomyService {
   private final ExcellentEconomyAPI api;
   private final String currency;
   private final OperationContext context = OperationContext.custom("Bots4VeloRental").silentFor(new NotificationTarget[]{NotificationTarget.EXECUTOR});

   EconomyService(String var1) {
      RegisteredServiceProvider var2 = Bukkit.getServicesManager().getRegistration(ExcellentEconomyAPI.class);
      if (var2 == null) {
         throw new IllegalStateException("ExcellentEconomy API service is unavailable");
      }

      this.api = (ExcellentEconomyAPI)var2.getProvider();
      this.currency = var1;
      if (!this.api.hasCurrency(var1)) {
         throw new IllegalStateException("ExcellentEconomy currency does not exist: " + var1);
      }
   }

   CompletableFuture<Double> balance(UUID var1) {
      return this.api.getBalanceAsync(var1, this.currency);
   }

   CompletableFuture<Boolean> withdraw(UUID var1, long var2) {
      return var2 > 0L && this.api.canPerformOperations()
         ? this.api.withdrawAsync(var1, this.currency, var2, this.context).thenApply(OperationResult::success)
         : CompletableFuture.completedFuture(false);
   }

   CompletableFuture<Boolean> deposit(UUID var1, long var2) {
      if (var2 <= 0L) {
         return CompletableFuture.completedFuture(true);
      } else {
         return !this.api.canPerformOperations()
            ? CompletableFuture.completedFuture(false)
            : this.api.depositAsync(var1, this.currency, var2, this.context).thenApply(OperationResult::success);
      }
   }
}
