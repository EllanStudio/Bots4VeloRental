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

    LeaseStore(Path databaseFile) throws Exception {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS leases (
                  lease_id TEXT PRIMARY KEY,
                  owner_uuid TEXT NOT NULL,
                  owner_name TEXT NOT NULL,
                  slot INTEGER NOT NULL,
                  bot_id TEXT NOT NULL,
                  bot_username TEXT NOT NULL,
                  reserve_coins INTEGER NOT NULL,
                  spent_coins INTEGER NOT NULL,
                  state TEXT NOT NULL,
                  world TEXT NOT NULL,
                  x REAL NOT NULL,
                  y REAL NOT NULL,
                  z REAL NOT NULL,
                  yaw REAL NOT NULL,
                  pitch REAL NOT NULL,
                  charged_once INTEGER NOT NULL,
                  created_at INTEGER NOT NULL,
                  next_charge_at INTEGER NOT NULL,
                  start_deadline INTEGER NOT NULL,
                  last_action_at INTEGER NOT NULL,
                  refund_id TEXT,
                  end_reason TEXT NOT NULL
                )
                """);
            statement.execute("CREATE INDEX IF NOT EXISTS leases_owner_state ON leases(owner_uuid, state)");
            statement.execute("CREATE INDEX IF NOT EXISTS leases_bot_state ON leases(bot_id, state)");
        }
    }

    synchronized List<Lease> loadOpen() throws SQLException {
        List<Lease> leases = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM leases WHERE state <> 'ENDED' ORDER BY created_at")) {
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    String refund = row.getString("refund_id");
                    leases.add(new Lease(
                        UUID.fromString(row.getString("lease_id")),
                        UUID.fromString(row.getString("owner_uuid")),
                        row.getString("owner_name"),
                        row.getInt("slot"),
                        row.getString("bot_id"),
                        row.getString("bot_username"),
                        row.getLong("reserve_coins"),
                        row.getLong("spent_coins"),
                        LeaseState.valueOf(row.getString("state")),
                        new LocationData(row.getString("world"), row.getDouble("x"), row.getDouble("y"),
                            row.getDouble("z"), row.getFloat("yaw"), row.getFloat("pitch")),
                        row.getBoolean("charged_once"),
                        row.getLong("created_at"),
                        row.getLong("next_charge_at"),
                        row.getLong("start_deadline"),
                        row.getLong("last_action_at"),
                        refund == null ? null : UUID.fromString(refund),
                        row.getString("end_reason")));
                }
            }
        }
        return leases;
    }

    synchronized void save(Lease lease) throws SQLException {
        String sql = """
            INSERT INTO leases (
              lease_id, owner_uuid, owner_name, slot, bot_id, bot_username,
              reserve_coins, spent_coins, state, world, x, y, z, yaw, pitch,
              charged_once, created_at, next_charge_at, start_deadline,
              last_action_at, refund_id, end_reason
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(lease_id) DO UPDATE SET
              reserve_coins=excluded.reserve_coins, spent_coins=excluded.spent_coins,
              state=excluded.state, world=excluded.world, x=excluded.x, y=excluded.y,
              z=excluded.z, yaw=excluded.yaw, pitch=excluded.pitch,
              charged_once=excluded.charged_once, next_charge_at=excluded.next_charge_at,
              start_deadline=excluded.start_deadline, last_action_at=excluded.last_action_at,
              refund_id=excluded.refund_id, end_reason=excluded.end_reason
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, lease.id.toString());
            statement.setString(index++, lease.ownerUuid.toString());
            statement.setString(index++, lease.ownerName);
            statement.setInt(index++, lease.slot);
            statement.setString(index++, lease.botId);
            statement.setString(index++, lease.botUsername);
            statement.setLong(index++, lease.reserveCoins);
            statement.setLong(index++, lease.spentCoins);
            statement.setString(index++, lease.state.name());
            statement.setString(index++, lease.location.world());
            statement.setDouble(index++, lease.location.x());
            statement.setDouble(index++, lease.location.y());
            statement.setDouble(index++, lease.location.z());
            statement.setFloat(index++, lease.location.yaw());
            statement.setFloat(index++, lease.location.pitch());
            statement.setBoolean(index++, lease.chargedOnce);
            statement.setLong(index++, lease.createdAt);
            statement.setLong(index++, lease.nextChargeAt);
            statement.setLong(index++, lease.startDeadline);
            statement.setLong(index++, lease.lastActionAt);
            statement.setString(index++, lease.refundId == null ? null : lease.refundId.toString());
            statement.setString(index, lease.endReason);
            statement.executeUpdate();
        }
    }

    synchronized void delete(UUID leaseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM leases WHERE lease_id = ?")) {
            statement.setString(1, leaseId.toString());
            statement.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
