package dev.ellan.botrental.paper;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

/** The {@code combat:} section of the paper bridge config. Everything is optional; missing means safe defaults. */
record CombatSettings(boolean enabled, int intervalTicks, double reach, boolean lineOfSight, Set<EntityType> excluded) {
   static CombatSettings load(FileConfiguration config, Logger logger) {
      Set<EntityType> excluded = new HashSet<>();
      for (String name : config.getStringList("combat.exclude-types")) {
         try {
            excluded.add(EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
         } catch (IllegalArgumentException unknown) {
            logger.warning("combat.exclude-types: unknown entity type '" + name + "' ignored");
         }
      }

      return new CombatSettings(
         config.getBoolean("combat.enabled", true),
         Math.max(1, config.getInt("combat.interval-ticks", 4)),
         Math.min(6.0D, Math.max(1.0D, config.getDouble("combat.reach", 3.0D))),
         config.getBoolean("combat.line-of-sight", true),
         Set.copyOf(excluded));
   }
}
