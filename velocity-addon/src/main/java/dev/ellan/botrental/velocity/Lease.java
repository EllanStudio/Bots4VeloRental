package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.LeaseView;
import dev.ellan.botrental.common.LocationData;
import java.util.UUID;

final class Lease {
   final UUID id;
   final UUID ownerUuid;
   final String ownerName;
   final int slot;
   final String botId;
   final String botUsername;
   final long createdAt;
   long reserveCoins;
   long spentCoins;
   LeaseState state;
   LocationData location;
   boolean chargedOnce;
   long nextChargeAt;
   long startDeadline;
   long lastActionAt;
   /** Not persisted: prevents overlapping async server-switch requests. */
   boolean serverSwitchInFlight;
   /** Not persisted: invalidates callbacks from a server-switch request after a disconnect. */
   long serverSwitchGeneration;
   UUID refundId;
   String endReason;
   /** HOURLY (per-minute billing) or PACKAGE (prepaid long-term). */
   String plan = LeaseView.HOURLY;
   /** Package tier id (load radius); empty for hourly leases. */
   String tier = "";
   /** Package only: epoch millis when the prepaid period ends; 0 until the bot first arrives. */
   long expiresAt;
   /** Package only: purchased length in days (used to prorate refunds). */
   int packageDays;
   /** Package only: bot may fight nearby hostile mobs (set by the paper bridge). */
   boolean combat;
   /** Package only: last tick at which the lease was ACTIVE; downtime after this is credited back. */
   long lastActiveAt;
   /** Not persisted: when lastActiveAt was last written to the database. */
   long lastActivePersistedAt;

   boolean isPackage() {
      return LeaseView.PACKAGE.equals(this.plan);
   }

   Lease(
      UUID var1,
      UUID var2,
      String var3,
      int var4,
      String var5,
      String var6,
      long var7,
      long var9,
      LeaseState var11,
      LocationData var12,
      boolean var13,
      long var14,
      long var16,
      long var18,
      long var20,
      UUID var22,
      String var23
   ) {
      this.id = var1;
      this.ownerUuid = var2;
      this.ownerName = var3;
      this.slot = var4;
      this.botId = var5;
      this.botUsername = var6;
      this.reserveCoins = var7;
      this.spentCoins = var9;
      this.state = var11;
      this.location = var12;
      this.chargedOnce = var13;
      this.createdAt = var14;
      this.nextChargeAt = var16;
      this.startDeadline = var18;
      this.lastActionAt = var20;
      this.refundId = var22;
      this.endReason = var23 == null ? "" : var23;
   }
}
