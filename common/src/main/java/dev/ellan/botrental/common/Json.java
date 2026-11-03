package dev.ellan.botrental.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;

public final class Json {
   private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

   private Json() {
   }

   public static byte[] write(Object var0) {
      return GSON.toJson(var0).getBytes(StandardCharsets.UTF_8);
   }

   public static <T> T read(byte[] var0, Class<T> var1) {
      return (T)GSON.fromJson(new String(var0, StandardCharsets.UTF_8), var1);
   }
}
