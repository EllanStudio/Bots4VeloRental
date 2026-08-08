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

    RefundLedger(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                entries.load(input);
            }
        }
    }

    synchronized boolean contains(UUID refundId) {
        return entries.containsKey(refundId.toString());
    }

    synchronized void record(UUID refundId, long coins) throws IOException {
        entries.setProperty(refundId.toString(), Long.toString(coins));
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            entries.store(output, "Processed Bots4Velo rental refunds");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException atomicFailure) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
