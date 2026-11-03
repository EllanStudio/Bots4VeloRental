package dev.ellan.botrental.paper;

/** Geometry for "can this bot hit that mob": vanilla measures from the eyes to the closest point of the target box. */
final class ReachMath {
   private ReachMath() {
   }

   /** Distance from a point to an axis-aligned box (0 when the point is inside it). */
   static double distanceToBox(
      double x, double y, double z,
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ
   ) {
      double dx = Math.max(Math.max(minX - x, 0.0D), x - maxX);
      double dy = Math.max(Math.max(minY - y, 0.0D), y - maxY);
      double dz = Math.max(Math.max(minZ - z, 0.0D), z - maxZ);
      return Math.sqrt(dx * dx + dy * dy + dz * dz);
   }

   static boolean inReach(double distance, double reach) {
      return distance <= reach;
   }
}
