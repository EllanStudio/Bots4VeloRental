package dev.ellan.botrental.paper;

import dev.ellan.botrental.common.ApiMessages;
import dev.ellan.botrental.common.LeaseView;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Lets a rented "combat" bot hold an item handed over by its owner and hit nearby hostile mobs with it.
 *
 * <p>Design rules: the bot never fights players, pets, villagers or animals; it only attacks {@link Enemy} mobs in
 * vanilla reach and respects the weapon's attack cooldown; it never picks up items or experience (drops stay on
 * the ground for the owner); the owner's item is held in {@link GearStore}, which is the single source of truth,
 * and is returned when the lease ends no matter whether the owner is online.
 */
final class BotCombatService implements Listener, AutoCloseable {
   /** What we know about a bot that should currently fight. */
   private record Fighter(UUID leaseId, UUID owner, Facing facing) {
   }

   /**
    * Where the owner left the bot looking. Hitting a mob turns the bot towards it, so the original aim is remembered
    * (a rotation we did not cause is taken as the new aim) and armor stands are looked for along it.
    */
   private static final class Facing {
      private boolean known;
      private float homeYaw;
      private float homePitch;
      private float lastYaw;
      private float lastPitch;

      /** Call with the bot's current rotation before acting; adopts it as home unless it is our own last turn. */
      void observe(float yaw, float pitch) {
         if (!this.known || !ReachMath.sameAngle(yaw, this.lastYaw, 0.5F) || !ReachMath.sameAngle(pitch, this.lastPitch, 0.5F)) {
            this.homeYaw = yaw;
            this.homePitch = pitch;
         }

         this.known = true;
         this.lastYaw = yaw;
         this.lastPitch = pitch;
      }

      /** Call after we turned the bot ourselves, so that turn is not mistaken for the owner re-aiming it. */
      void turned(float yaw, float pitch) {
         this.lastYaw = yaw;
         this.lastPitch = pitch;
      }
   }

   private final JavaPlugin plugin;
   private final CombatSettings settings;
   private final GearStore gear;
   private final Set<String> botNames;
   private final Map<String, Fighter> fighters = new ConcurrentHashMap<>();
   /**
    * What each fighting bot must hold in its main hand, straight from {@link GearStore}. Other plugins (cross-server
    * inventory sync on login, for one) can replace a bot's inventory after we handed it the item, so the attack loop
    * checks the hand against this and puts the item back.
    */
   private final Map<String, ItemStack> expectedHand = new ConcurrentHashMap<>();
   private BukkitTask attackTask;

   BotCombatService(JavaPlugin plugin, CombatSettings settings, GearStore gear, Set<String> botNames) {
      this.plugin = plugin;
      this.settings = settings;
      this.gear = gear;
      this.botNames = botNames;
   }

