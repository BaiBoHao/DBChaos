package chaos.core;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 基础故障注入类。
 */
public abstract class BaseFaultInject {
    protected static final Logger LOG = Logger.getLogger(BaseFaultInject.class.getName());
    
    protected String url;
    protected String user;
    protected String password;
    protected String faultType;
    protected String dbType;
    protected String SQLType;

    // 可覆盖配置
    public static String overrideUrl = null;
    public static String overrideUser = null;
    public static String overridePassword = null;

    
    private String standardizeDbType(String dbType) {
        if (dbType == null) return "unknown";
        
        String lowerType = dbType.toLowerCase();
        switch (lowerType) {
            case "ob":
            case "oceanbase":
            case "mysql":
                return "mysql";
            case "opengauss":
            case "postgresql":
            case "og":
                return "postgresql";
            default:
                return lowerType;
        }
    }

    private void loadConfig() {
        try (InputStream in = BaseFaultInject.class.getResourceAsStream("/db.properties")) {
            if (in == null) {
                throw new RuntimeException("在 classpath 中未找到 /db.properties 文件");
            }
            
            Properties props = new Properties();
            props.load(in);

            // 优先使用覆盖配置；未覆盖时优先读取数据库类型专属配置，再回退到通用配置。
            this.url = chooseConfigValue(props, "url", overrideUrl);
            this.user = chooseConfigValue(props, "user", overrideUser);
            this.password = chooseConfigValue(props, "password", overridePassword);

            if (this.url == null || this.user == null || this.password == null) {
                throw new RuntimeException("db.properties 配置不完整");
            }

            // 适用java1.8及以上版本，自动加载驱动
            String driverClass;
            String lowerDb = dbType.toLowerCase();
            switch (lowerDb) {
                case "opengauss":
                case "og":
                    // Some openGauss JDBC distributions expose the driver as org.postgresql.Driver.
                    driverClass = "org.opengauss.Driver";
                    break;
                case "postgresql":
                    driverClass = "org.postgresql.Driver";
                    break;
                case "mysql":
                case "oceanbase":
                    driverClass = "com.mysql.cj.jdbc.Driver";
                    break;
                default:
                    throw new RuntimeException("不支持的数据库驱动类型: " + dbType);
            }
            
            try {
                Class.forName(driverClass);
            } catch (ClassNotFoundException e) {
                // openGauss JDBC is often packaged under PostgreSQL driver namespace.
                if ("opengauss".equals(lowerDb)) {
                    try {
                        Class.forName("org.postgresql.Driver");
                    } catch (ClassNotFoundException e2) {
                        throw new RuntimeException("找不到驱动类 org.opengauss.Driver 或 org.postgresql.Driver，请检查 JDBC 依赖是否已正确打包。");
                    }
                } else {
                    throw new RuntimeException("找不到驱动类 " + driverClass + "，请检查 JDBC 依赖是否已正确打包。");
                }
            }
            
        } catch (Exception e) {
            throw new RuntimeException("初始化基类失败: " + e.getMessage());
        }
    }

    private String chooseConfigValue(Properties props, String keySuffix, String overrideVal) {
        if (overrideVal != null && !overrideVal.trim().isEmpty()) {
            return overrideVal;
        }

        String[] candidates = getDbConfigPrefixes();
        for (String prefix : candidates) {
            String v = props.getProperty(prefix + "." + keySuffix);
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }

        String fallback = props.getProperty(keySuffix);
        return (fallback == null || fallback.trim().isEmpty()) ? null : fallback;
    }

    private String[] getDbConfigPrefixes() {
        String lower = (dbType == null) ? "" : dbType.toLowerCase();
        switch (lower) {
            case "mysql":
                return new String[] {"mysql"};
            case "oceanbase":
            case "ob":
                return new String[] {"oceanbase", "ob", "mysql"};
            case "opengauss":
            case "og":
                return new String[] {"opengauss", "og", "postgresql"};
            case "postgresql":
                return new String[] {"postgresql"};
            default:
                return new String[0];
        }
    }

    protected Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private final java.util.Map<String, String> settings = new java.util.HashMap<>();

