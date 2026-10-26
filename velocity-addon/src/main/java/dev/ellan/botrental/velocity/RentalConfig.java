package dev.ellan.botrental.velocity;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

record RentalConfig(
    String bindAddress,
    int port,
    String sharedSecret,
    /** Lower-case backend server name to that server's Paper bridge base URL, in config order. */
    Map<String, String> bridges,
    String currency,
    long onlineRate,
    long offlineRate,
    int maxPerPlayer,
    long billingPeriodSeconds,
    long startupTimeoutSeconds,
    List<String> pool
) {
    static RentalConfig load(Path addonDirectory) throws IOException {
        Path file = addonDirectory.resolve("config.yml");
        if (Files.notExists(file)) {
            try (InputStream resource = RentalConfig.class.getResourceAsStream("/config.yml")) {
                if (resource == null) {
                    throw new IOException("bundled config.yml is missing");
                }
                Files.copy(resource, file);
            }
        }
        Map<String, Object> root;
        try (InputStream input = Files.newInputStream(file)) {
            root = new Yaml().load(input);
        }
        Map<String, Object> http = section(root, "http");
        Map<String, Object> bridge = section(root, "bridge");
        Map<String, Object> rental = section(root, "rental");
        List<String> pool = list(rental, "pool").stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
        RentalConfig config = new RentalConfig(
            string(http, "bind-address"), integer(http, "port"),
            string(bridge, "shared-secret"), bridges(bridge, rental), string(rental, "currency"),
            number(rental, "online-rate-per-minute"), number(rental, "offline-rate-per-minute"),
            integer(rental, "max-per-player"), number(rental, "billing-period-seconds"),
            number(rental, "startup-timeout-seconds"), pool);
        config.validate();
        return config;
    }

    private void validate() {
        if (!bindAddress.equals("127.0.0.1") && !bindAddress.equals("::1") && !bindAddress.equals("localhost")) {
            throw new IllegalArgumentException("http.bind-address must be loopback");
        }
        if (port < 1024 || port > 65535 || bridges.isEmpty() || currency.isBlank()) {
            throw new IllegalArgumentException("invalid port, bridge servers, or currency");
        }
        if (sharedSecret.length() < 32 || sharedSecret.startsWith("CHANGE_ME")) {
            throw new IllegalArgumentException("bridge.shared-secret must be replaced with a random secret");
        }
        if (onlineRate <= 0 || offlineRate <= 0 || maxPerPlayer <= 0
            || billingPeriodSeconds <= 0 || startupTimeoutSeconds < 10) {
            throw new IllegalArgumentException("rental rates and limits must be positive");
        }
        if (pool.isEmpty() || new LinkedHashSet<>(pool).size() != pool.size()) {
            throw new IllegalArgumentException("rental.pool must contain unique bot ids");
        }
    }

    boolean allows(String server) {
        return server != null && bridges.containsKey(server.toLowerCase(Locale.ROOT));
    }

    /**
     * Reads {@code bridge.servers}. Deployments from before multi-server support
     * only have {@code bridge.base-url} and {@code rental.allowed-server}; those
     * are accepted as a single-server map so an old config keeps working.
     */
    private static Map<String, String> bridges(Map<String, Object> bridge, Map<String, Object> rental) {
        Map<String, String> result = new LinkedHashMap<>();
        Object servers = bridge.get("servers");
        if (servers instanceof Map<?, ?> map) {
            map.forEach((name, url) -> {
                String key = name == null ? "" : name.toString().trim().toLowerCase(Locale.ROOT);
                String value = url == null ? "" : url.toString().trim();
                if (key.isEmpty() || value.isEmpty()) {
                    throw new IllegalArgumentException("bridge.servers entries need a name and a base URL");
                }
                result.put(key, value);
            });
        }
        else {
            result.put(string(rental, "allowed-server").toLowerCase(Locale.ROOT), string(bridge, "base-url"));
        }
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Object value = root == null ? null : root.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("missing config section: " + key);
        }
        return (Map<String, Object>) map;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalArgumentException("missing config value: " + key);
        }
        return value.toString().trim();
    }

    private static long number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(string(map, key));
    }

    private static int integer(Map<String, Object> map, String key) {
        return Math.toIntExact(number(map, key));
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("missing list: " + key);
        }
        return (List<String>) list;
    }
}
