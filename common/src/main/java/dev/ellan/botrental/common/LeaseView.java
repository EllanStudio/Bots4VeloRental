package dev.ellan.botrental.common;

import java.util.UUID;

/**
 * Snapshot of one lease as shown to the player.
 *
 * <p>{@code plan} is {@code HOURLY} (per-minute billing, the original product) or {@code PACKAGE} (prepaid
 * long-term plan). For packages {@code expiresAt} is the epoch-millisecond end of the prepaid period (0 until the
 * bot has arrived), {@code tier} is the load-radius tier id and {@code packageDays} the purchased length.
 */
public record LeaseView(
   UUID leaseId,
   int slot,
   String botId,
   String botUsername,
   String state,
   long reserveCoins,
   long spentCoins,
   long currentRate,
   long estimatedMinutes,
   LocationData location,
   String plan,
   String tier,
   long expiresAt,
   boolean combat,
   int packageDays
) {
   public static final String HOURLY = "HOURLY";
   public static final String PACKAGE = "PACKAGE";

   /** Compatibility constructor for the original per-minute product. */
   public LeaseView(
      UUID leaseId, int slot, String botId, String botUsername, String state, long reserveCoins,
      long spentCoins, long currentRate, long estimatedMinutes, LocationData location
   ) {
      this(leaseId, slot, botId, botUsername, state, reserveCoins, spentCoins, currentRate, estimatedMinutes,
         location, HOURLY, "", 0L, false, 0);
   }

   public boolean isPackage() {
      return PACKAGE.equals(plan);
   }
}
