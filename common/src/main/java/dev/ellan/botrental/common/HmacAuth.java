package dev.ellan.botrental.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HmacAuth {
    public static final String TIMESTAMP_HEADER = "X-BotRental-Timestamp";
    public static final String NONCE_HEADER = "X-BotRental-Nonce";
    public static final String SIGNATURE_HEADER = "X-BotRental-Signature";
    private static final Duration MAX_SKEW = Duration.ofSeconds(30);

    private final byte[] secret;
    private final Clock clock;
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public HmacAuth(String secret) {
        this(secret, Clock.systemUTC());
    }

    HmacAuth(String secret, Clock clock) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("shared secret must contain at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public SignedHeaders sign(String method, String path, byte[] body) {
        long timestamp = clock.millis();
        String nonce = UUID.randomUUID().toString();
        return new SignedHeaders(timestamp, nonce, signature(method, path, timestamp, nonce, body));
    }

    public boolean verify(String method, String path, byte[] body, String timestampValue,
                          String nonce, String suppliedSignature) {
        if (timestampValue == null || nonce == null || suppliedSignature == null || nonce.length() > 80) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampValue);
        }
        catch (NumberFormatException ignored) {
            return false;
        }
        long now = clock.millis();
        if (Math.abs(now - timestamp) > MAX_SKEW.toMillis()) {
            return false;
        }
        seenNonces.entrySet().removeIf(entry -> now - entry.getValue() > MAX_SKEW.toMillis() * 2);
        if (seenNonces.putIfAbsent(nonce, now) != null) {
            return false;
        }
        String expected = signature(method, path, timestamp, nonce, body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
            suppliedSignature.getBytes(StandardCharsets.US_ASCII));
    }

    private String signature(String method, String path, long timestamp, String nonce, byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body);
            String canonical = method.toUpperCase() + "\n" + path + "\n" + timestamp + "\n" + nonce
                + "\n" + Base64.getEncoder().encodeToString(digest);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception failure) {
            throw new IllegalStateException("could not sign request", failure);
        }
    }

    public record SignedHeaders(long timestamp, String nonce, String signature) {
    }
}
