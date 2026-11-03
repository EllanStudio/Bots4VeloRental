package dev.ellan.botrental.velocity;

import dev.ellan.botrental.common.LocationData;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class LeaseStore implements AutoCloseable {
   private final Connection connection;

   LeaseStore(Path var1) throws Exception {
      Class.forName("org.sqlite.JDBC");
      this.connection = DriverManager.getConnection("jdbc:sqlite:" + var1.toAbsolutePath());

      try (Statement var2 = this.connection.createStatement()) {
         var2.execute("PRAGMA journal_mode=WAL");
         var2.execute("PRAGMA synchronous=FULL");
         var2.execute(
            "CREATE TABLE IF NOT EXISTS leases (\n  lease_id TEXT PRIMARY KEY,\n  owner_uuid TEXT NOT NULL,\n  owner_name TEXT NOT NULL,\n  slot INTEGER NOT NULL,\n  bot_id TEXT NOT NULL,\n  bot_username TEXT NOT NULL,\n  reserve_coins INTEGER NOT NULL,\n  spent_coins INTEGER NOT NULL,\n  state TEXT NOT NULL,\n  server TEXT NOT NULL DEFAULT 'redstone',\n  world TEXT NOT NULL,\n  x REAL NOT NULL,\n  y REAL NOT NULL,\n  z REAL NOT NULL,\n  yaw REAL NOT NULL,\n  pitch REAL NOT NULL,\n  charged_once INTEGER NOT NULL,\n  created_at INTEGER NOT NULL,\n  next_charge_at INTEGER NOT NULL,\n  start_deadline INTEGER NOT NULL,\n  last_action_at INTEGER NOT NULL,\n  refund_id TEXT,\n  end_reason TEXT NOT NULL\n)\n"
         );
         if (!hasColumn(var2, "server")) {
            var2.execute("ALTER TABLE leases ADD COLUMN server TEXT NOT NULL DEFAULT 'redstone'");
         }

         var2.execute("CREATE INDEX IF NOT EXISTS leases_owner_state ON leases(owner_uuid, state)");
         var2.execute("CREATE INDEX IF NOT EXISTS leases_bot_state ON leases(bot_id, state)");
      }
   }

   private static boolean hasColumn(Statement var0, String var1) throws SQLException {
      try (ResultSet var2 = var0.executeQuery("PRAGMA table_info(leases)")) {
         while (var2.next()) {
            if (var1.equalsIgnoreCase(var2.getString("name"))) {
               return true;
            }
         }
      }

      return false;
   }

   synchronized List<Lease> loadOpen() throws SQLException {
      ArrayList var1 = new ArrayList();

      try (
         PreparedStatement var2 = this.connection.prepareStatement("SELECT * FROM leases WHERE state <> 'ENDED' ORDER BY created_at");
         ResultSet var3 = var2.executeQuery();
      ) {
         while (var3.next()) {
            String var4 = var3.getString("refund_id");
            var1.add(
               new Lease(
                  UUID.fromString(var3.getString("lease_id")),
                  UUID.fromString(var3.getString("owner_uuid")),
                  var3.getString("owner_name"),
                  var3.getInt("slot"),
                  var3.getString("bot_id"),
                  var3.getString("bot_username"),
                  var3.getLong("reserve_coins"),
                  var3.getLong("spent_coins"),
                  LeaseState.valueOf(var3.getString("state")),
                  new LocationData(
                     var3.getString("server"),
                     var3.getString("world"),
                     var3.getDouble("x"),
                     var3.getDouble("y"),
                     var3.getDouble("z"),
                     var3.getFloat("yaw"),
                     var3.getFloat("pitch")
                  ),
                  var3.getBoolean("charged_once"),
                  var3.getLong("created_at"),
                  var3.getLong("next_charge_at"),
                  var3.getLong("start_deadline"),
                  var3.getLong("last_action_at"),
                  var4 == null ? null : UUID.fromString(var4),
                  var3.getString("end_reason")
               )
            );
         }
      }

      return var1;
   }

   synchronized void save(Lease var1) throws SQLException {
      String var2 = "INSERT INTO leases (\n  lease_id, owner_uuid, owner_name, slot, bot_id, bot_username,\n  reserve_coins, spent_coins, state, server, world, x, y, z, yaw, pitch,\n  charged_once, created_at, next_charge_at, start_deadline,\n  last_action_at, refund_id, end_reason\n) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\nON CONFLICT(lease_id) DO UPDATE SET\n  reserve_coins=excluded.reserve_coins, spent_coins=excluded.spent_coins,\n  state=excluded.state, server=excluded.server, world=excluded.world, x=excluded.x, y=excluded.y,\n  z=excluded.z, yaw=excluded.yaw, pitch=excluded.pitch,\n  charged_once=excluded.charged_once, next_charge_at=excluded.next_charge_at,\n  start_deadline=excluded.start_deadline, last_action_at=excluded.last_action_at,\n  refund_id=excluded.refund_id, end_reason=excluded.end_reason\n";

      try (PreparedStatement var3 = this.connection.prepareStatement(var2)) {
         int var4 = 1;
         var3.setString(var4++, var1.id.toString());
         var3.setString(var4++, var1.ownerUuid.toString());
         var3.setString(var4++, var1.ownerName);
         var3.setInt(var4++, var1.slot);
         var3.setString(var4++, var1.botId);
         var3.setString(var4++, var1.botUsername);
         var3.setLong(var4++, var1.reserveCoins);
         var3.setLong(var4++, var1.spentCoins);
         var3.setString(var4++, var1.state.name());
         var3.setString(var4++, var1.location.server());
         var3.setString(var4++, var1.location.world());
         var3.setDouble(var4++, var1.location.x());
         var3.setDouble(var4++, var1.location.y());
         var3.setDouble(var4++, var1.location.z());
         var3.setFloat(var4++, var1.location.yaw());
         var3.setFloat(var4++, var1.location.pitch());
         var3.setBoolean(var4++, var1.chargedOnce);
         var3.setLong(var4++, var1.createdAt);
         var3.setLong(var4++, var1.nextChargeAt);
         var3.setLong(var4++, var1.startDeadline);
         var3.setLong(var4++, var1.lastActionAt);
         var3.setString(var4++, var1.refundId == null ? null : var1.refundId.toString());
         var3.setString(var4, var1.endReason);
         var3.executeUpdate();
      }
   }

   synchronized void delete(UUID var1) throws SQLException {
      try (PreparedStatement var2 = this.connection.prepareStatement("DELETE FROM leases WHERE lease_id = ?")) {
         var2.setString(1, var1.toString());
         var2.executeUpdate();
      }
   }

   @Override
   public synchronized void close() throws SQLException {
      this.connection.close();
   }
}
