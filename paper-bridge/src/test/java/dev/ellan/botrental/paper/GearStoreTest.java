package dev.ellan.botrental.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GearStoreTest {
    @TempDir
    Path temp;

    @Test
    void storedGearSurvivesARestart() throws Exception {
        Path file = temp.resolve("gear.properties");
        UUID lease = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        byte[] item = {1, 2, 3, (byte) 255, 0, 42};

        new GearStore(file).put(new GearStore.Gear(lease, owner, 5, item));

        GearStore reopened = new GearStore(file);
        GearStore.Gear gear = reopened.get(lease).orElseThrow();
        assertThat(gear.owner()).isEqualTo(owner);
        assertThat(gear.slot()).isEqualTo(5);
        assertThat(gear.item()).containsExactly(item);
        assertThat(reopened.all()).hasSize(1);
    }

    @Test
    void replacingGearKeepsOnlyTheLatestItem() throws Exception {
        GearStore store = new GearStore(temp.resolve("gear.properties"));
        UUID lease = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        store.put(new GearStore.Gear(lease, owner, 4, new byte[] {1}));
        store.put(new GearStore.Gear(lease, owner, 4, new byte[] {2}));
        assertThat(store.all()).hasSize(1);
        assertThat(store.get(lease).orElseThrow().item()).containsExactly(2);
    }

    @Test
    void removedGearIsGoneAfterARestart() throws Exception {
        Path file = temp.resolve("gear.properties");
        GearStore store = new GearStore(file);
        UUID lease = UUID.randomUUID();
        store.put(new GearStore.Gear(lease, UUID.randomUUID(), 4, new byte[] {9}));
        store.remove(lease);
        assertThat(new GearStore(file).get(lease)).isEmpty();
        store.remove(lease);   // removing twice must not fail
    }

    @Test
    void pendingReturnsAreKeptPerOwnerInOrderAndClearedAfterDelivery() throws Exception {
        Path file = temp.resolve("gear.properties");
        GearStore store = new GearStore(file);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        store.addPending(alice, new byte[] {1});
        store.addPending(bob, new byte[] {7});
        store.addPending(alice, new byte[] {2});

        GearStore reopened = new GearStore(file);
        assertThat(reopened.pending(alice)).hasSize(2);
        assertThat(reopened.pending(alice).get(0)).containsExactly(1);
        assertThat(reopened.pending(alice).get(1)).containsExactly(2);

        reopened.clearPending(alice);
        assertThat(reopened.pending(alice)).isEmpty();
        assertThat(reopened.pending(bob)).hasSize(1);
        assertThat(new GearStore(file).pending(alice)).isEmpty();

        // a new pending item after a restart must not collide with an existing key
        GearStore again = new GearStore(file);
        again.addPending(bob, new byte[] {8});
        assertThat(again.pending(bob)).hasSize(2);
    }

    @Test
    void anAtomicSaveLeavesNoTemporaryFileBehind() throws Exception {
        Path file = temp.resolve("gear.properties");
        new GearStore(file).put(new GearStore.Gear(UUID.randomUUID(), UUID.randomUUID(), 4, new byte[] {1}));
        try (var listing = Files.list(temp)) {
            assertThat(listing.map(p -> p.getFileName().toString()).toList()).containsExactly("gear.properties");
        }
    }
}
