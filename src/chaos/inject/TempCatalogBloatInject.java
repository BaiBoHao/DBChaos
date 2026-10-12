package chaos.inject;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Config-adaptive white-box pressure; see ADAPTIVE_NOTES.md.
 *
 * <p>Two selectable logics via {@code -mode}:
 * <ul>
 *   <li>{@code resource} (default): native CREATE TEMP / INSERT / DROP churn. Isolated to the
 *       session's temporary namespace, so it does not bloat shared catalogs and does not hold
 *       locks the workload waits on — the observable cost is CPU/IO and session/thread-pool
 *       occupancy. This is genuine resource contention, not metadata-lock contention.</li>
 *   <li>{@code lock}: a privileged (sysadmin) connection holds a relation-level lock on a system
 *       catalog (default {@code pg_catalog.pg_class}) in an open transaction, cycled with a bounded
 *       hold and released by rollback. This blocks catalog readers — relcache/catcache rebuilds,
 *       backend startup, autovacuum, DDL — producing real metadata-lock contention. Requires a
 *       sysadmin account able to {@code SET xc_maintenance_mode = on}.</li>
 * </ul>
 */
public class TempCatalogBloatInject extends AdaptiveFaultInject {
    // resource mode
    private int threads;
    private int paceMs;
    private int burstSize;
    private boolean enterprise;

    // lock mode
    private static final Pattern QUALIFIED_NAME =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");
    private static final Set<String> LOCK_MODES = new HashSet<>(Arrays.asList(
            "ACCESS EXCLUSIVE", "EXCLUSIVE", "SHARE ROW EXCLUSIVE",
            "SHARE UPDATE EXCLUSIVE", "SHARE", "ROW EXCLUSIVE"));
    private String mode = "resource";
    private String catalog;
    private String lockMode;
    private int holdMs;
    private int gapMs;
    private String suUser;
    private String suPassword;
    private final List<Thread> lockers = Collections.synchronizedList(new ArrayList<Thread>());
    private final List<Connection> privConns = Collections.synchronizedList(new ArrayList<Connection>());
    private final Set<Statement> privStmts = ConcurrentHashMap.newKeySet();

    public TempCatalogBloatInject(String dbType) { super(dbType, "TEMP_CATALOG_BLOAT"); }

    protected void configure(String[] args) {
        String requested = getArg(args, "-mode");
        mode = requested == null ? "resource" : requested.trim().toLowerCase(Locale.ROOT);
        if (mode.equals("lock")) {
            configureLock(args);
            return;
        }
        if (!mode.equals("resource")) {
            throw new IllegalArgumentException("-mode must be one of: resource | lock");
        }
        configureResource(args);
    }

    private void configureResource(String[] args) {
        enterprise = isEnterprise();
        int baseCap = enterprise ? Math.min(60, budget()) : Math.min(16, budget());
        threads = clamp(arg(args, "-threads", baseCap), 1, Math.min(enterprise ? 64 : 32, budget()));
        burstSize = enterprise ? 5 : 1;
        if (enterprise) {
            paceMs = 0; // Continuous DDL storm without artificial throttling
        } else {
            int target = 1000;
            int pace = (int)Math.round(1000.0 * threads * burstSize / target) - 12 * burstSize;
            paceMs = Math.max(1, pace);
        }
        logAdaptive("Resource-contention mode: native temporary catalog create/drop; threads=" + threads +
                "; burst=" + burstSize + "; paceMs=" + paceMs + "; connection budget=" + budget() +
                (enterprise ? " [Enterprise High-Concurrency Catalog DDL Storm Enabled]" : ""));
    }

