package dev.ellan.botrental.paper;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Raider;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.entity.Tameable;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Long-term bots use a small simulation distance. The chunks between their simulation distance and their load
 * distance stay loaded but do not tick, so a mob that walks into that ring freezes there: it never despawns and no
 * longer counts toward the spawn cap. Around a bot that is online for weeks this lets mobs pile up without limit,
 * which does not happen around real players: their simulation distance reaches the 128-block despawn range.
 *
 * <p>This sweep puts the vanilla despawn rule back for that ring only. Around tracked bots it removes mobs in loaded
 * chunks that are not entity-ticking when vanilla would have despawned them: not persistent, no name, not tamed,
 * leashed, riding or ridden, not from a bucket, not in a raid, more than 32 blocks from every player, and of a
 * category that despawns at all (monsters, water and ambient mobs; never animals). Mobs that belong to other plugins
 * (scoreboard tags or plugin data) are left alone.
 */
final class FrozenRingSweeper {
   record Settings(boolean enabled, int intervalSeconds, int extraChunks, Set<SpawnCategory> categories,
                   Set<EntityType> excluded, Set<String> keepDataNamespaces, boolean log) {
      static Settings load(FileConfiguration config, Logger logger) {
         Set<SpawnCategory> categories = EnumSet.noneOf(SpawnCategory.class);
         for (String name : config.getStringList("frozen-ring-cleanup.categories")) {
            try {
               categories.add(SpawnCategory.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
               logger.warning("frozen-ring-cleanup.categories: unknown category '" + name + "' ignored");
            }
         }
         if (categories.isEmpty()) {
            categories = EnumSet.of(SpawnCategory.MONSTER, SpawnCategory.WATER_ANIMAL, SpawnCategory.WATER_AMBIENT,
               SpawnCategory.WATER_UNDERGROUND_CREATURE, SpawnCategory.AMBIENT);
         }
         // Animals and axolotls never despawn in vanilla; refuse them even when configured.
         categories.remove(SpawnCategory.ANIMAL);
         categories.remove(SpawnCategory.AXOLOTL);
         categories.remove(SpawnCategory.MISC);

         Set<EntityType> excluded = new HashSet<>(Set.of(EntityType.WITHER, EntityType.ENDER_DRAGON, EntityType.WARDEN,
            EntityType.ELDER_GUARDIAN));
         for (String name : config.getStringList("frozen-ring-cleanup.exclude-types")) {
            try {
               excluded.add(EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
               logger.warning("frozen-ring-cleanup.exclude-types: unknown entity type '" + name + "' ignored");
            }
         }

         Set<String> keep = new HashSet<>();
         for (String ns : config.getStringList("frozen-ring-cleanup.ignore-data-namespaces")) {
            keep.add(ns.trim().toLowerCase(Locale.ROOT));
         }

         return new Settings(
            config.getBoolean("frozen-ring-cleanup.enabled", true),
            Math.max(30, config.getInt("frozen-ring-cleanup.interval-seconds", 120)),
            Math.min(4, Math.max(1, config.getInt("frozen-ring-cleanup.extra-chunks", 2))),
            Set.copyOf(categories), Set.copyOf(excluded), Set.copyOf(keep),
            config.getBoolean("frozen-ring-cleanup.log", true));
      }
   }

   private static final double PLAYER_SAFE_DISTANCE_SQ = 32.0D * 32.0D;

   private final JavaPlugin plugin;
   private final Settings settings;
   private final Set<UUID> tracked = ConcurrentHashMap.newKeySet();

   FrozenRingSweeper(JavaPlugin plugin, Settings settings) {
      this.plugin = plugin;
      this.settings = settings;
   }

   void start() {
      if (!this.settings.enabled()) {
         this.plugin.getLogger().info("Frozen-ring cleanup is disabled.");
         return;
      }
      long period = this.settings.intervalSeconds() * 20L;
      Bukkit.getScheduler().runTaskTimer(this.plugin, this::sweep, period, period);
      this.plugin.getLogger().info("Frozen-ring cleanup every " + this.settings.intervalSeconds() + " s, categories "
         + this.settings.categories() + ".");
   }

   /** A long-term bot was placed with a reduced load radius. */
   void track(Player bot) {
      this.tracked.add(bot.getUniqueId());
   }

   /** Main thread. */
   void sweep() {
      Set<Long> visited = new HashSet<>();
      Map<String, Integer> removed = new TreeMap<>();
      int skippedPlugin = 0;
      for (UUID id : Set.copyOf(this.tracked)) {
         Player bot = Bukkit.getPlayer(id);
         if (bot == null) {
            this.tracked.remove(id);
            continue;
         }

         World world = bot.getWorld();
         Location at = bot.getLocation();
         int cx = at.getBlockX() >> 4;
         int cz = at.getBlockZ() >> 4;
         int radius = Math.max(bot.getViewDistance(), bot.getSimulationDistance()) + this.settings.extraChunks();
         for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
               long key = ((long) world.getUID().hashCode() << 42) ^ (((long) x & 0x1FFFFFL) << 21) ^ ((long) z & 0x1FFFFFL);
               if (!visited.add(key) || !world.isChunkLoaded(x, z)) {
                  continue;
               }
               Chunk chunk = world.getChunkAt(x, z);
               if (chunk.getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING || !chunk.isEntitiesLoaded()) {
                  continue;
               }
               for (Entity entity : chunk.getEntities()) {
                  if (!this.despawnable(entity)) {
                     continue;
                  }
                  if (this.ownedByPlugin(entity)) {
                     skippedPlugin++;
                     continue;
                  }
                  if (nearPlayer(world, entity.getLocation())) {
                     continue;
                  }
                  removed.merge(entity.getType().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
                  entity.remove();
               }
            }
         }
      }

      if (this.settings.log() && !removed.isEmpty()) {
         int total = removed.values().stream().mapToInt(Integer::intValue).sum();
         this.plugin.getLogger().info("Frozen-ring cleanup removed " + total + " mobs " + removed
            + (skippedPlugin > 0 ? ", kept " + skippedPlugin + " plugin mobs" : "") + ".");
      }
   }

   boolean despawnable(Entity entity) {
      if (!(entity instanceof Mob mob) || !mob.isValid() || mob.isDead()) {
         return false;
      }
      if (!this.settings.categories().contains(entity.getSpawnCategory()) || this.settings.excluded().contains(entity.getType())) {
         return false;
      }
      if (!((LivingEntity) mob).getRemoveWhenFarAway() || mob.customName() != null || mob.isLeashed()
         || mob.isInsideVehicle() || !mob.getPassengers().isEmpty()) {
         return false;
      }
      if (mob instanceof Tameable tameable && tameable.isTamed()) {
         return false;
      }
      if (mob instanceof Fish fish && fish.isFromBucket()) {
         return false;
      }
      return !(mob instanceof Raider raider) || raider.getRaid() == null;
   }

   private boolean ownedByPlugin(Entity entity) {
      if (!entity.getScoreboardTags().isEmpty()) {
         return true;
      }
      for (NamespacedKey key : entity.getPersistentDataContainer().getKeys()) {
         if (!this.settings.keepDataNamespaces().contains(key.getNamespace())) {
            return true;
         }
      }
      return false;
   }

   private static boolean nearPlayer(World world, Location location) {
      for (Player player : world.getPlayers()) {
         if (player.getLocation().distanceSquared(location) < PLAYER_SAFE_DISTANCE_SQ) {
            return true;
         }
      }
      return false;
   }
}
