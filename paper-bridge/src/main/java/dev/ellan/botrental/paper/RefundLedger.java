package dev.ellan.botrental.paper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;

final class RefundLedger {
   private final Path file;
   private final Properties entries = new Properties();

   RefundLedger(Path var1) throws IOException {
      this.file = var1;
      if (Files.exists(var1)) {
         try (InputStream var2 = Files.newInputStream(var1)) {
            this.entries.load(var2);
         }
      }
   }

   synchronized boolean contains(UUID var1) {
      return this.entries.containsKey(var1.toString());
   }

   synchronized void record(UUID var1, long var2) throws IOException {
      this.entries.setProperty(var1.toString(), Long.toString(var2));
      Path var4 = this.file.resolveSibling(this.file.getFileName() + ".tmp");

      try (OutputStream var5 = Files.newOutputStream(var4)) {
         this.entries.store(var5, "Processed Bots4Velo rental refunds");
      }

      try {
         Files.move(var4, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException var9) {
         Files.move(var4, this.file, StandardCopyOption.REPLACE_EXISTING);
      }
   }
}
