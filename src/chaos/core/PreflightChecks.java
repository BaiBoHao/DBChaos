package chaos.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/** Configuration and statistics checks. Unknown critical settings fail closed. */
public final class PreflightChecks implements AutoCloseable {
    private final Connection conn;
    private final boolean mysql;
    private final Map<String, String> settings = new HashMap<String, String>();

    public PreflightChecks(Connection conn, String sqlType) throws SQLException {
        this.conn = conn;
        this.mysql = "mysql".equals(sqlType);
        try {
            require(mysql || "postgresql".equals(sqlType), "不支持的数据库类型: " + sqlType);
            if (!mysql) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.setQueryTimeout(10);
                    try (ResultSet rs = stmt.executeQuery("SELECT name, setting, unit FROM pg_settings")) {
                        while (rs.next()) {
                            String unit = rs.getString(3);
                            settings.put(rs.getString(1), rs.getString(2) + (unit == null ? "" : unit));
                        }
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            try { conn.close(); } catch (SQLException ignored) {}
            throw e;
        }
    }

    Connection connection() { return conn; }

    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public String scalar(String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(10);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                require(rs.next(), "检查无结果: " + sql);
                return rs.getString(1);
            }
        }
    }

    public String setting(String name) throws SQLException {
        if (!mysql) {
            String value = settings.get(name);
            require(value != null, "无法读取必要配置 " + name);
            return value;
        }
        // Names are internal constants, never user input.
        return scalar("SELECT @@" + name);
    }

    public void connections(long needed) throws SQLException {
        long max = Long.parseLong(setting("max_connections"));
        long used = Long.parseLong(scalar(mysql
                ? "SELECT COUNT(*) FROM information_schema.processlist"
                : "SELECT COUNT(*) FROM pg_stat_activity"));
        long reserved = 0;
        if (!mysql) {
            for (String name : new String[] {"superuser_reserved_connections", "reserved_connections"}) {
                if (settings.containsKey(name)) reserved += Long.parseLong(settings.get(name));
            }
            // Role limits apply even when the instance still has free slots.
            long limit = Long.parseLong(scalar("SELECT rolconnlimit FROM pg_roles WHERE rolname = current_user"));
            long roleUsed = Long.parseLong(scalar("SELECT COUNT(*) FROM pg_stat_activity WHERE usename = current_user"));
            require(limit < 0 || limit - roleUsed >= needed, "当前账号连接余量不足，需要 " + needed);
        } else {
            // A partial process list would overestimate free capacity.
            require("1".equals(scalar("SELECT COUNT(*) FROM information_schema.processlist WHERE ID = CONNECTION_ID()")),
                    "无法读取连接统计");
            used = Long.parseLong(scalar("SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Threads_connected'"));
            long limit = Long.parseLong(setting("max_user_connections"));
            require(limit == 0 || limit - Long.parseLong(scalar(
                    "SELECT COUNT(*) FROM information_schema.processlist WHERE USER = SUBSTRING_INDEX(CURRENT_USER(), '@', 1)")) >= needed,
                    "当前账号连接余量不足，需要 " + needed);
        }
        require(max - reserved - used >= needed, "数据库连接余量不足，需要 " + needed + "，当前已用 " + used + "/" + max);
    }

    public void snapshot(long duration) throws SQLException {
        int isolation = conn.getTransactionIsolation();
        require(isolation == Connection.TRANSACTION_REPEATABLE_READ
                || isolation == Connection.TRANSACTION_SERIALIZABLE,
                "新连接默认隔离级别必须保留事务级快照（REPEATABLE READ / SERIALIZABLE）");
        transactionTimeouts(duration);
    }

    public void transactionTimeouts(long duration) throws SQLException {
        if (!mysql) {
            for (String name : new String[] {"idle_in_transaction_session_timeout", "transaction_timeout", "session_timeout"}) {
                if (settings.containsKey(name)) {
                    long timeout = milliseconds(settings.get(name));
                    require(timeout == 0 || timeout > duration, name + " 必须为 0 或大于 -duration");
                }
            }
        } else {
            long timeout = Long.parseLong(setting("wait_timeout"));
            require(timeout == 0 || timeout > duration / 1000.0, "wait_timeout 必须大于 -duration");
        }
    }

    public void cleanupEnabled() throws SQLException {
        if (!mysql) {
            require("on".equalsIgnoreCase(setting("autovacuum")), "autovacuum 必须开启");
            require("on".equalsIgnoreCase(setting("track_counts")), "track_counts 必须开启");
        } else {
            require(Long.parseLong(setting("innodb_purge_threads")) > 0, "InnoDB 后台版本清理线程必须开启");
        }
    }

    public void deadlockTimeout(int seconds) throws SQLException {
        if (!mysql) {
            require(milliseconds(setting("deadlock_timeout")) < seconds * 1000L,
                    "deadlock_timeout 必须小于 -statement-timeout");
        } else {
            require("1".equals(setting("innodb_deadlock_detect")), "innodb_deadlock_detect 必须开启");
        }
    }

    public void tableRows(String table, long rows) throws SQLException {
        require(table != null && table.matches("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?"), "非法表名: " + table);
        require(Long.parseLong(scalar("SELECT COUNT(*) FROM (SELECT 1 FROM " + table + " LIMIT " + rows + ") preflight_rows")) >= rows,
                "表 " + table + " 行数不足，至少需要 " + rows);
        if (mysql) {
            String[] parts = table.split("\\.");
            String schema = parts.length == 2 ? parts[0] : scalar("SELECT DATABASE()");
            String name = parts[parts.length - 1];
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT ENGINE FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
                stmt.setQueryTimeout(10);
                stmt.setString(1, schema);
                stmt.setString(2, name);
                try (ResultSet rs = stmt.executeQuery()) {
                    require(rs.next() && "InnoDB".equalsIgnoreCase(rs.getString(1)), "目标表必须使用 InnoDB 事务行锁引擎");
                }
            }
        }
    }

    public void transactionalEngine() throws SQLException {
        require(!mysql || "InnoDB".equalsIgnoreCase(setting("default_storage_engine")),
                "新建测试表的 default_storage_engine 必须为 InnoDB");
    }

    public void prepared() throws SQLException {
        if (!mysql) {
            long limit = Long.parseLong(setting("max_prepared_transactions"));
            long used = Long.parseLong(scalar("SELECT COUNT(*) FROM pg_prepared_xacts"));
            require(limit > 0 && used < limit, "Prepared 事务未开启或已无剩余槽位");
        } else {
            transactionalEngine();
        }
    }

    public void packet(long bytes) throws SQLException {
        if (mysql) require(Long.parseLong(setting("max_allowed_packet")) > bytes * 2 + 1024,
                "max_allowed_packet 必须容纳编码后的单次请求（预检按两倍载荷加协议开销估算）");
    }

    public static long milliseconds(String raw) {
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        long multiplier = 1;
        if (value.endsWith("ms")) value = value.substring(0, value.length() - 2);
        else if (value.endsWith("min")) { multiplier = 60000; value = value.substring(0, value.length() - 3); }
        else if (value.endsWith("s")) { multiplier = 1000; value = value.substring(0, value.length() - 1); }
        else if (value.endsWith("h")) { multiplier = 3600000; value = value.substring(0, value.length() - 1); }
        else if (value.endsWith("d")) { multiplier = 86400000; value = value.substring(0, value.length() - 1); }
        return Math.multiplyExact(Long.parseLong(value.trim()), multiplier);
    }

    @Override public void close() throws SQLException { conn.close(); }
}