    private void configureLock(String[] args) {
        // The base probe is SELECT 1, which is served from cache and never touches pg_class, so a
        // catalog lock would be invisible to the closed loop and it would escalate pointlessly.
        closedLoop = false;
        catalog = getArg(args, "-catalog");
        if (catalog == null || catalog.trim().isEmpty()) {
            catalog = "pg_catalog.pg_class";
        }
        catalog = catalog.trim();
        if (!QUALIFIED_NAME.matcher(catalog).matches()) {
            throw new IllegalArgumentException("-catalog must be table or schema.table, got: " + catalog);
        }
        lockMode = getArg(args, "-lock_mode");
        lockMode = (lockMode == null ? "ACCESS EXCLUSIVE" : lockMode.trim().toUpperCase(Locale.ROOT));
        if (!LOCK_MODES.contains(lockMode)) {
            throw new IllegalArgumentException("-lock_mode must be one of: " + LOCK_MODES);
        }
        int cap = Math.max(1, budget());
        threads = clamp(arg(args, "-threads", 1), 1, cap);
        holdMs = clamp(arg(args, "-hold_ms", 500), 1, 60000);
        gapMs = clamp(arg(args, "-gap_ms", 100), 0, 60000);
        String overrideUser = getArg(args, "-su_user");
        String overridePassword = getArg(args, "-su_password");
        suUser = (overrideUser == null || overrideUser.isEmpty()) ? user : overrideUser;
        suPassword = (overridePassword == null || overridePassword.isEmpty()) ? password : overridePassword;
        logAdaptive("Privileged metadata-lock mode: LOCK TABLE " + catalog + " IN " + lockMode +
                " MODE; threads=" + threads + "; holdMs=" + holdMs + "; gapMs=" + gapMs +
                "; pidUser=" + user + "; lockUser=" + suUser + " [real metadata-lock contention]");
    }

