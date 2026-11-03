package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.LocationData;
import java.util.UUID;

interface RentalBridge {
   boolean place(UUID var1, String var2, UUID var3, LocationData var4) throws Exception;

   boolean refund(String var1, UUID var2, UUID var3, UUID var4, long var5, String var7) throws Exception;
}
