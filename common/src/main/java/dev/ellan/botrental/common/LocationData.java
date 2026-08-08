package dev.ellan.botrental.common;

public record LocationData(String world, double x, double y, double z, float yaw, float pitch) {
    public LocationData {
        world = world == null ? "" : world.trim();
        if (world.isEmpty()) {
            throw new IllegalArgumentException("world is required");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("location contains a non-finite number");
        }
    }
}
