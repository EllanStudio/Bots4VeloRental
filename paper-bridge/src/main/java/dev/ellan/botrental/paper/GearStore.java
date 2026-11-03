package dev.ellan.botrental.paper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Durable storage for items players hand to a combat bot, plus a queue of items waiting to be returned to an owner
 * who was offline when the lease ended. Items are opaque byte arrays here (the caller serialises them) so the file
 * format has no server-version dependency and this class can be tested without a server.
 *
 * <p>The file is rewritten atomically after every change, so a crash can lose at most the change in flight, never
 * leave a half-written file; an item is removed from this store only after it was handed back.
 */
final class GearStore {
   record Gear(UUID leaseId, UUID owner, int slot, byte[] item) {
   }

   private static final String GEAR = "gear.";
   private static final String PENDING = "pending.";
   private final Path file;
   private final Properties data = new Properties();
   private long pendingCounter;

   GearStore(Path file) throws IOException {
      this.file = file;
      if (Files.exists(file)) {
         try (InputStream in = Files.newInputStream(file)) {
            this.data.load(in);
         }
      }

      for (String key : this.data.stringPropertyNames()) {
         if (key.startsWith(PENDING)) {
            this.pendingCounter = Math.max(this.pendingCounter, Long.parseLong(key.substring(key.lastIndexOf('.') + 1)));
         }
      }
   }

   synchronized Optional<Gear> get(UUID leaseId) {
      return Optional.ofNullable(this.data.getProperty(GEAR + leaseId)).map(value -> parse(leaseId, value));
   }

   synchronized List<Gear> all() {
      List<Gear> result = new ArrayList<>();
      for (String key : this.data.stringPropertyNames()) {
         if (key.startsWith(GEAR)) {
            result.add(parse(UUID.fromString(key.substring(GEAR.length())), this.data.getProperty(key)));
         }
      }

      return result;
   }

   synchronized void put(Gear gear) throws IOException {
      this.data.setProperty(GEAR + gear.leaseId(), gear.owner() + ";" + gear.slot() + ";" + Base64.getEncoder().encodeToString(gear.item()));
      this.save();
   }

   synchronized void remove(UUID leaseId) throws IOException {
      if (this.data.remove(GEAR + leaseId) != null) {
         this.save();
      }
   }

   /** Queue an item for an owner who cannot receive it right now. */
   synchronized void addPending(UUID owner, byte[] item) throws IOException {
      this.data.setProperty(PENDING + owner + "." + (++this.pendingCounter), Base64.getEncoder().encodeToString(item));
      this.save();
   }

   synchronized List<byte[]> pending(UUID owner) {
      List<byte[]> result = new ArrayList<>();
      for (String key : this.data.stringPropertyNames().stream().filter(k -> k.startsWith(PENDING + owner + ".")).sorted().toList()) {
         result.add(Base64.getDecoder().decode(this.data.getProperty(key)));
      }

      return result;
   }

   /** Forget the pending items of an owner, after they were handed over. */
   synchronized void clearPending(UUID owner) throws IOException {
      boolean changed = false;
      for (String key : new ArrayList<>(this.data.stringPropertyNames())) {
         if (key.startsWith(PENDING + owner + ".")) {
            this.data.remove(key);
            changed = true;
         }
      }

      if (changed) {
         this.save();
      }
   }

   private static Gear parse(UUID leaseId, String value) {
      String[] parts = value.split(";", 3);
      return new Gear(leaseId, UUID.fromString(parts[0]), Integer.parseInt(parts[1]), Base64.getDecoder().decode(parts[2]));
   }

   private void save() throws IOException {
      Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
      try (OutputStream out = Files.newOutputStream(temp)) {
         this.data.store(out, "Bots4Velo rental: items held by combat bots and items waiting to be returned");
      }

      try {
         Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicNotSupported) {
         Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
      }
   }
}
