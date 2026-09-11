package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.ApiMessages.ActionResponse;
import dev.ellan.botrental.common.ApiMessages.CreateRequest;
import dev.ellan.botrental.common.ApiMessages.OwnerSlotRequest;
import dev.ellan.botrental.common.ApiMessages.RelocateRequest;
import dev.ellan.botrental.common.ApiMessages.StatusRequest;
import dev.ellan.botrental.common.ApiMessages.StatusResponse;
import dev.ellan.botrental.common.ApiMessages.TopUpRequest;
import dev.ellan.botrental.common.LeaseView;
import dev.nulli0n.vbot.addon.api.AddonBotEvent;
import dev.nulli0n.vbot.addon.api.AddonBotService;
import dev.nulli0n.vbot.addon.api.AddonBotSnapshot;
import dev.nulli0n.vbot.addon.api.AddonBotState;
import dev.nulli0n.vbot.addon.api.AddonLogger;
import dev.nulli0n.vbot.addon.api.AddonServerSwitchResult;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

final class LeaseManager implements AutoCloseable {
    private static final String PREFIX = "[挂机机器人] ";

    private final RentalConfig config;
    private final AddonBotService bots;
    private final RentalBridge bridge;
    private final LeaseStore store;
    private final AddonLogger logger;
    private final Clock clock;
    private final Map<UUID, Lease> leases = new HashMap<>();
    /** Connection-local positive authentication evidence from Bots4Velo. */
    private final Set<String> authenticatedBots = new HashSet<>();
    /** Whether this bot has emitted lifecycle/auth events on the addon bus. */
    private final Set<String> observedAuthLifecycles = new HashSet<>();
    private final Map<UUID, ActionResponse> recentRequests = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, ActionResponse> eldest) {
            return size() > 2_000;
        }
    };

    LeaseManager(RentalConfig config, AddonBotService bots, RentalBridge bridge,
                 LeaseStore store, AddonLogger logger) throws Exception {
        this(config, bots, bridge, store, logger, Clock.systemUTC());
    }

    LeaseManager(RentalConfig config, AddonBotService bots, RentalBridge bridge,
                 LeaseStore store, AddonLogger logger, Clock clock) throws Exception {
        this.config = config;
        this.bots = bots;
        this.bridge = bridge;
        this.store = store;
        this.logger = logger;
        this.clock = clock;
        for (Lease lease : store.loadOpen()) {
            leases.put(lease.id, lease);
        }
    }

    synchronized void start() {
        long now = now();
        for (Lease lease : leases.values()) {
            if (lease.state == LeaseState.REFUND_PENDING) {
                continue;
            }
            lease.state = LeaseState.STARTING;
            lease.startDeadline = now + config.startupTimeoutSeconds() * 1000;
            lease.lastActionAt = 0;
            save(lease);
        }
        tick();
    }

    synchronized ActionResponse create(CreateRequest request) {
        if (request != null && request.requestId() != null && recentRequests.containsKey(request.requestId())) {
            return recentRequests.get(request.requestId());
        }
        ActionResponse response = createInternal(request);
        remember(request == null ? null : request.requestId(), response);
        return response;
    }

    private ActionResponse createInternal(CreateRequest request) {
        if (request == null || request.requestId() == null || request.ownerUuid() == null
            || request.location() == null || request.ownerName() == null || request.ownerName().isBlank()) {
            return ActionResponse.fail("BAD_REQUEST", "租赁信息不完整。");
        }
        if (request.reserveCoins() < config.onlineRate()) {
            return ActionResponse.failWithRefund("RESERVE_TOO_SMALL", "预存金额不足一分钟在线费用。",
                Math.max(0, request.reserveCoins()));
        }
        List<Lease> ownerLeases = ownerLeases(request.ownerUuid());
        if (ownerLeases.size() >= config.maxPerPlayer()) {
            return ActionResponse.failWithRefund("OWNER_LIMIT",
                "你已经租满 " + config.maxPerPlayer() + " 个机器人。", request.reserveCoins());
        }

        Set<String> used = new HashSet<>();
        leases.values().forEach(lease -> used.add(lease.botId.toLowerCase()));
        Optional<String> available = config.pool().stream()
            .filter(id -> !used.contains(id.toLowerCase()))
            .filter(id -> bots.bot(id).isPresent())
            .findFirst();
        if (available.isEmpty()) {
            return ActionResponse.failWithRefund("POOL_FULL", "机器人池暂时没有空位。", request.reserveCoins());
        }

        String botId = available.get();
        AddonBotSnapshot bot = bots.bot(botId).orElseThrow();
        int slot = firstFreeSlot(ownerLeases);
        long now = now();
        Lease lease = new Lease(UUID.randomUUID(), request.ownerUuid(), request.ownerName().trim(), slot,
            bot.id(), bot.username(), request.reserveCoins(), 0, LeaseState.STARTING, request.location(),
            false, now, 0, now + config.startupTimeoutSeconds() * 1000, 0, null, "");
        try {
            store.save(lease);
            leases.put(lease.id, lease);
            boolean requested = bots.start(lease.botId);
            boolean alreadyPlaying = bots.bot(lease.botId).map(AddonBotSnapshot::state)
                .filter(state -> state == AddonBotState.PLAY).isPresent();
            if (!requested && !alreadyPlaying) {
                leases.remove(lease.id);
                store.delete(lease.id);
                return ActionResponse.failWithRefund("BOT_START_FAILED",
                    "机器人暂时无法启动，请稍后再试。", request.reserveCoins());
            }
            lease.lastActionAt = now;
            save(lease);
            return ActionResponse.ok("租赁已受理，机器人正在前往指定位置。", view(lease));
        }
        catch (Exception failure) {
            logger.error("Could not create lease", failure);
            leases.remove(lease.id);
            try {
                store.delete(lease.id);
            }
            catch (Exception cleanupFailure) {
                logger.error("Could not clean up failed lease", cleanupFailure);
            }
            return ActionResponse.failWithRefund("INTERNAL_ERROR",
                "租赁创建失败，金币将原路退回。", request.reserveCoins());
        }
    }

    synchronized ActionResponse cancel(OwnerSlotRequest request) {
        if (request != null && request.requestId() != null && recentRequests.containsKey(request.requestId())) {
            return recentRequests.get(request.requestId());
        }
        Lease lease = ownedSlot(request);
        if (lease == null) {
            ActionResponse response = ActionResponse.fail("NOT_FOUND", "没有找到这个租赁位。");
            remember(request == null ? null : request.requestId(), response);
            return response;
        }
        long refundable = lease.reserveCoins;
        beginEnd(lease, "MANUAL_CANCEL", "租赁已取消，剩余金币正在退回。");
        ActionResponse response = new ActionResponse(true, "OK", "租赁已取消。", null, refundable);
        remember(request.requestId(), response);
        return response;
    }

    synchronized ActionResponse topUp(TopUpRequest request) {
        if (request != null && request.requestId() != null && recentRequests.containsKey(request.requestId())) {
            return recentRequests.get(request.requestId());
        }
        ActionResponse response = topUpInternal(request);
        remember(request == null ? null : request.requestId(), response);
        return response;
    }

    private ActionResponse topUpInternal(TopUpRequest request) {
        Lease lease = ownedSlot(request == null ? null
            : new OwnerSlotRequest(request.requestId(), request.ownerUuid(), request.slot()));
        if (lease == null || lease.state == LeaseState.REFUND_PENDING) {
            return ActionResponse.failWithRefund("NOT_FOUND", "没有找到可充值的租赁位。",
                request == null ? 0 : Math.max(0, request.coins()));
        }
        if (request.coins() <= 0) {
            return ActionResponse.fail("BAD_AMOUNT", "充值金额必须大于 0。");
        }
        try {
            lease.reserveCoins = Math.addExact(lease.reserveCoins, request.coins());
            save(lease);
            return ActionResponse.ok("已补充 " + request.coins() + " 艾尔岚金币。", view(lease));
        }
        catch (ArithmeticException failure) {
            return ActionResponse.failWithRefund("AMOUNT_TOO_LARGE", "充值金额过大。", request.coins());
        }
    }

    synchronized ActionResponse relocate(RelocateRequest request) {
        if (request != null && request.requestId() != null && recentRequests.containsKey(request.requestId())) {
            return recentRequests.get(request.requestId());
        }
        ActionResponse response = relocateInternal(request);
        remember(request == null ? null : request.requestId(), response);
        return response;
    }

    private ActionResponse relocateInternal(RelocateRequest request) {
        Lease lease = ownedSlot(request == null ? null
            : new OwnerSlotRequest(request.requestId(), request.ownerUuid(), request.slot()));
        if (lease == null || request.location() == null) {
            return ActionResponse.fail("NOT_FOUND", "没有找到这个租赁位。");
        }
        try {
            if (lease.state == LeaseState.ACTIVE && !bridge.place(
                lease.id, lease.botUsername, lease.ownerUuid, request.location())) {
                return ActionResponse.fail("PLACE_FAILED", "机器人暂时无法移动到新位置。");
            }
            lease.location = request.location();
            save(lease);
            return ActionResponse.ok("机器人位置已更新。", view(lease));
        }
        catch (Exception failure) {
            logger.error("Could not relocate lease " + lease.id, failure);
            return ActionResponse.fail("BRIDGE_UNAVAILABLE", "红石服位置服务暂时不可用。");
        }
    }

    synchronized StatusResponse status(StatusRequest request) {
        if (request == null || request.ownerUuid() == null) {
            return new StatusResponse(false, "BAD_REQUEST", "缺少玩家信息。", config.pool().size(),
                poolAvailable(), config.maxPerPlayer(), config.onlineRate(), config.offlineRate(), List.of());
        }
        List<LeaseView> views = ownerLeases(request.ownerUuid()).stream()
            .sorted(Comparator.comparingInt(lease -> lease.slot)).map(this::view).toList();
        return new StatusResponse(true, "OK", "OK", config.pool().size(), poolAvailable(),
            config.maxPerPlayer(), config.onlineRate(), config.offlineRate(), views);
    }

    synchronized void onBotEvent(AddonBotEvent event) {
        if (event == null) {
            return;
        }
        Lease lease = leases.values().stream()
            .filter(candidate -> candidate.botId.equalsIgnoreCase(event.botId())).findFirst().orElse(null);
        if (lease == null || lease.state == LeaseState.REFUND_PENDING) {
            return;
        }
        String botKey = lease.botId.toLowerCase();
        if (event.type() != null && (event.type().equals("PLAY")
            || event.type().startsWith("AUTH_"))) {
            observedAuthLifecycles.add(botKey);
        }
        if ("AUTHENTICATED".equals(event.type())) {
            authenticatedBots.add(botKey);
            return;
        }
        if ("DISCONNECTED".equals(event.type()) || "STOPPED".equals(event.type())) {
            authenticatedBots.remove(botKey);
            observedAuthLifecycles.remove(botKey);
            lease.serverSwitchGeneration++;
            lease.serverSwitchInFlight = false;
            lease.state = LeaseState.STARTING;
            lease.startDeadline = now() + config.startupTimeoutSeconds() * 1000;
            lease.lastActionAt = 0;
            lease.nextChargeAt = Math.max(lease.nextChargeAt,
                now() + config.billingPeriodSeconds() * 1000);
            save(lease);
        }
    }

    synchronized void tick() {
        reconcileUnusedPool();
        for (Lease lease : new ArrayList<>(leases.values())) {
            try {
                switch (lease.state) {
                    case STARTING -> tickStarting(lease);
                    case ACTIVE -> tickActive(lease);
                    case REFUND_PENDING -> tryRefund(lease);
                    case ENDED -> leases.remove(lease.id);
                }
            }
            catch (Exception failure) {
                logger.error("Lease tick failed for " + lease.id, failure);
            }
        }
    }

    private void tickStarting(Lease lease) throws Exception {
        long now = now();
        if (now >= lease.startDeadline) {
            beginEnd(lease, "START_TIMEOUT", "机器人启动超时，预存金币将全部退回。");
            return;
        }
        AddonBotSnapshot snapshot = bots.bot(lease.botId).orElse(null);
        if (snapshot == null) {
            beginEnd(lease, "BOT_MISSING", "机器人配置缺失，预存金币将全部退回。");
            return;
        }
        if (snapshot.state() != AddonBotState.PLAY) {
            if (lease.serverSwitchInFlight) {
                return;
            }
            if (now - lease.lastActionAt >= 10_000) {
                // Bots4Velo owns the backoff while a session is already waiting
                // to reconnect. Calling the operator-facing reconnect API here
                // would cancel that pending attempt and enqueue a replacement
                // behind the global spawn interval, making one disconnect take
                // minutes to recover when the pool is busy. Only a terminal
                // FAILED session needs an explicit recovery request; a
                // RECONNECT_WAIT session is already recovering by itself.
                if (snapshot.state() == AddonBotState.FAILED) {
                    bots.reconnect(lease.botId);
                }
                else if (snapshot.state() == AddonBotState.STOPPED) {
                    bots.start(lease.botId);
                }
                lease.lastActionAt = now;
                save(lease);
            }
            return;
        }
        // PLAY only means the transport is connected.  Gate the rental's
        // first server switch on the core's confirmed AuthMe/VeloAuth state;
        // otherwise the old 5-second poll turns AUTHENTICATION_PENDING into a
        // noisy retry loop while the authentication conversation is pending.
        if (observedAuthLifecycles.contains(lease.botId.toLowerCase())
            && !authenticatedBots.contains(lease.botId.toLowerCase())) {
            return;
        }
        String server = bots.currentServer(lease.botId).orElse("");
        if (!server.equalsIgnoreCase(config.allowedServer())) {
            if (!lease.serverSwitchInFlight && now - lease.lastActionAt >= 5_000) {
                requestServerSwitch(lease, now);
            }
            return;
        }
        if (!bridge.place(lease.id, lease.botUsername, lease.ownerUuid, lease.location)) {
            return;
        }
        activate(lease);
    }

    private void requestServerSwitch(Lease lease, long now) {
        long generation = ++lease.serverSwitchGeneration;
        lease.serverSwitchInFlight = true;
        lease.lastActionAt = now;
        save(lease);

        CompletionStage<AddonServerSwitchResult> request;
        try {
            request = bots.switchServer(lease.botId, config.allowedServer());
        }
        catch (RuntimeException failure) {
            if (lease.serverSwitchGeneration == generation) {
                lease.serverSwitchInFlight = false;
            }
            logger.warn("Server switch request failed for " + lease.botId + ": " + failure.getMessage());
            return;
        }
        if (request == null) {
            if (lease.serverSwitchGeneration == generation) {
                lease.serverSwitchInFlight = false;
            }
            logger.warn("Server switch service returned no result for " + lease.botId);
            return;
        }
        request.whenComplete((result, failure) -> onServerSwitchResult(
            lease, generation, result, failure));
    }

    private void onServerSwitchResult(Lease lease, long generation,
                                      AddonServerSwitchResult result, Throwable failure) {
        synchronized (this) {
            if (leases.get(lease.id) != lease || lease.state == LeaseState.REFUND_PENDING
                || lease.state == LeaseState.ENDED || lease.serverSwitchGeneration != generation) {
                return;
            }
            lease.serverSwitchInFlight = false;
            if (failure != null) {
                logger.warn("Server switch request failed for " + lease.botId + ": " + failure.getMessage());
                return;
            }
            if (result == null) {
                logger.warn("Server switch service returned no result for " + lease.botId);
                return;
            }
            if (!result.successful()) {
                logger.warn("Server switch for " + lease.botId + " was not accepted: " + result.status());
            }
        }
    }

    private void activate(Lease lease) {
        long now = now();
        if (!lease.chargedOnce) {
            long rate = currentRate(lease);
            if (lease.reserveCoins < rate) {
                beginEnd(lease, "OUT_OF_FUNDS", "预存金币不足，机器人租赁已经结束。");
                return;
            }
            lease.reserveCoins -= rate;
            lease.spentCoins += rate;
            lease.chargedOnce = true;
        }
        lease.state = LeaseState.ACTIVE;
        lease.nextChargeAt = now + config.billingPeriodSeconds() * 1000;
        lease.lastActionAt = now;
        save(lease);
        bots.sendPlayerMessage(lease.ownerUuid,
            PREFIX + "机器人 " + lease.botUsername + " 已到达指定位置。");
    }

    private void tickActive(Lease lease) {
        AddonBotSnapshot snapshot = bots.bot(lease.botId).orElse(null);
        String server = bots.currentServer(lease.botId).orElse("");
        if (snapshot == null || snapshot.state() != AddonBotState.PLAY
            || !server.equalsIgnoreCase(config.allowedServer())) {
            lease.state = LeaseState.STARTING;
            lease.startDeadline = now() + config.startupTimeoutSeconds() * 1000;
            lease.lastActionAt = 0;
            lease.nextChargeAt = now() + config.billingPeriodSeconds() * 1000;
            save(lease);
            return;
        }
        if (now() < lease.nextChargeAt) {
            return;
        }
        long rate = currentRate(lease);
        if (lease.reserveCoins < rate) {
            beginEnd(lease, "OUT_OF_FUNDS", "预存金币不足，机器人租赁已经结束。");
            return;
        }
        lease.reserveCoins -= rate;
        lease.spentCoins += rate;
        lease.nextChargeAt = now() + config.billingPeriodSeconds() * 1000;
        save(lease);
    }

    private void beginEnd(Lease lease, String reason, String message) {
        lease.state = LeaseState.REFUND_PENDING;
        lease.serverSwitchGeneration++;
        lease.serverSwitchInFlight = false;
        lease.endReason = reason;
        if (lease.refundId == null) {
            lease.refundId = UUID.randomUUID();
        }
        bots.stop(lease.botId);
        save(lease);
        bots.sendPlayerMessage(lease.ownerUuid, PREFIX + message);
        tryRefund(lease);
    }

    private void tryRefund(Lease lease) {
        try {
            if (lease.reserveCoins > 0 && !bridge.refund(lease.refundId, lease.id,
                lease.ownerUuid, lease.reserveCoins, lease.endReason)) {
                return;
            }
            long refunded = lease.reserveCoins;
            lease.reserveCoins = 0;
            lease.state = LeaseState.ENDED;
            save(lease);
            leases.remove(lease.id);
            if (refunded > 0) {
                bots.sendPlayerMessage(lease.ownerUuid,
                    PREFIX + "已退回 " + refunded + " 枚艾尔岚金币。");
            }
        }
        catch (Exception failure) {
            logger.warn("Refund remains pending for " + lease.id + ": " + failure.getMessage());
        }
    }

    private void reconcileUnusedPool() {
        Set<String> used = new HashSet<>();
        leases.values().forEach(lease -> used.add(lease.botId.toLowerCase()));
        for (String botId : config.pool()) {
            if (used.contains(botId.toLowerCase())) {
                continue;
            }
            bots.bot(botId).filter(snapshot -> snapshot.state() != AddonBotState.STOPPED
                && snapshot.state() != AddonBotState.STOPPING).ifPresent(snapshot -> bots.stop(snapshot.id()));
        }
    }

    private Lease ownedSlot(OwnerSlotRequest request) {
        if (request == null || request.ownerUuid() == null || request.slot() < 1
            || request.slot() > config.maxPerPlayer()) {
            return null;
        }
        return leases.values().stream().filter(lease -> lease.ownerUuid.equals(request.ownerUuid())
            && lease.slot == request.slot()).findFirst().orElse(null);
    }

    private List<Lease> ownerLeases(UUID owner) {
        return leases.values().stream().filter(lease -> lease.ownerUuid.equals(owner)
            && lease.state != LeaseState.ENDED).toList();
    }

    private int firstFreeSlot(List<Lease> ownerLeases) {
        Set<Integer> used = new HashSet<>();
        ownerLeases.forEach(lease -> used.add(lease.slot));
        for (int slot = 1; slot <= config.maxPerPlayer(); slot++) {
            if (!used.contains(slot)) {
                return slot;
            }
        }
        throw new IllegalStateException("no free owner slot");
    }

    private int poolAvailable() {
        return Math.max(0, config.pool().size() - leases.size());
    }

    private LeaseView view(Lease lease) {
        long rate = currentRate(lease);
        return new LeaseView(lease.id, lease.slot, lease.botId, lease.botUsername,
            lease.state.name(), lease.reserveCoins, lease.spentCoins, rate,
            rate <= 0 ? 0 : lease.reserveCoins / rate, lease.location);
    }

    private long currentRate(Lease lease) {
        return bots.isPlayerOnline(lease.ownerUuid) ? config.onlineRate() : config.offlineRate();
    }

    private void save(Lease lease) {
        try {
            store.save(lease);
        }
        catch (Exception failure) {
            throw new IllegalStateException("could not persist lease " + lease.id, failure);
        }
    }

    private void remember(UUID requestId, ActionResponse response) {
        if (requestId != null) {
            recentRequests.put(requestId, response);
        }
    }

    private long now() {
        return clock.millis();
    }

    @Override
    public synchronized void close() throws Exception {
        store.close();
    }
}
