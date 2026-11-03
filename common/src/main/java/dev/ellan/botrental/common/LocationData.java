package dev.ellan.botrental.common;

import java.util.Locale;

public record LocationData(String server, String world, double x, double y, double z, float yaw, float pitch) {
   public LocationData(String server, String world, double x, double y, double z, float yaw, float pitch) {
      server = server == null ? "" : server.trim().toLowerCase(Locale.ROOT);
      world = world == null ? "" : world.trim();
      if (world.isEmpty()) {
         throw new IllegalArgumentException("world is required");
      }

      if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Float.isFinite(yaw) && Float.isFinite(pitch)) {
         this.server = server;
         this.world = world;
         this.x = x;
         this.y = y;
         this.z = z;
         this.yaw = yaw;
         this.pitch = pitch;
      } else {
         throw new IllegalArgumentException("location contains a non-finite number");
      }
   }
}
