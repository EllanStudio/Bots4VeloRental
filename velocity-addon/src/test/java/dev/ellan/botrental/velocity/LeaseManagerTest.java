package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.ApiMessages.CreateRequest;
import dev.ellan.botrental.common.ApiMessages.OwnerSlotRequest;
import dev.ellan.botrental.common.ApiMessages.StatusRequest;
import dev.ellan.botrental.common.LocationData;
import dev.nulli0n.vbot.addon.api.AddonBotEvent;
import dev.nulli0n.vbot.addon.api.AddonBotService;
import dev.nulli0n.vbot.addon.api.AddonBotSnapshot;
import dev.nulli0n.vbot.addon.api.AddonBotState;
import dev.nulli0n.vbot.addon.api.AddonLogger;
import dev.nulli0n.vbot.addon.api.AddonServerSwitchResult;
import dev.nulli0n.vbot.addon.api.AddonServerSwitchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LeaseManagerTest {
    @TempDir
    Path temp;

    @Test
    void enforcesThreeLeasesPerOwner() throws Exception {
        TestRig rig = rig();
        UUID owner = UUID.randomUUID();
        for (int index = 0; index < 3; index++) {
            assertThat(rig.manager.create(request(owner, 100)).success()).isTrue();
        }

        var fourth = rig.manager.create(request(owner, 100));
        assertThat(fourth.success()).isFalse();
        assertThat(fourth.code()).isEqualTo("OWNER_LIMIT");
        assertThat(fourth.refundableCoins()).isEqualTo(100);
        assertThat(rig.manager.status(new StatusRequest(owner)).leases()).hasSize(3);
        rig.close();
    }

    @Test
    void chargesOnlineThenOfflineRateAtMinuteBoundaries() throws Exception {
        TestRig rig = rig();
        UUID owner = UUID.randomUUID();
        rig.bots.online.put(owner, true);
        var created = rig.manager.create(request(owner, 45));
        String botId = created.lease().botId();
        rig.bots.states.put(botId, AddonBotState.PLAY);
        rig.bots.servers.put(botId, "redstone");

        rig.manager.tick();
        assertThat(rig.manager.status(new StatusRequest(owner)).leases().getFirst().reserveCoins()).isEqualTo(40);

        rig.clock.advanceSeconds(60);
        rig.bots.online.put(owner, false);
        rig.manager.tick();
        assertThat(rig.manager.status(new StatusRequest(owner)).leases().getFirst().reserveCoins()).isEqualTo(20);

        rig.clock.advanceSeconds(60);
        rig.manager.tick();
        assertThat(rig.manager.status(new StatusRequest(owner)).leases().getFirst().reserveCoins()).isZero();

        rig.clock.advanceSeconds(60);
        rig.manager.tick();
        assertThat(rig.manager.status(new StatusRequest(owner)).leases()).isEmpty();
        assertThat(rig.bots.states.get(botId)).isEqualTo(AddonBotState.STOPPED);
        rig.close();
    }

    @Test
    void manualCancelRefundsOnlyUnusedReserve() throws Exception {
        TestRig rig = rig();
        UUID owner = UUID.randomUUID();
        rig.bots.online.put(owner, true);
        var created = rig.manager.create(request(owner, 30));
        String botId = created.lease().botId();
        rig.bots.states.put(botId, AddonBotState.PLAY);
        rig.bots.servers.put(botId, "redstone");
        rig.manager.tick();

        var cancelled = rig.manager.cancel(new OwnerSlotRequest(UUID.randomUUID(), owner, 1));
        assertThat(cancelled.success()).isTrue();
        assertThat(cancelled.refundableCoins()).isEqualTo(25);
        assertThat(rig.bridge.refunds).containsExactly(25L);
        assertThat(rig.manager.status(new StatusRequest(owner)).leases()).isEmpty();
        rig.close();
    }

    @Test
    void doesNotOverlapServerSwitchRequestsWhileAuthMeTransitionIsPending() throws Exception {
        TestRig rig = rig();
        UUID owner = UUID.randomUUID();
        var created = rig.manager.create(request(owner, 30));
        String botId = created.lease().botId();
        rig.bots.states.put(botId, AddonBotState.PLAY);
        rig.bots.servers.put(botId, "lobby");
        rig.bots.deferSwitch = true;

        rig.clock.advanceSeconds(5);
        rig.manager.tick();
        assertThat(rig.bots.switchRequests).isEqualTo(1);

        rig.clock.advanceSeconds(5);
        rig.manager.tick();
        assertThat(rig.bots.switchRequests)
            .as("a slow AuthMe/Velocity transition must not be duplicated by the rental tick")
            .isEqualTo(1);

        rig.bots.servers.put(botId, "redstone");
        rig.bots.pendingSwitches.getFirst().complete(switchResult(botId));
        rig.manager.tick();

        assertThat(rig.manager.status(new StatusRequest(owner)).leases()).hasSize(1);
        assertThat(rig.manager.status(new StatusRequest(owner)).leases().getFirst().state())
            .isEqualTo(LeaseState.ACTIVE.name());
        assertThat(rig.bridge.refunds).isEmpty();
        rig.close();
    }

    @Test
    void retriesAfterAsyncSwitchReportsAuthenticationPending() throws Exception {
        TestRig rig = rig();
        UUID owner = UUID.randomUUID();
        var created = rig.manager.create(request(owner, 30));
        String botId = created.lease().botId();
        rig.bots.states.put(botId, AddonBotState.PLAY);
        rig.bots.servers.put(botId, "lobby");
        rig.bots.deferSwitch = true;

        rig.clock.advanceSeconds(5);
        rig.manager.tick();
        rig.bots.pendingSwitches.getFirst().complete(new AddonServerSwitchResult(
            AddonServerSwitchStatus.AUTHENTICATION_PENDING, botId, botId, "redstone",
            "authentication has not completed"));

        rig.clock.advanceSeconds(5);
        rig.manager.tick();
        assertThat(rig.bots.switchRequests).isEqualTo(2);
        assertThat(rig.manager.status(new StatusRequest(owner)).leases().getFirst().state())
            .isEqualTo(LeaseState.STARTING.name());
        rig.close();
    }

    @Test
    void ignoresAStaleSwitchCompletionAfterDisconnectStartsANewRequest() throws Exception {
        TestRig rig = rig();
        UUID owner = UUID.randomUUID();
        var created = rig.manager.create(request(owner, 30));
        String botId = created.lease().botId();
        rig.bots.states.put(botId, AddonBotState.PLAY);
        rig.bots.servers.put(botId, "lobby");
        rig.bots.deferSwitch = true;

        rig.clock.advanceSeconds(5);
        rig.manager.tick();
        rig.manager.onBotEvent(new AddonBotEvent(
            Instant.ofEpochMilli(rig.clock.millis()), botId, "DISCONNECTED", "auth transition"));
        rig.manager.tick();
        assertThat(rig.bots.switchRequests).isEqualTo(2);

        rig.bots.pendingSwitches.get(0).complete(switchResult(botId));
        rig.clock.advanceSeconds(5);
        rig.manager.tick();
        assertThat(rig.bots.switchRequests)
            .as("a completion from the pre-disconnect request must not clear the new guard")
            .isEqualTo(2);
        rig.close();
    }

    @Test
    void synchronousSwitchFailureClearsInFlightGuardForTheNextRetry() throws Exception {
        TestRig rig = rig();
        UUID owner = UUID.randomUUID();
        var created = rig.manager.create(request(owner, 30));
        String botId = created.lease().botId();
        rig.bots.states.put(botId, AddonBotState.PLAY);
        rig.bots.servers.put(botId, "lobby");
        rig.bots.throwOnSwitch = true;

        rig.clock.advanceSeconds(5);
        rig.manager.tick();
        assertThat(rig.bots.switchRequests).isEqualTo(1);

        rig.bots.throwOnSwitch = false;
        rig.clock.advanceSeconds(5);
        rig.manager.tick();
        assertThat(rig.bots.switchRequests).isEqualTo(2);
        rig.close();
    }

    private static AddonServerSwitchResult switchResult(String botId) {
        return new AddonServerSwitchResult(
            AddonServerSwitchStatus.SWITCHED, botId, botId, "redstone", "SUCCESS");
    }

    private TestRig rig() throws Exception {
        RentalConfig config = new RentalConfig("127.0.0.1", 18765, "http://127.0.0.1:18766",
            "01234567890123456789012345678901", "redstone", "ellan_coin", 5, 20, 3,
            60, 90, IntStream.rangeClosed(1, 10).mapToObj(index -> "BOT_" + index).toList());
        FakeBots bots = new FakeBots(config.pool());
        FakeBridge bridge = new FakeBridge();
        MutableClock clock = new MutableClock(1_000_000L);
        LeaseStore store = new LeaseStore(temp.resolve(UUID.randomUUID() + ".db"));
        LeaseManager manager = new LeaseManager(config, bots, bridge, store, new SilentLogger(), clock);
        return new TestRig(manager, bots, bridge, clock);
    }

    private static CreateRequest request(UUID owner, long reserve) {
        return new CreateRequest(UUID.randomUUID(), owner, "Player", reserve,
            new LocationData("world", 1, 64, 2, 0, 0));
    }

    private record TestRig(LeaseManager manager, FakeBots bots, FakeBridge bridge, MutableClock clock)
        implements AutoCloseable {
        @Override
        public void close() throws Exception {
            manager.close();
        }
    }

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advanceSeconds(long seconds) {
            millis += seconds * 1000;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    private static final class FakeBridge implements RentalBridge {
        private final List<Long> refunds = new ArrayList<>();

        @Override
        public boolean place(UUID leaseId, String botUsername, UUID ownerUuid, LocationData location) {
            return true;
        }

        @Override
        public boolean refund(UUID refundId, UUID leaseId, UUID ownerUuid, long coins, String reason) {
            refunds.add(coins);
            return true;
        }
    }

    private static final class FakeBots implements AddonBotService {
        private final Map<String, AddonBotState> states = new HashMap<>();
        private final Map<String, String> servers = new HashMap<>();
        private final Map<UUID, Boolean> online = new HashMap<>();
        private final List<CompletableFuture<AddonServerSwitchResult>> pendingSwitches = new ArrayList<>();
        private int switchRequests;
        private boolean deferSwitch;
        private boolean throwOnSwitch;

        private FakeBots(List<String> ids) {
            ids.forEach(id -> states.put(id, AddonBotState.STOPPED));
        }

        @Override
        public List<AddonBotSnapshot> bots() {
            return states.keySet().stream().map(id -> new AddonBotSnapshot(id, id, states.get(id))).toList();
        }

        @Override
        public Optional<AddonBotSnapshot> bot(String id) {
            return Optional.ofNullable(states.get(id)).map(state -> new AddonBotSnapshot(id, id, state));
        }

        @Override
        public boolean start(String id) {
            if (states.get(id) != AddonBotState.STOPPED) {
                return false;
            }
            states.put(id, AddonBotState.CONNECTING);
            return true;
        }

        @Override
        public boolean stop(String id) {
            states.put(id, AddonBotState.STOPPED);
            return true;
        }

        @Override
        public boolean reconnect(String id) {
            states.put(id, AddonBotState.CONNECTING);
            return true;
        }

        @Override
        public Optional<String> currentServer(String id) {
            return Optional.ofNullable(servers.get(id));
        }

        @Override
        public CompletionStage<AddonServerSwitchResult> switchServer(String id, String server) {
            switchRequests++;
            if (throwOnSwitch) {
                throw new IllegalStateException("simulated switch failure");
            }
            if (deferSwitch) {
                CompletableFuture<AddonServerSwitchResult> pending = new CompletableFuture<>();
                pendingSwitches.add(pending);
                return pending;
            }
            servers.put(id, server);
            return CompletableFuture.completedFuture(switchResult(id));
        }

        @Override
        public boolean isPlayerOnline(UUID playerId) {
            return online.getOrDefault(playerId, false);
        }

        @Override
        public boolean sendPlayerMessage(UUID playerId, String message) {
            return true;
        }

        @Override
        public void addEventListener(Consumer<AddonBotEvent> listener) {
        }

        @Override
        public void removeEventListener(Consumer<AddonBotEvent> listener) {
        }
    }

    private static final class SilentLogger implements AddonLogger {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message, Throwable failure) {
            throw new AssertionError(message, failure);
        }
    }
}
