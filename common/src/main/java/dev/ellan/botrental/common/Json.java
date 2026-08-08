package dev.ellan.botrental.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;

public final class Json {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static byte[] write(Object value) {
        return GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    public static <T> T read(byte[] bytes, Class<T> type) {
        return GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
    }
}
