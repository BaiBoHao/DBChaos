package chaos.core;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Server-side requirements that generic JDBC cannot observe must have a real checker. */
public final class PreflightEnvironment {
    private PreflightEnvironment() {}

    private static Properties check(String url, String user, String kind, String fault,
                                    long duration, String... parameters) throws Exception {
        String executable = System.getenv("DBCHAOS_PREFLIGHT_CHECKER");
        if (executable == null || executable.trim().isEmpty()) executable = System.getProperty("dbchaos.preflight.checker");
        PreflightChecks.require(executable != null && !executable.trim().isEmpty(),
                "无法确认 " + kind + " 测试前提：请通过 DBCHAOS_PREFLIGHT_CHECKER 配置服务器/监控检查程序；未验证则退出");
        Path checker = Paths.get(executable);
        PreflightChecks.require(checker.isAbsolute() && Files.isRegularFile(checker) && Files.isExecutable(checker),
                "DBCHAOS_PREFLIGHT_CHECKER 必须是可执行文件的绝对路径");
        java.util.List<String> command = new java.util.ArrayList<String>();
        command.add(checker.toString());
        command.add(kind);
        command.add(fault);
        command.add(Long.toString(duration));
        java.util.Collections.addAll(command, parameters);
        String fingerprint = fingerprint(url, user);
        Path output = Files.createTempFile("dbchaos-preflight-", ".properties");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("DBCHAOS_PREFLIGHT_JDBC_URL", url);
            builder.environment().put("DBCHAOS_PREFLIGHT_DB_USER", user);
            builder.environment().put("DBCHAOS_PREFLIGHT_TARGET", fingerprint);
            process = builder.start();
            PreflightChecks.require(process.waitFor(15, TimeUnit.SECONDS), kind + " 外部检查超时");
            PreflightChecks.require(process.exitValue() == 0, kind + " 外部检查失败，退出码 " + process.exitValue());
            PreflightChecks.require(Files.size(output) <= 65536, kind + " 外部检查输出过大");
            Properties result = new Properties();
            try (InputStream in = Files.newInputStream(output)) { result.load(in); }
            PreflightChecks.require("pass".equals(result.getProperty("result")), kind + " 外部检查没有确认前提满足");
            PreflightChecks.require(fingerprint.equals(result.getProperty("target")), kind + " 外部检查目标与当前连接不一致");
            return result;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }

    public static String fingerprint(String url, String user) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((url + "\n" + user).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static long bytes(Properties result, String key) {
        String raw = result.getProperty(key);
        PreflightChecks.require(raw != null, "外部检查缺少 " + key);
        long value = Long.parseLong(raw);
        PreflightChecks.require(value >= 0, key + " 不能为负数");
        return value;
    }

    public static long seedBytes(long rows, long payloadKb) {
        // Minimum provisioning estimate including a second copy for tuple/index overhead.
        return Math.multiplyExact(rows, Math.multiplyExact(Math.addExact(Math.multiplyExact(payloadKb, 1024), 64), 2));
    }

    public static void storage(String url, String user, String fault, long duration,
                               long seedBytes, String... parameters) throws Exception {
        String[] inputs = new String[parameters.length + 1];
        inputs[0] = Long.toString(seedBytes);
        System.arraycopy(parameters, 0, inputs, 1, parameters.length);
        Properties result = check(url, user, "storage", fault, duration, inputs);
        long dataFree = bytes(result, "data_available_bytes");
        long logFree = bytes(result, "log_available_bytes");
        long dataGrowth = bytes(result, "data_growth_bytes");
        long logGrowth = bytes(result, "log_growth_bytes");
        PreflightChecks.require(dataGrowth > 0 && logGrowth > 0, "必须估算整个注入时长的数据及日志增长，不能按零增长放行");
        long dataNeeded = Math.addExact(seedBytes, dataGrowth);
        String shared = result.getProperty("shared_volume");
        PreflightChecks.require("true".equals(shared) || "false".equals(shared), "外部检查必须确认数据与日志是否共享卷");
        if ("true".equals(shared)) {
            PreflightChecks.require(Math.min(dataFree, logFree) >= Math.addExact(dataNeeded, logGrowth), "数据与日志共享卷空间不足");
        } else {
            PreflightChecks.require(dataFree >= dataNeeded && logFree >= logGrowth, "数据库数据或日志磁盘空间不足");
        }
        System.out.println("[Preflight] 服务器数据及日志容量检查通过（按检查程序提供的全时段增长预算）");
    }

    public static void businessHotspots(String url, String user, String fault, long duration,
                                        String table, long rows) throws Exception {
        Properties result = check(url, user, "business-hotspots", fault, duration, table, Long.toString(rows));
        PreflightChecks.require(table.equals(result.getProperty("table"))
                && "true".equals(result.getProperty("hotspot_access_verified"))
                && bytes(result, "hotspot_rows_checked") >= rows,
                "未验证业务负载访问即将锁定的目标记录，不能仅凭表有访问量放行");
        System.out.println("[Preflight] 业务负载访问目标锁记录检查通过: " + table);
    }

    public static void clientMemory(String url, String user, String fault, long duration, int threads) throws Exception {
        long needed = Math.addExact(Runtime.getRuntime().maxMemory(), (64L + threads) * 1024 * 1024);
        Properties result = check(url, user, "client-memory", fault, duration, Long.toString(needed));
        PreflightChecks.require(bytes(result, "client_memory_budget_bytes") >= needed,
                "执行机物理内存/容器内存预算不足以支持 -Xmx 及线程和驱动开销");
        System.out.println("[Preflight] 执行机物理/容器内存预算检查通过");
    }
}