    /** Read only; unavailable settings retain caller defaults. Each call uses a fresh connection. */
    public java.util.Map<String, String> querySettings(String... names) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        if (names == null || names.length == 0) return result;
        StringBuilder sql = new StringBuilder("SELECT name, setting FROM pg_settings WHERE name IN (");
        for (int i = 0; i < names.length; i++) sql.append(i == 0 ? "?" : ",?");
        sql.append(")");
        try (Connection conn = getConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < names.length; i++) stmt.setString(i + 1, names[i]);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) result.put(rs.getString(1), rs.getString(2));
            }
        } catch (Exception e) {
            logAdaptive("Settings unavailable/partial: " + e.getMessage());
        }
        settings.putAll(result);
        return result;
    }

    protected String settingStr(String name, String def) {
        if (!settings.containsKey(name)) querySettings(name);
        String value = settings.get(name);
        return value == null ? def : value;
    }

    protected int settingInt(String name, int def) {
        try { return Integer.parseInt(settingStr(name, "").trim()); }
        catch (RuntimeException e) { return def; }
    }

    protected boolean settingBool(String name, boolean def) {
        String value = settingStr(name, "").trim();
        if ("on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value) || "0".equals(value)) return false;
        return def;
    }

    protected long settingKB(String name, long def) {
        String value = settingStr(name, "").trim().toUpperCase(java.util.Locale.ROOT);
        try {
            long multiplier = 1;
            if (value.endsWith("GB")) { multiplier = 1024 * 1024; value = value.substring(0, value.length() - 2); }
            else if (value.endsWith("MB")) { multiplier = 1024; value = value.substring(0, value.length() - 2); }
            else if (value.endsWith("KB")) value = value.substring(0, value.length() - 2);
            return Math.multiplyExact(Long.parseLong(value.trim()), multiplier);
        } catch (RuntimeException e) { return def; }
    }

    public long memoryUnits() {
        return Math.max(1L, settingKB("max_process_memory", 2097152L) / 2097152L);
    }

    public boolean isEnterprise() {
        return memoryUnits() >= 16 || settingInt("max_connections", 100) >= 150;
    }

    protected int scaleByMemory(int baseThreads, int cap) {
        long units = memoryUnits();
        return (int) Math.max(baseThreads, Math.min(Math.max(baseThreads, cap),
                (long) baseThreads * Math.min(units, Integer.MAX_VALUE)));
    }

    protected void logAdaptive(String msg) {
        System.out.println("   [Adaptive] " + msg);
    }

    protected String getStandardDbType() {
        return this.SQLType;
    }

    protected boolean hasArg(String[] args, String target) {
        for (String arg : args) {
            if (target.equalsIgnoreCase(arg)) return true;
        }
        return false;
    }

    protected String getArg(String[] args, String target) {
        for (int i = 0; i < args.length - 1; i++) {
            if (target.equalsIgnoreCase(args[i])) return args[i + 1];
        }
        return null;
    }

    /** Extension hook: faults without special prerequisites keep the default no-op. */
    protected void preflight(String[] args) throws Exception {}

    /** Must run before any setup/cleanup or pressure threads are started. */
    protected final void runPreflight(String[] args) throws Exception {
        try {
            preflight(args);
            System.out.println("[Preflight] " + faultType + " 预检通过");
            markFaultStarted();
        } catch (Exception e) {
            throw new IllegalStateException("[Preflight] " + faultType
                    + " 预检失败，退出测试: " + e.getMessage(), e);
        }
    }

    protected PreflightProbes preflightProbes() {
        return new PreflightProbes(this::getConnection, getStandardDbType());
    }

    protected String preflightArg(String[] args, String key) {
        String value = getArg(args, key);
        if (hasArg(args, key) && value == null) {
            throw new IllegalArgumentException(key + " 缺少值");
        }
        return value;
    }

    protected long preflightLong(String[] args, String key, long fallback) {
        String raw = preflightArg(args, key);
        long value = raw == null ? fallback : Long.parseLong(raw);
        PreflightChecks.require(value > 0, key + " 必须为正整数");
        return value;
    }

    protected int preflightInt(String[] args, String key, int fallback) {
        long value = preflightLong(args, key, fallback);
        PreflightChecks.require(value <= Integer.MAX_VALUE, key + " 超出整数范围");
        return (int) value;
    }

    public void printHelp() {}

    public BaseFaultInject(String dbType, String faultType) {
        this.dbType = dbType;
        this.faultType = faultType;
        this.SQLType = standardizeDbType(dbType);
        this.loadConfig();
    }

    public abstract void execute(String[] args) throws Exception;

    /** 输出本次测试报告；子类可在通用报告之外补充自己的效果指标。 */
    public abstract void reportTest();

    /** 保留无有效注入等已有非零退出语义，同时确保退出前生成报告。 */
    public static final class TestExecutionException extends IllegalStateException {
        private final int exitCode;
        public TestExecutionException(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }
        public int getExitCode() { return exitCode; }
    }

    private long reportStarted;
    private chaos.monitor.ResilienceMonitor resilienceMonitor;

    public final void observeBusiness(chaos.monitor.ResilienceMonitor monitor) {
        this.resilienceMonitor = monitor;
        monitor.observeDatabase(this::getConnection);
    }

    /** 正式注入（含准备负载）开始；参数检查及 SQL 能力预检须先完成。 */
    protected final void markFaultStarted() {
        if (resilienceMonitor != null) resilienceMonitor.faultStarted();
    }
    private volatile boolean executionFailed;
    private final java.util.concurrent.atomic.AtomicLong observedErrors = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.Set<String> errorSamples = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> checkHints = new java.util.LinkedHashSet<>();

    /** CLI 的统一入口：异常继续交给调用者处理，报告在退出前输出。 */
    public final void executeWithReport(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            execute(args);
            return;
        }
        reportStarted = System.nanoTime();
        executionFailed = false;
        observedErrors.set(0);
        synchronized (this) { errorSamples.clear(); checkHints.clear(); }
        try {
            execute(args);
        } catch (Exception | OutOfMemoryError e) {
            executionFailed = true;
            recordTestError(e);
            throw e;
        } finally {
            if (resilienceMonitor != null) resilienceMonitor.finish(executionFailed);
            reportTest();
        }
    }

    /** 供压力线程和清理逻辑记录已捕获的异常；只保留少量样本。 */
    protected final synchronized void recordTestError(Throwable error) {
        observedErrors.incrementAndGet();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String state = cause instanceof SQLException ? ((SQLException) cause).getSQLState() : null;
            String message = String.valueOf(cause.getMessage());
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            if (errorSamples.size() < 5) {
                // 不在报告中泄露连接 URL 或密码，也不输出完整 SQL。
                if (password != null && !password.isEmpty()) message = message.replace(password, "[REDACTED]");
                if (url != null && !url.isEmpty()) message = message.replace(url, "[JDBC_URL]");
                message = message.replace('\n', ' ').replace('\r', ' ');
                errorSamples.add(cause.getClass().getSimpleName() + (state == null ? "" : " SQLState=" + state)
                        + ": " + message.substring(0, Math.min(400, message.length())));
            }
            if ("53100".equals(state) || lower.contains("no space left") || lower.contains("disk full"))
                checkHints.add("请检查数据库数据盘、WAL/日志盘剩余空间及存储配额。");
            if (cause instanceof OutOfMemoryError || "53200".equals(state) || lower.contains("out of memory") || lower.contains("堆不足"))
                checkHints.add("请检查数据库与执行机内存、容器内存限制及 JVM -Xmx，核对载荷和并发参数。");
            if ("42501".equals(state) || "28000".equals(state) || "28P01".equals(state) || lower.contains("permission denied") || lower.contains("access denied"))
                checkHints.add("请检查数据库连接账号、认证配置及建表、读写、事务和监控视图权限。");
            if ("53300".equals(state) || lower.contains("too many connections") || lower.contains("连接余量"))
                checkHints.add("请检查 max_connections、账号连接限额、现有连接数及测试并发。");
            if (state != null && state.startsWith("08"))
                checkHints.add("请检查数据库服务状态、网络连通性、连接配置及服务器日志。");
            if (lower.contains("max_allowed_packet") || lower.contains("packet too large"))
                checkHints.add("请检查 max_allowed_packet、单次载荷大小及驱动请求大小限制。");
            if ("42P01".equals(state) || "3F000".equals(state) || "42703".equals(state))
                checkHints.add("请检查目标表、列及 schema 是否存在，确认连接数据库和 search_path 配置。");
            if ("42704".equals(state) || "0A000".equals(state) || lower.contains("max_prepared_transactions") || lower.contains("autovacuum"))
                checkHints.add("请检查目标数据库版本、SQL 功能支持及该故障所需的数据库参数。");
        }
    }

    /** 通用报告不把 SQL 错误直接解释为环境配置问题或韧性结论。 */
    protected final synchronized void printTestReport() {
        long elapsed = reportStarted == 0 ? 0 : (System.nanoTime() - reportStarted) / 1000000;
        System.out.println("\n[TestReport] 故障=" + faultType + " 数据库=" + dbType);
        System.out.println("[TestReport] 执行状态=" + (executionFailed ? "执行失败" : observedErrors.get() > 0 ? "执行结束，存在错误记录" : "执行结束")
                + " 耗时=" + elapsed + "ms 异常记录数=" + observedErrors.get());
        for (String sample : errorSamples) System.out.println("[TestReport] 异常样本: " + sample);
        for (String hint : checkHints) System.out.println("[TestReport] 检查建议（需结合日志确认原因）: " + hint);
        if (executionFailed && checkHints.isEmpty())
            System.out.println("[TestReport] 请根据异常检查故障参数、数据库配置、权限、目标表及服务器日志。");
        System.out.println("[TestReport] 执行结束不代表故障生效；请结合注入计数、业务影响和恢复情况评价韧性。");
        if (resilienceMonitor != null) resilienceMonitor.report();
        else System.out.println("[Resilience] 未提供业务结果源，六项业务韧性指标不可用；通过 --wait-stable <结果目录> 启用全程观测。");
    }
}
