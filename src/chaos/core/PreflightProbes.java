package chaos.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** Small, isolated capability probes; never uses a workload's fixed object names. */
public final class PreflightProbes {
    public interface Connections { Connection open() throws SQLException; }
    private final Connections connections;
    private final String sqlType;

    public PreflightProbes(Connections connections, String sqlType) {
        this.connections = connections;
        this.sqlType = sqlType;
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(10);
            stmt.execute(sql);
        }
    }

    private final class Fixture implements AutoCloseable {
        final String table = "chaos_preflight_" + UUID.randomUUID().toString().replace("-", "");
        final Connection owner;
        Fixture() throws SQLException {
            owner = connections.open();
            try {
                execute(owner, "CREATE TABLE " + table + " (id INT PRIMARY KEY, version BIGINT, group_id INT, payload TEXT)");
            } catch (SQLException | RuntimeException e) {
                try { owner.close(); } catch (SQLException cleanup) { e.addSuppressed(cleanup); }
                throw e;
            }
        }
        @Override public void close() throws SQLException {
            try { execute(owner, "DROP TABLE " + table); }
            catch (SQLException e) {
                throw new SQLException("预检探测表清理失败: " + table + "; " + e.getMessage(), e);
            } finally { owner.close(); }
        }
    }

    public void snapshotAndScans(boolean vacuumOptions) throws SQLException {
        try (Fixture fixture = new Fixture()) {
            String table = fixture.table;
            execute(fixture.owner, "CREATE INDEX idx_" + table + " ON " + table + " (group_id)");
            if (vacuumOptions && "postgresql".equals(sqlType)) {
                execute(fixture.owner, "ALTER TABLE " + table
                        + " SET (autovacuum_enabled = true, autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 50)");
            }
            execute(fixture.owner, "INSERT INTO " + table + " VALUES (1, 1, 1, 'probe')");
            try (PreflightChecks anchor = new PreflightChecks(connections.open(), sqlType)) {
                Connection snapshot = anchor.connection();
                snapshot.setAutoCommit(false);
                try {
                    PreflightChecks.require("1".equals(anchor.scalar("SELECT version FROM " + table + " WHERE id = 1")), "无法读取快照探测数据");
                    execute(fixture.owner, "UPDATE " + table + " SET version = 2 WHERE id = 1");
                    PreflightChecks.require("1".equals(anchor.scalar("SELECT version FROM " + table + " WHERE id = 1")),
                            "实际双连接探测未能保留事务级旧快照");
                    anchor.scalar("SELECT count(*) FROM " + table + " WHERE version >= 0");
                    anchor.scalar("SELECT group_id, count(*) FROM " + table
                            + " WHERE group_id BETWEEN 0 AND 32 GROUP BY group_id ORDER BY group_id");
                } finally { snapshot.rollback(); }
            }
        }
        System.out.println("[Preflight] 双连接快照、扫描 SQL" + (vacuumOptions ? "及表级清理选项" : "") + "实际探测通过");
    }

    public void statementTimeout(int seconds) throws SQLException {
        try (PreflightChecks check = new PreflightChecks(connections.open(), sqlType)) {
            boolean mysql = "mysql".equals(sqlType);
            execute(check.connection(), mysql ? "SET max_execution_time = " + seconds * 1000L
                    : "SET statement_timeout = '" + seconds + "s'");
            long actual = mysql ? Long.parseLong(check.scalar("SELECT @@max_execution_time"))
                    : PreflightChecks.milliseconds(check.scalar("SHOW statement_timeout"));
            PreflightChecks.require(actual == seconds * 1000L, "无法设置故障会话所需的语句超时");
        }
    }

    public void prepared() throws SQLException {
        String gid = "chaos_preflight_" + UUID.randomUUID().toString().replace("-", "");
        boolean mysql = "mysql".equals(sqlType);
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(true);
            boolean started = false, ended = false, prepared = false;
            try {
                if (mysql) {
                    execute(conn, "XA START '" + gid + "'");
                    started = true;
                    execute(conn, "SELECT 1");
                    execute(conn, "XA END '" + gid + "'");
                    ended = true;
                    execute(conn, "XA PREPARE '" + gid + "'");
                    prepared = true;
                    boolean found = false;
                    try (Statement stmt = conn.createStatement()) {
                        stmt.setQueryTimeout(10);
                        try (java.sql.ResultSet rs = stmt.executeQuery("XA RECOVER")) {
                            while (rs.next()) {
                                if (gid.equals(new String(rs.getBytes(4), java.nio.charset.StandardCharsets.UTF_8))) found = true;
                            }
                        }
                    }
                    PreflightChecks.require(found, "XA PREPARE 后未找到实际积压记录，源码 XA 流程不适用于当前环境");
                } else {
                    started = true;
                    execute(conn, "BEGIN; SELECT txid_current(); PREPARE TRANSACTION '" + gid + "'");
                    prepared = true;
                    try (PreflightChecks observer = new PreflightChecks(connections.open(), sqlType)) {
                        PreflightChecks.require("1".equals(observer.scalar("SELECT COUNT(*) FROM pg_prepared_xacts WHERE gid = '" + gid + "'")),
                                "PREPARE 后未找到实际 Prepared 记录");
                    }
                }
            } finally {
                // Always try to release this unique GID, including a PREPARE whose result was lost.
                if (started) {
                    try {
                        if (mysql && !ended) execute(conn, "XA END '" + gid + "'");
                        if (!mysql && !prepared) execute(conn, "ROLLBACK");
                    } finally {
                        try (Connection cleanup = connections.open()) {
                            try { execute(cleanup, mysql ? "XA ROLLBACK '" + gid + "'" : "ROLLBACK PREPARED '" + gid + "'"); }
                            catch (SQLException e) {
                                // Missing GID is expected when PREPARE failed. Other cleanup failures must stop injection.
                                if (!("42704".equals(e.getSQLState()) || (mysql && e.getErrorCode() == 1397))) {
                                    throw new SQLException("预检 Prepared/XA 清理失败，GID=" + gid + "; " + e.getMessage(), e);
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("[Preflight] Prepared/XA 准备、记录保留与回滚清理实际探测通过");
    }
}
