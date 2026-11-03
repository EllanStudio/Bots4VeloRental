package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.ApiMessages.CreateRequest;
import dev.ellan.botrental.common.ApiMessages.OwnerSlotRequest;
import dev.ellan.botrental.common.ApiMessages.StatusRequest;
import dev.ellan.botrental.common.ApiMessages.TopUpRequest;
import dev.ellan.botrental.common.LeaseView;
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
        authenticate(rig, botId);

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
        authenticate(rig, botId);
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
        authenticate(rig, botId);
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
        authenticate(rig, botId);
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
    void doesNotRequestRentalSwitchUntilCoreAuthenticationIsConfirmed() throws Exception {
        TestRig rig = rig();
        UUID owner = UUID.randomUUID();
        var created = rig.manager.create(request(owner, 30));
        String botId = created.lease().botId();
        rig.bots.states.put(botId, AddonBotState.PLAY);
        rig.bots.servers.put(botId, "lobby");

        rig.manager.onBotEvent(new AddonBotEvent(
            Instant.ofEpochMilli(rig.clock.millis()), botId, "PLAY", "entered play"));
        rig.clock.advanceSeconds(5);
        rig.manager.tick();
        assertThat(rig.bots.switchRequests)
            .as("PLAY is not proof of AuthMe authentication")
            .isZero();

        rig.manager.onBotEvent(new AddonBotEvent(
            Instant.ofEpochMilli(rig.clock.millis()), botId, "AUTHENTICATED", "confirmed"));
        rig.manager.tick();
        assertThat(rig.bots.switchRequests).isEqualTo(1);
        rig.close();
    }

    @Test
    void acceptsAuthenticationSnapshotWhenEventWasEmittedBeforeAddonListener() throws Exception {
        TestRig rig = rig();
        UUID owner = UUID.randomUUID();
        var created = rig.manager.create(request(owner, 30));
        String botId = created.lease().botId();
        rig.bots.states.put(botId, AddonBotState.PLAY);
        rig.bots.servers.put(botId, "lobby");
        // Simulate an addon reload: the core has already confirmed AuthMe, but
        // the AUTHENTICATED event was emitted before LeaseManager subscribed.
        rig.bots.authentication.put(botId, true);

        rig.clock.advanceSeconds(5);
        rig.manager.tick();

        assertThat(rig.bots.switchRequests)
            .as("the positive core snapshot must recover an already-authenticated session")
            .isEqualTo(1);
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
        authenticate(rig, botId);
        rig.bots.deferSwitch = true;

        rig.clock.advanceSeconds(5);
        rig.manager.tick();
        rig.manager.onBotEvent(new AddonBotEvent(
            Instant.ofEpochMilli(rig.clock.millis()), botId, "DISCONNECTED", "auth transition"));
        rig.manager.onBotEvent(new AddonBotEvent(
            Instant.ofEpochMilli(rig.clock.millis()), botId, "AUTHENTICATED", "reconnected"));
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
        authenticate(rig, botId);
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


    // ------------------------------------------------------------------ long-term packages

    private static RentalConfig reloadConfig(long online, long offline, PackageConfig packages) {
        List<String> pool = IntStream.rangeClosed(1, 10).mapToObj(index -> "BOT_" + index).toList();
        return new RentalConfig("127.0.0.1", 18765, "01234567890123456789012345678901",
            Map.of("redstone", "http://127.0.0.1:18766", "survival", "http://127.0.0.1:18767"),
            "ellan_coin", online, offline, 3, 60, 90, pool, packages);
    }

    @Test
    void reloadingPricesChangesWhatPlayersAreQuotedWithoutRestart() throws Exception {
        TestRig rig = packageRig();
        UUID owner = UUID.randomUUID();
        assertThat(rig.manager.status(new StatusRequest(owner)).onlineRate()).isEqualTo(5);

        PackageConfig old = packageConfig();
        PackageConfig cheaper = new PackageConfig(true, old.pool(), old.maxPerPlayer(), old.maxCombat(), old.allowedServers(),
            70, 40, Map.of(1, 1, 7, 5, 30, 18),
            Map.of("small", new PackageConfig.Tier("small", "小", 3, 4, 300),
                "large", new PackageConfig.Tier("large", "大", 8, 10, 1500)));
        String summary = rig.manager.reloadPricing(reloadConfig(2, 3, cheaper));

        var status = rig.manager.status(new StatusRequest(owner));
        assertThat(status.onlineRate()).isEqualTo(2);
        assertThat(status.offlineRate()).isEqualTo(3);
        assertThat(status.offers().stream().filter(o -> o.tier().equals("small") && o.days() == 7).findFirst().orElseThrow().price())
            .isEqualTo(300L * 5);
        assertThat(summary).contains("online 2/min").contains("small=300");
        // the quote is also what create() enforces
        assertThat(rig.manager.create(packageRequest(owner, "redstone", "small", 7, false, 300L * 5)).success()).isTrue();
        rig.close();
    }

    @Test
    void reloadRejectsStructuralChangesAndKeepsTheOldPrices() throws Exception {
        TestRig rig = packageRig();
        UUID owner = UUID.randomUUID();
        PackageConfig old = packageConfig();

        PackageConfig fewerBots = new PackageConfig(true, List.of("BOT_11"), old.maxPerPlayer(), old.maxCombat(), old.allowedServers(),
            old.refundPercent(), old.combatSurchargePercent(), old.chargedDays(), old.tiers());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> rig.manager.reloadPricing(reloadConfig(1, 1, fewerBots)));

        PackageConfig droppedTier = new PackageConfig(true, old.pool(), old.maxPerPlayer(), old.maxCombat(), old.allowedServers(),
            old.refundPercent(), old.combatSurchargePercent(), old.chargedDays(), Map.of("small", old.tiers().get("small")));
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> rig.manager.reloadPricing(reloadConfig(1, 1, droppedTier)));
        assertThat(thrown.getMessage()).contains("packages.tiers");

        assertThat(rig.manager.status(new StatusRequest(owner)).onlineRate()).isEqualTo(5);
        rig.close();
    }

    private static final long DAY = 86_400L;

    private static PackageConfig packageConfig() {
        return new PackageConfig(true, List.of("BOT_11", "BOT_12", "BOT_13"), 2, 1, List.of("redstone", "survival"),
            80, 50, Map.of(1, 1, 7, 6, 30, 20),
            Map.of("small", new PackageConfig.Tier("small", "小", 3, 4, 450),
                "large", new PackageConfig.Tier("large", "大", 8, 10, 1800)));
    }

    private TestRig packageRig() throws Exception {
        return packageRig(temp.resolve(UUID.randomUUID() + ".db"));
    }

    private TestRig packageRig(java.nio.file.Path database) throws Exception {
        List<String> pool = IntStream.rangeClosed(1, 10).mapToObj(index -> "BOT_" + index).toList();
        RentalConfig config = new RentalConfig("127.0.0.1", 18765, "01234567890123456789012345678901",
            Map.of("redstone", "http://127.0.0.1:18766", "survival", "http://127.0.0.1:18767"),
            "ellan_coin", 5, 20, 3, 60, 90, pool, packageConfig());
        List<String> all = new ArrayList<>(pool);
        all.addAll(config.packages().pool());
        FakeBots bots = new FakeBots(all);
        FakeBridge bridge = new FakeBridge();
        MutableClock clock = new MutableClock(1_000_000L);
        LeaseManager manager = new LeaseManager(config, bots, bridge, new LeaseStore(database), new SilentLogger(), clock);
        return new TestRig(manager, bots, bridge, clock);
    }

    private static CreateRequest packageRequest(UUID owner, String server, String tier, int days, boolean combat, long paid) {
        return new CreateRequest(UUID.randomUUID(), owner, "Player", paid,
            new LocationData(server, "world", 1, 64, 2, 0, 0), "PACKAGE", tier, days, combat);
    }

    private static void arrive(TestRig rig, String botId, String server) {
        rig.bots.states.put(botId, AddonBotState.PLAY);
        rig.bots.servers.put(botId, server);
        authenticate(rig, botId);
        rig.manager.tick();
    }

    private static LeaseView only(TestRig rig, UUID owner) {
        return rig.manager.status(new StatusRequest(owner)).leases().getFirst();
    }

    @Test
    void packagePriceIsOwnedByTheAddonAndMismatchesAreRefunded() throws Exception {
        TestRig rig = packageRig();
        UUID owner = UUID.randomUUID();

        var wrong = rig.manager.create(packageRequest(owner, "survival", "small", 1, false, 449));
        assertThat(wrong.success()).isFalse();
        assertThat(wrong.code()).isEqualTo("PRICE_MISMATCH");
        assertThat(wrong.refundableCoins()).isEqualTo(449);

        assertThat(rig.manager.create(packageRequest(owner, "survival", "huge", 1, false, 450)).code()).isEqualTo("BAD_PACKAGE");
        assertThat(rig.manager.create(packageRequest(owner, "survival", "small", 3, false, 450)).code()).isEqualTo("BAD_PACKAGE");
        assertThat(rig.manager.create(packageRequest(owner, "spawn", "small", 1, false, 450)).code()).isEqualTo("SERVER_NOT_ALLOWED");

        var offers = rig.manager.status(new StatusRequest(owner)).offers();
        assertThat(offers).hasSize(6);
        var smallWeek = offers.stream().filter(o -> o.tier().equals("small") && o.days() == 7).findFirst().orElseThrow();
        assertThat(smallWeek.price()).isEqualTo(2700);        // 7 days charged as 6
        assertThat(smallWeek.combatPrice()).isEqualTo(4050);  // +50 %
        var largeMonth = offers.stream().filter(o -> o.tier().equals("large") && o.days() == 30).findFirst().orElseThrow();
        assertThat(largeMonth.price()).isEqualTo(36_000);     // 30 days charged as 20
        rig.close();
    }

    @Test
    void packageClockStartsOnArrivalAndNothingIsBilledPerMinute() throws Exception {
        TestRig rig = packageRig();
        UUID owner = UUID.randomUUID();
        var created = rig.manager.create(packageRequest(owner, "survival", "small", 1, false, 450));
        assertThat(created.success()).isTrue();
        LeaseView view = created.lease();
        assertThat(view.slot()).as("package slots follow the hourly slots").isEqualTo(4);
        assertThat(view.botId()).isEqualTo("BOT_11");
        assertThat(view.plan()).isEqualTo("PACKAGE");
        assertThat(view.expiresAt()).as("no clock before the bot arrives").isZero();

        rig.clock.advanceSeconds(30);
        arrive(rig, view.botId(), "survival");
        LeaseView active = only(rig, owner);
        assertThat(active.state()).isEqualTo("ACTIVE");
        assertThat(active.reserveCoins()).isZero();
        assertThat(active.spentCoins()).isEqualTo(450);
        assertThat(active.expiresAt()).isEqualTo(rig.clock.millis() + DAY * 1000);
        assertThat(rig.bridge.placements).containsExactly("small@survival");

        rig.clock.advanceSeconds(DAY / 2);
        rig.manager.tick();
        assertThat(only(rig, owner).spentCoins()).as("no per-minute billing").isEqualTo(450);
        assertThat(only(rig, owner).state()).isEqualTo("ACTIVE");
        rig.close();
    }

    @Test
    void packageExpiresWithoutRefundAndFreesItsBot() throws Exception {
        TestRig rig = packageRig();
        UUID owner = UUID.randomUUID();
        String botId = rig.manager.create(packageRequest(owner, "redstone", "small", 1, false, 450)).lease().botId();
        arrive(rig, botId, "redstone");

        rig.clock.advanceSeconds(DAY - 5);
        rig.manager.tick();
        assertThat(only(rig, owner).state()).isEqualTo("ACTIVE");

        rig.clock.advanceSeconds(10);
        rig.manager.tick();
        rig.manager.tick();
        assertThat(rig.manager.status(new StatusRequest(owner)).leases()).isEmpty();
        assertThat(rig.bridge.refunds).as("a fully used package refunds nothing").isEmpty();
        assertThat(rig.bots.states.get(botId)).isEqualTo(AddonBotState.STOPPED);
        assertThat(rig.manager.status(new StatusRequest(owner)).packagePoolAvailable()).isEqualTo(3);
        rig.close();
    }

    @Test
    void packageDowntimeIsCreditedAndStartupTimeoutDoesNotForfeitIt() throws Exception {
        TestRig rig = packageRig();
        UUID owner = UUID.randomUUID();
        String botId = rig.manager.create(packageRequest(owner, "survival", "small", 1, false, 450)).lease().botId();
        arrive(rig, botId, "survival");
        long expiryBefore = only(rig, owner).expiresAt();

        rig.clock.advanceSeconds(3600);
        rig.manager.tick();                                    // active until t0 + 1h
        rig.bots.states.put(botId, AddonBotState.RECONNECT_WAIT);
        rig.manager.onBotEvent(new AddonBotEvent(Instant.ofEpochMilli(rig.clock.millis()), botId, "DISCONNECTED", "x"));

        rig.clock.advanceSeconds(1800);                        // 30 min down, far beyond the 90 s startup timeout
        rig.manager.tick();
        assertThat(only(rig, owner).state()).as("an activated package keeps retrying").isEqualTo("STARTING");
        assertThat(rig.bridge.refunds).isEmpty();

        arrive(rig, botId, "survival");
        LeaseView back = only(rig, owner);
        assertThat(back.state()).isEqualTo("ACTIVE");
        assertThat(back.expiresAt() - expiryBefore).as("30 minutes credited").isEqualTo(1800L * 1000);
        rig.close();
    }

    @Test
    void cancellingARunningPackageRefundsUnusedWholeDaysMinusTheFee() throws Exception {
        TestRig rig = packageRig();
        UUID owner = UUID.randomUUID();
        String botId = rig.manager.create(packageRequest(owner, "survival", "small", 7, false, 2700)).lease().botId();
        arrive(rig, botId, "survival");

        rig.clock.advanceSeconds(2 * DAY + 3600);               // 4 whole days of 7 remain
        var cancelled = rig.manager.cancel(new OwnerSlotRequest(UUID.randomUUID(), owner, 4));
        assertThat(cancelled.success()).isTrue();
        assertThat(cancelled.refundableCoins()).isEqualTo(2700L * 4 / 7 * 80 / 100);
        assertThat(rig.bridge.refunds).containsExactly(2700L * 4 / 7 * 80 / 100);
        assertThat(rig.manager.status(new StatusRequest(owner)).leases()).isEmpty();
        rig.close();
    }

    @Test
    void cancellingBeforeArrivalRefundsEverything() throws Exception {
        TestRig rig = packageRig();
        UUID owner = UUID.randomUUID();
        rig.manager.create(packageRequest(owner, "redstone", "large", 1, false, 1800));
        var cancelled = rig.manager.cancel(new OwnerSlotRequest(UUID.randomUUID(), owner, 4));
        assertThat(cancelled.refundableCoins()).isEqualTo(1800);
        assertThat(rig.bridge.refunds).containsExactly(1800L);
        rig.close();
    }

    @Test
    void packageThatNeverArrivesIsRefundedInFull() throws Exception {
        TestRig rig = packageRig();
        UUID owner = UUID.randomUUID();
        rig.manager.create(packageRequest(owner, "redstone", "small", 1, false, 450));
        rig.clock.advanceSeconds(120);
        rig.manager.tick();
        assertThat(rig.bridge.refunds).containsExactly(450L);
        assertThat(rig.manager.status(new StatusRequest(owner)).leases()).isEmpty();
        rig.close();
    }

    @Test
    void packagesRejectTopUps() throws Exception {
        TestRig rig = packageRig();
        UUID owner = UUID.randomUUID();
        rig.manager.create(packageRequest(owner, "redstone", "small", 1, false, 450));
        var topUp = rig.manager.topUp(new TopUpRequest(UUID.randomUUID(), owner, 4, 100));
        assertThat(topUp.success()).isFalse();
        assertThat(topUp.code()).isEqualTo("PACKAGE_NO_TOPUP");
        assertThat(topUp.refundableCoins()).isEqualTo(100);
        rig.close();
    }

    @Test
    void packageLimitsCombatCapAndPoolsAreIndependent() throws Exception {
        TestRig rig = packageRig();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertThat(rig.manager.create(packageRequest(owner, "redstone", "small", 1, true, 675)).success()).isTrue();
        assertThat(rig.manager.create(packageRequest(owner, "redstone", "small", 1, false, 450)).success()).isTrue();

        var third = rig.manager.create(packageRequest(owner, "redstone", "small", 1, false, 450));
        assertThat(third.code()).isEqualTo("OWNER_LIMIT");
        assertThat(third.refundableCoins()).isEqualTo(450);

        var secondCombat = rig.manager.create(packageRequest(other, "redstone", "small", 1, true, 675));
        assertThat(secondCombat.code()).isEqualTo("COMBAT_FULL");
        assertThat(secondCombat.refundableCoins()).isEqualTo(675);

        // The hourly rental is unaffected by owning packages: its own limit and pool still apply.
        for (int index = 0; index < 3; index++) {
            assertThat(rig.manager.create(request(owner, 100)).success()).isTrue();
        }
        var status = rig.manager.status(new StatusRequest(owner));
        assertThat(status.leases()).hasSize(5);
        assertThat(status.packagePoolAvailable()).isEqualTo(1);
        assertThat(status.poolAvailable()).isEqualTo(7);
        rig.close();
    }

    @Test
    void combatFlagTravelsToTheBackend() throws Exception {
        TestRig rig = packageRig();
        UUID owner = UUID.randomUUID();
        String botId = rig.manager.create(packageRequest(owner, "redstone", "large", 7, true, 16_200)).lease().botId();
        arrive(rig, botId, "redstone");
        assertThat(rig.bridge.placements).containsExactly("large+combat@redstone");
        assertThat(only(rig, owner).combat()).isTrue();
        rig.close();
    }

    @Test
    void packageSurvivesAnAddonRestartWithItsClock() throws Exception {
        java.nio.file.Path database = temp.resolve("restart.db");
        TestRig first = packageRig(database);
        UUID owner = UUID.randomUUID();
        String botId = first.manager.create(packageRequest(owner, "survival", "small", 7, false, 2700)).lease().botId();
        arrive(first, botId, "survival");
        long expiry = only(first, owner).expiresAt();
        first.clock.advanceSeconds(600);
        first.manager.tick();
        first.close();

        TestRig second = packageRig(database);
        LeaseView restored = only(second, owner);
        assertThat(restored.plan()).isEqualTo("PACKAGE");
        assertThat(restored.tier()).isEqualTo("small");
        assertThat(restored.expiresAt()).isEqualTo(expiry);
        assertThat(restored.spentCoins()).isEqualTo(2700);
        second.close();
    }

    private static AddonServerSwitchResult switchResult(String botId) {
        return new AddonServerSwitchResult(
            AddonServerSwitchStatus.SWITCHED, botId, botId, "redstone", "SUCCESS");
    }

    private static void authenticate(TestRig rig, String botId) {
        rig.manager.onBotEvent(new AddonBotEvent(
            Instant.ofEpochMilli(rig.clock.millis()), botId, "AUTHENTICATED", "confirmed"));
    }

    private TestRig rig() throws Exception {
        RentalConfig config = new RentalConfig("127.0.0.1", 18765,
            "01234567890123456789012345678901", Map.of("redstone", "http://127.0.0.1:18766"), "ellan_coin", 5, 20, 3,
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
            new LocationData("redstone", "world", 1, 64, 2, 0, 0));
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
        private final List<String> placements = new ArrayList<>();

        @Override
        public boolean place(UUID leaseId, String botUsername, UUID ownerUuid, LocationData location,
                             String tier, boolean combat) {
            placements.add(tier + (combat ? "+combat" : "") + "@" + location.server());
            return true;
        }

        @Override
        public boolean refund(String server, UUID refundId, UUID leaseId, UUID ownerUuid, long coins, String reason) {
            refunds.add(coins);
            return true;
        }
    }

    private static final class FakeBots implements AddonBotService {
        private final Map<String, AddonBotState> states = new HashMap<>();
        private final Map<String, Boolean> authentication = new HashMap<>();
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
            return states.keySet().stream().map(id -> new AddonBotSnapshot(id, id, states.get(id),
                authentication.getOrDefault(id, false))).toList();
        }

        @Override
        public Optional<AddonBotSnapshot> bot(String id) {
            return Optional.ofNullable(states.get(id)).map(state -> new AddonBotSnapshot(id, id, state,
                authentication.getOrDefault(id, false)));
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
