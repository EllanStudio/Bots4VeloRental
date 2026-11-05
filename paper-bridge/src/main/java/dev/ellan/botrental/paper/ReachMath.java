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

   /** The point of the box nearest to (x, y, z); the point itself when it is inside the box. */
   static double[] closestPoint(
      double x, double y, double z,
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ
   ) {
      return new double[] {
         Math.min(Math.max(x, minX), maxX),
         Math.min(Math.max(y, minY), maxY),
         Math.min(Math.max(z, minZ), maxZ)
      };
   }

   /**
    * Points on a target that the bot may aim at, nearest first: the closest point of the box, the centre and a point
    * in the upper body. A vanilla player hits a mob whenever the crosshair ray meets its box, so one clear ray to any
    * of these is enough; requiring a clear eye-to-eye ray is stricter than the game and fails next to slabs, fences,
    * cauldron rims and other low obstacles.
    */
   static double[][] aimPoints(
      double x, double y, double z,
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ
   ) {
      double cx = (minX + maxX) / 2.0D;
      double cz = (minZ + maxZ) / 2.0D;
      return new double[][] {
         closestPoint(x, y, z, minX, minY, minZ, maxX, maxY, maxZ),
         {cx, (minY + maxY) / 2.0D, cz},
         {cx, minY + (maxY - minY) * 0.85D, cz}
      };
   }

   /** Unit vector for a Minecraft yaw/pitch pair in degrees (yaw 0 faces +Z, pitch -90 faces up). */
   static double[] direction(float yaw, float pitch) {
      double yawRad = Math.toRadians(yaw);
      double pitchRad = Math.toRadians(pitch);
      double horizontal = Math.cos(pitchRad);
      return new double[] {-Math.sin(yawRad) * horizontal, -Math.sin(pitchRad), Math.cos(yawRad) * horizontal};
   }

   /** True when two angles in degrees describe the same facing, allowing for 360-degree wrap-around. */
   static boolean sameAngle(float a, float b, float tolerance) {
      float diff = Math.abs(((a - b) % 360.0F + 540.0F) % 360.0F - 180.0F);
      return diff <= tolerance;
   }
}