   void start() {
      Bukkit.getPluginManager().registerEvents(this, this.plugin);
      if (this.settings.enabled()) {
         this.attackTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 40L, this.settings.intervalTicks());
      }
   }

   @Override
   public void close() {
      if (this.attackTask != null) {
         this.attackTask.cancel();
         this.attackTask = null;
      }
   }

   boolean enabled() {
      return this.settings.enabled();
   }

   // ------------------------------------------------------------------ called by the bridge when a bot is placed

   /** Main thread. Registers or clears the fighter for a bot and (re)applies the stored item to its hand. */
   void configure(String botName, ApiMessages.PlaceRequest request) {
      String key = botName.toLowerCase(Locale.ROOT);
      Player bot = Bukkit.getPlayerExact(botName);
      if (!request.combat() || !this.settings.enabled()) {
         this.expectedHand.remove(key);
         if (this.fighters.remove(key) != null && bot != null) {
            this.clearHands(bot);
         }

         return;
      }

      this.fighters.put(key, new Fighter(request.leaseId(), request.ownerUuid(), new Facing()));
      this.rememberHand(key, request.leaseId());
      if (bot != null) {
         this.applyGear(bot, request.leaseId());
      }
   }

   private void applyGear(Player bot, UUID leaseId) {
      Optional<GearStore.Gear> stored = this.gear.get(leaseId);
      // Always overwrite: whatever a previous renter left in this bot's hands must never carry over.
      bot.getInventory().setItemInMainHand(stored.map(g -> ItemStack.deserializeBytes(g.item())).orElse(null));
      bot.getInventory().setItemInOffHand(null);
   }

   private void rememberHand(String key, UUID leaseId) {
      Optional<GearStore.Gear> stored = this.gear.get(leaseId);
      if (stored.isPresent()) {
         this.expectedHand.put(key, ItemStack.deserializeBytes(stored.get().item()));
      } else {
         this.expectedHand.remove(key);
      }
   }

   private void clearHands(Player bot) {
      bot.getInventory().setItemInMainHand(null);
      bot.getInventory().setItemInOffHand(null);
   }

   // ------------------------------------------------------------------ owner actions (main thread)

   /** Take the item in the owner's main hand into custody and put it in the bot's hand. Returns a player message. */
   String deposit(Player owner, LeaseView lease) {
      if (!lease.combat()) {
         return "&c\u8fd9\u4e2a\u5957\u9910\u4e0d\u662f\u6218\u6597\u578b\uff0c\u4e0d\u80fd\u4fdd\u7ba1\u7269\u54c1\u3002";
      }

      ItemStack hand = owner.getInventory().getItemInMainHand();
      if (hand == null || hand.getType().isAir()) {
         return "&c\u8bf7\u5148\u628a\u8981\u4ea4\u7ed9\u673a\u5668\u4eba\u7684\u7269\u54c1\u62ff\u5728\u4e3b\u624b\u3002";
      }

      ItemStack giving = hand.clone();
      Optional<GearStore.Gear> previous = this.gear.get(lease.leaseId());
      try {
         this.gear.put(new GearStore.Gear(lease.leaseId(), owner.getUniqueId(), lease.slot(), giving.serializeAsBytes()));
      } catch (IOException failure) {
         this.plugin.getLogger().log(Level.SEVERE, "Could not store bot gear for " + lease.leaseId(), failure);
         return "&c\u4fdd\u5b58\u5931\u8d25\uff0c\u7269\u54c1\u6ca1\u6709\u88ab\u62ff\u8d70\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002";
      }

      // Only after the durable write succeeded do we take the item from the player's hand.
      owner.getInventory().setItemInMainHand(null);
      previous.ifPresent(old -> this.giveBack(owner, ItemStack.deserializeBytes(old.item())));
      this.expectedHand.put(lease.botUsername().toLowerCase(Locale.ROOT), giving.clone());
      Player bot = Bukkit.getPlayerExact(lease.botUsername());
      if (bot != null) {
         this.applyGear(bot, lease.leaseId());
      }

      return "&a\u5df2\u628a " + describe(giving) + " &a\u4ea4\u7ed9\u673a\u5668\u4eba\u4fdd\u7ba1\u5e76\u62ff\u5728\u624b\u4e0a\u3002" + (previous.isPresent() ? "&7 \u539f\u6765\u7684\u7269\u54c1\u5df2\u9000\u8fd8\u3002" : "");
   }

   /** Give the stored item back to its owner right now. */
   String withdraw(Player owner, LeaseView lease) {
      Optional<GearStore.Gear> stored = this.gear.get(lease.leaseId());
      if (stored.isEmpty()) {
         return "&7\u673a\u5668\u4eba\u624b\u4e0a\u6ca1\u6709\u4fdd\u7ba1\u7684\u7269\u54c1\u3002";
      }

      this.giveBack(owner, ItemStack.deserializeBytes(stored.get().item()));
      try {
         this.gear.remove(lease.leaseId());
      } catch (IOException failure) {
         this.plugin.getLogger().log(Level.SEVERE, "Could not clear bot gear for " + lease.leaseId(), failure);
      }

      this.expectedHand.remove(lease.botUsername().toLowerCase(Locale.ROOT));
      Player bot = Bukkit.getPlayerExact(lease.botUsername());
      if (bot != null) {
         this.clearHands(bot);
      }

      return "&a\u5df2\u628a\u7269\u54c1\u53d6\u56de\u3002";
   }

   private void giveBack(Player owner, ItemStack item) {
      Map<Integer, ItemStack> leftover = owner.getInventory().addItem(item);
      leftover.values().forEach(rest -> owner.getWorld().dropItemNaturally(owner.getLocation(), rest));
   }

   private static String describe(ItemStack item) {
      return item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " x" + item.getAmount();
   }

   // ------------------------------------------------------------------ giving items back when a lease is gone

   /**
    * Async: for every stored item, check that its lease still exists; when it does not, take the item out of the bot's
    * hand and return it to the owner (queueing it if they are offline).
    */
   void reconcile(java.util.function.Function<UUID, ApiMessages.StatusResponse> statusOf) {
      Map<UUID, List<GearStore.Gear>> byOwner = new HashMap<>();
      this.gear.all().forEach(g -> byOwner.computeIfAbsent(g.owner(), k -> new java.util.ArrayList<>()).add(g));
      byOwner.forEach((owner, items) -> {
         ApiMessages.StatusResponse status;
         try {
            status = statusOf.apply(owner);
         } catch (RuntimeException unavailable) {
            return;     // addon not reachable: never guess, try again next round
         }

         if (status == null || !status.success()) {
            return;
         }

         for (GearStore.Gear item : items) {
            boolean stillLeased = status.leases().stream().anyMatch(lease -> lease.leaseId().equals(item.leaseId()));
            if (!stillLeased) {
               Bukkit.getScheduler().runTask(this.plugin, () -> this.returnEnded(item));
            }
         }
      });
   }

   private void returnEnded(GearStore.Gear item) {
      Player bot = null;
      for (Map.Entry<String, Fighter> entry : this.fighters.entrySet()) {
         if (entry.getValue().leaseId().equals(item.leaseId())) {
            bot = Bukkit.getPlayerExact(entry.getKey());
            this.fighters.remove(entry.getKey());
            this.expectedHand.remove(entry.getKey());
         }
      }

      if (bot != null) {
         this.clearHands(bot);
      }

      try {
         Player owner = Bukkit.getPlayer(item.owner());
         if (owner != null) {
            this.giveBack(owner, ItemStack.deserializeBytes(item.item()));
            owner.sendMessage(Component.text("[\u6302\u673a\u673a\u5668\u4eba] \u5957\u9910\u5df2\u7ed3\u675f\uff0c\u4fdd\u7ba1\u7684\u7269\u54c1\u5df2\u9000\u8fd8\u5230\u4f60\u7684\u80cc\u5305\u3002"));
         } else {
            this.gear.addPending(item.owner(), item.item());
         }

         this.gear.remove(item.leaseId());
      } catch (IOException failure) {
         this.plugin.getLogger().log(Level.SEVERE, "Could not return bot gear for lease " + item.leaseId(), failure);
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      List<byte[]> waiting = this.gear.pending(player.getUniqueId());
      if (waiting.isEmpty()) {
         return;
      }

      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (!player.isOnline()) {
            return;
         }

         waiting.forEach(bytes -> this.giveBack(player, ItemStack.deserializeBytes(bytes)));
         try {
            this.gear.clearPending(player.getUniqueId());
         } catch (IOException failure) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not clear pending gear for " + player.getUniqueId(), failure);
         }

         player.sendMessage(Component.text("[\u6302\u673a\u673a\u5668\u4eba] \u4f60\u6709 " + waiting.size() + " \u4ef6\u673a\u5668\u4eba\u4fdd\u7ba1\u7684\u7269\u54c1\u5df2\u9000\u8fd8\u3002"));
      }, 40L);
   }

   /** Rental bots must not soak up experience: the orbs stay on the ground for the owner. */
   @EventHandler
   public void onExperience(PlayerPickupExperienceEvent event) {
      if (this.botNames.contains(event.getPlayer().getName().toLowerCase(Locale.ROOT))) {
         event.setCancelled(true);
      }
   }

   // ------------------------------------------------------------------ the attack loop

   private void tick() {
      for (Map.Entry<String, Fighter> entry : this.fighters.entrySet()) {
         Player bot = Bukkit.getPlayerExact(entry.getKey());
         if (bot == null || !bot.isOnline() || bot.isDead()) {
            continue;
         }

         // Put the owner's item back if the hand is empty or holds a different kind of item. Only the material is
         // compared: enchantment plugins rewrite a held item's data on their own, and its durability drops as it
         // is used, so a strict comparison never matches and would reset the item on every pass.
         ItemStack expected = this.expectedHand.get(entry.getKey());
         if (expected != null && bot.getInventory().getItemInMainHand().getType() != expected.getType()) {
            bot.getInventory().setItemInMainHand(expected.clone());
            this.plugin.getLogger().info("Restored the held item of rental bot " + bot.getName());
         }

         // Full-strength hits only: the vanilla cooldown is the weapon's rate of fire.
         if (bot.getAttackCooldown() < 1.0F) {
            continue;
         }

         Facing facing = entry.getValue().facing();
         facing.observe(bot.getLocation().getYaw(), bot.getLocation().getPitch());

         Location eye = bot.getEyeLocation();
         LivingEntity target = null;
         Location aim = null;
         LivingEntity mob = this.pickTarget(bot, eye);
         if (mob != null) {
            target = mob;
            aim = this.visibleAimPoint(bot, eye, mob.getBoundingBox());
         } else if (this.settings.armorStands()) {
            RayTraceResult hit = this.aimedArmorStand(bot, eye, facing);
            if (hit != null && hit.getHitEntity() instanceof ArmorStand stand) {
               target = stand;
               aim = hit.getHitPosition().toLocation(bot.getWorld());
            }
         }

         if (target == null || aim == null) {
            continue;
         }

         bot.lookAt(aim.getX(), aim.getY(), aim.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
         facing.turned(bot.getLocation().getYaw(), bot.getLocation().getPitch());
         bot.attack(target);
         bot.swingMainHand();
      }
   }

   /** The nearest hostile mob whose hitbox the bot can reach and see (see {@link ReachMath#aimPoints}). */
   private LivingEntity pickTarget(Player bot, Location eye) {
      double search = this.settings.reach() + 1.0D;
      LivingEntity best = null;
      double bestDistance = Double.MAX_VALUE;
      for (Entity candidate : bot.getNearbyEntities(search, search, search)) {
         if (!(candidate instanceof LivingEntity living) || !this.attackableMob(living)) {
            continue;
         }

         BoundingBox box = living.getBoundingBox();
         double distance = ReachMath.distanceToBox(eye.getX(), eye.getY(), eye.getZ(),
            box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
         if (!ReachMath.inReach(distance, this.settings.reach()) || distance >= bestDistance) {
            continue;
         }

         if (this.visibleAimPoint(bot, eye, box) == null) {
            continue;
         }

         best = living;
         bestDistance = distance;
      }

      return best;
   }

   /**
    * A point on the box that the eyes have a clear ray to, nearest first, or null when every ray is blocked. With
    * line-of-sight off the nearest point is returned without looking at the blocks in between.
    */
   private Location visibleAimPoint(Player bot, Location eye, BoundingBox box) {
      double[][] points = ReachMath.aimPoints(eye.getX(), eye.getY(), eye.getZ(),
         box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
      for (double[] p : points) {
         Location point = new Location(eye.getWorld(), p[0], p[1], p[2]);
         if (!this.settings.lineOfSight()) {
            return point;
         }

         Vector way = point.toVector().subtract(eye.toVector());
         double length = way.length();
         if (length <= 0.05D) {
            return point;               // the eyes are in (or against) the box
         }

         // Stop just short of the target so the face it is pressed against does not count as an obstacle.
         if (eye.getWorld().rayTraceBlocks(eye, way.multiply(1.0D / length), length - 0.02D,
            FluidCollisionMode.NEVER, true) == null) {
            return point;
         }
      }

      return null;
   }

   /**
    * The armor stand under the bot's crosshair, if any: a ray from the eyes along the remembered aim, stopped by
    * blocks like any vanilla click. Only stands the bot is actually pointing at are hit, never every stand nearby.
    * Marker stands have no hitbox and invulnerable ones cannot be hurt, so neither is a target.
    */
   private RayTraceResult aimedArmorStand(Player bot, Location eye, Facing facing) {
      double[] d = ReachMath.direction(facing.homeYaw, facing.homePitch);
      RayTraceResult hit = bot.getWorld().rayTrace(eye, new Vector(d[0], d[1], d[2]), this.settings.reach(),
         FluidCollisionMode.NEVER, true, 0.0D,
         e -> e instanceof ArmorStand stand && !stand.isMarker() && !stand.isInvulnerable() && stand.isValid());
      return hit != null && hit.getHitEntity() != null ? hit : null;
   }

   private boolean attackableMob(LivingEntity entity) {
      if (!(entity instanceof Enemy) || entity instanceof Player || entity instanceof Boss) {
         return false;
      }

      if (entity instanceof Tameable tameable && tameable.isTamed()) {
         return false;
      }

      return entity.isValid() && !entity.isDead() && !this.settings.excluded().contains(entity.getType());
   }
}