    protected void startWork() {
        if (mode.equals("lock")) {
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(this::runLocker, faultType + "-locker-" + i);
                t.setDaemon(true);
                lockers.add(t);
                t.start();
            }
            return;
        }
        for (int i=0; i<threads; i++) worker(conn -> {
            String table = "tmp_chaos_catalog";
            for (int b=0; b<burstSize && running; b++) {
                if (enterprise) {
                    sql(conn, "CREATE TEMP TABLE " + table + " (id int PRIMARY KEY, c1 text, c2 text, c3 text)");
                    try {
                        sql(conn, "CREATE INDEX ON " + table + " (c1)");
                        sql(conn, "CREATE INDEX ON " + table + " (c2)");
                        sql(conn, "CREATE INDEX ON " + table + " (c3)");
                        try { sql(conn, "INSERT INTO " + table + " VALUES (1, 'demo', 'data', 'block')"); }
                        catch (SQLException e) { failure(e); }
                    } finally {
                        sql(conn, "DROP TABLE " + table);
                        ok.incrementAndGet();
                    }
                } else {
                    sql(conn, "CREATE TEMP TABLE " + table + " (id int, c1 varchar(64), c2 text)");
                    try {
                        try { sql(conn, "INSERT INTO " + table + " VALUES (1, 'demo', repeat('x',100))"); }
                        catch (SQLException e) { failure(e); }
                    } finally {
                        sql(conn, "DROP TABLE " + table);
                        ok.incrementAndGet();
                    }
                }
            }
            try { Thread.sleep(paceMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }

    private void runLocker() {
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(url, suUser, suPassword);
            privConns.add(conn);
            // Session GUCs are transactional in openGauss: set them under autocommit so the rollback
            // that releases each lock does not also undo them.
            if (!enableMaintenanceMode(conn)) {
                return;
            }
            conn.setAutoCommit(false);
            while (running && !Thread.currentThread().isInterrupted()) {
                holdLock(conn);
                if (!sleepQuietly(holdMs) || conn.isClosed()) {
                    break;
                }
                conn.rollback();
                if (!sleepQuietly(gapMs)) {
                    break;
                }
            }
        } catch (SQLException e) {
            failure("lock mode connect/SET for " + catalog, e);
        } finally {
            if (conn != null) {
                try {
                    if (!conn.isClosed()) {
                        conn.rollback();
                        conn.close();
                    }
                } catch (SQLException ignored) {}
                privConns.remove(conn);
            }
        }
    }

    private boolean enableMaintenanceMode(Connection conn) {
        // xc_maintenance_mode is PGC_SUSET (sysadmin-only) and is required before a system catalog
        // can take ACCESS EXCLUSIVE. Fail fast with guidance instead of looping on a permission error.
        try (Statement st = conn.createStatement()) {
            st.execute("SET xc_maintenance_mode = on");
            // openGauss GUC (not PostgreSQL's lock_timeout); PGC_SUSET, unit ms. Bounds how long a
            // locker queues for the catalog lock so contention backs off instead of piling up.
            st.execute("SET lockwait_timeout = 5000");
            return true;
        } catch (SQLException e) {
            System.err.println("   [lock mode] cannot set maintenance-mode GUCs: " + e.getMessage());
            System.err.println("   [lock mode] this mode needs a sysadmin account able to SET " +
                    "xc_maintenance_mode; pass -su_user/-su_password (current lockUser=" + suUser + ")");
            failure("SET xc_maintenance_mode", e);
            return false;
        }
    }

    private void holdLock(Connection conn) {
        try (Statement st = conn.createStatement()) {
            privStmts.add(st);
            try {
                st.execute("LOCK TABLE " + catalog + " IN " + lockMode + " MODE");
                ok.incrementAndGet();
            } finally {
                privStmts.remove(st);
            }
        } catch (SQLException e) {
            // lockwait_timeout expiry (contention on the same catalog) is expected and retried.
            String msg = String.valueOf(e.getMessage());
            if ("55P03".equals(e.getSQLState()) || (msg != null && (msg.contains("lock timeout") ||
                    msg.contains("等待锁超时") || msg.contains("lockwait")))) {
                return;
            }
            failure("LOCK TABLE " + catalog, e);
        }
    }

    private boolean sleepQuietly(int ms) {
        if (ms <= 0) return true;
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    protected void cleanup() throws Exception {
        for (Statement st : privStmts) {
            try { st.cancel(); } catch (SQLException ignored) {}
        }
        List<Thread> snapshot = new ArrayList<>(lockers);
        for (Thread t : snapshot) t.interrupt();
        boolean interrupted = Thread.interrupted();
        for (Thread t : snapshot) {
            while (t.isAlive()) {
                try { t.join(); } catch (InterruptedException e) { interrupted = true; }
            }
        }
        lockers.clear();
        for (Connection c : privConns) {
            try {
                if (!c.isClosed()) {
                    c.rollback();
                    c.close();
                }
            } catch (SQLException ignored) {}
        }
        privConns.clear();
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public void printHelp() {
        System.out.println("用例: temp_catalog_bloat (系统目录膨胀与元数据锁竞争)");
        System.out.println("两种逻辑，通过 -mode 选择:");
        System.out.println("  resource (默认) 临时表高频生灭 -> CPU/IO 与线程池资源争用");
        System.out.println("  lock            特权用户对系统目录持关系锁 -> 真实元数据锁竞争(阻塞目录读者)");
        System.out.println("参数:");
        System.out.println("  -duration <ms>      持续时间(毫秒) [必填]");
        System.out.println("  -mode <resource|lock>  故障逻辑 (默认 resource)");
        System.out.println("  -threads <num>      并发线程数 [配置自适应，显式值受安全上限约束]");
        System.out.println("  -catalog <name>     lock 模式目标目录 (默认 pg_catalog.pg_class)");
        System.out.println("  -lock_mode <mode>   lock 模式锁级别 (默认 ACCESS EXCLUSIVE)");
        System.out.println("  -hold_ms <ms>       lock 模式每次持锁时长 (默认 500)");
        System.out.println("  -gap_ms <ms>        lock 模式两次持锁间隔 (默认 100)");
        System.out.println("  -su_user/-su_password  lock 模式使用的 sysadmin 账户 (默认同配置账户)");
    }
}
