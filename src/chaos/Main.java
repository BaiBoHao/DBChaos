package chaos;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import chaos.core.BaseFaultInject;

/**
 * DBChaos 项目启动主类。
 * 负责统一解析命令入口、数据库类型、内核子系统与具体不利 Case。
 */
public class Main {
    private static final Properties appProps = new Properties();
    private static final Properties dbProps = new Properties();

    // 颜色常量
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";
    private static final String DIM    = "\u001B[2m";

    private static final Set<String> SUPPORTED_DBS = new HashSet<>(Arrays.asList(
        "opengauss", "og", "postgresql", "pg", "mysql", "oceanbase", "ob"
    ));

    private static final Set<String> FAULT_KEYWORDS = new HashSet<>(Arrays.asList(
        "plan_flip", "max_connection", "stack_overflow", "massive_rollback",
        "memory", "memory_pressure", "max_prepared", "uncommitted_txn", "duplicate_txn"
    ));

    private static final Set<String> SUBSYSTEM_KEYWORDS = new HashSet<>(Arrays.asList(
        "session", "sql", "exec", "txn", "storage", "log", "task", "quota"
    ));

    private static final Set<String> STACK_SQL_MODES = new HashSet<>(Arrays.asList(
        "sql_depth", "view_nest", "join_bomb"
    ));

    private static final Set<String> STACK_EXEC_MODES = new HashSet<>(Arrays.asList(
        "func_recurse", "proc_recurse", "trans_recurse", "proc_recurse"
    ));

    private static final String[][] SUBSYSTEM_CATALOG = {
        {"session", "连接与会话管理"},
        {"sql", "SQL 编译与优化"},
        {"exec", "执行引擎与运行时"},
        {"txn", "事务与并发控制"},
        {"storage", "存储引擎与缓冲管理"},
        {"log", "日志、检查点与崩溃恢复"},
        {"task", "后台维护与系统任务"},
        {"quota", "资源治理与系统配额"}
    };

    private static final String[][] CASE_CATALOG = {
        {"session", "max_connection", "连接风暴、连接耗尽、线程池饱和"},
        {"sql", "plan_flip", "执行计划翻转"},
        {"sql", "stack_overflow", "深层表达式、视图展开、Join 搜索压力"},
        {"exec", "stack_overflow", "函数、过程与事务路径递归执行压力"},
        {"txn", "uncommitted_txn", "长事务持锁"},
        {"txn", "duplicate_txn", "热点更新与唯一性冲突"},
        {"txn", "max_prepared", "Prepared/XA 事务积压"},
        {"storage", "memory_pressure", "大对象写入与缓冲挤压"},
        {"log", "massive_rollback", "高频事务回滚风暴"}
    };

    static {
        try (InputStream in = Main.class.getResourceAsStream("/chaos.properties")) {
            if (in != null) {
                appProps.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}

        try (InputStream in = Main.class.getResourceAsStream("/db.properties")) {
            if (in != null) {
                dbProps.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    public static void main(String[] args) {
        String[] loggers = {
            "org.opengauss",
            "org.postgresql",
            "com.mysql.cj",
            "com.mysql"
        };

        for (String loggerName : loggers) {
            Logger logger = Logger.getLogger(loggerName);
            logger.setLevel(Level.WARNING);
        }

        if (args.length == 0 || (args.length == 1 && isHelpToken(args[0]))) {
            showFullHelp();
            return;
        }

        resetGlobalOverrides();

        CommandContext command;
        try {
            command = parseCommandContext(args);
        } catch (IllegalArgumentException e) {
            System.out.println(RED + BOLD + " 参数错误: " + RESET + e.getMessage());
            return;
        }

        if (!SUPPORTED_DBS.contains(command.dbType.toLowerCase())) {
            printUnsupportedDb(command.dbType);
            return;
        }

        if (command.subsystem == null) {
            showFullHelp();
            return;
        }

        if (SUPPORTED_DBS.contains(command.subsystem)) {
            printLegacyDbSyntaxHint(command.subsystem);
            return;
        }

        if (FAULT_KEYWORDS.contains(command.subsystem)) {
            printMissingSubsystemHint(command.subsystem);
            return;
        }

        if (!isKnownSubsystem(command.subsystem)) {
            printUnknownSubsystem(command.subsystem);
            return;
        }

        if (command.caseKey == null) {
            printSubsystemHelp(command.dbType, command.subsystem, !command.helpRequested);
            return;
        }

        if (!isCaseAllowedInSubsystem(command.subsystem, command.caseKey)) {
            printCaseMismatch(command.subsystem, command.caseKey);
            return;
        }

        try {
            command.caseArgs = normalizeCaseArgs(command.subsystem, command.caseKey, command.caseArgs);
        } catch (IllegalArgumentException e) {
            System.err.println(RED + BOLD + " 参数错误: " + RESET + e.getMessage());
            return;
        }

        parseGlobalOverrides(args);

        BaseFaultInject injector = createInjector(command.dbType, command.caseKey);
        if (injector == null) {
            System.out.println(RED + BOLD + " 未知的注入入口: " + command.caseKey + RESET);
            printSubsystemHelp(command.dbType, command.subsystem, false);
            return;
        }

        if (command.helpRequested) {
            printCaseHelp(command);
            injector.printHelp();
            return;
        }

        try {
            injector.execute(command.caseArgs);
        } catch (Exception e) {
            System.out.println("\n" + RED + BOLD + " 执行异常: " + RESET + e.getMessage());
        }
    }

    private static void showFullHelp() {
        printWelcomeScreen();
        printTopLevelUsage();
    }

    private static void printWelcomeScreen() {
        String banner = appProps.getProperty("cli.banner", "DBChaos");
        String version = appProps.getProperty("cli.version", "1.0.0");
        String author = appProps.getProperty("cli.author", "西北工业大学");
        String features = appProps.getProperty("cli.features", "");

        System.out.println(CYAN + BOLD + banner + RESET);
        System.out.println(BOLD + " " + appProps.getProperty("cli.description") + RESET);
        if (!features.trim().isEmpty()) {
            System.out.println(DIM + " " + features + RESET);
        }
        System.out.println(
            DIM + " Version " + RESET + GREEN + version + RESET +
            DIM + " | License " + RESET + "Apache 2.0" +
            DIM + " | Author " + RESET + YELLOW + author + RESET
        );
        System.out.println(CYAN + " " + repeat("=", 96) + RESET);
    }

    private static void printTopLevelUsage() {
        String jarName = buildJarName();
        System.out.println("\n" + BOLD + "用法" + RESET);
        System.out.println(YELLOW + "  java -jar " + jarName + " [--db <DB_TYPE>] <SUBSYSTEM> <CASE> [OPTIONS]" + RESET);
        System.out.println(DIM + "  如未显式传入 --db，则默认读取 resources/db.properties 中的 type。" + RESET);
        System.out.println(DIM + "  支持数据库: opengauss | postgresql | mysql" + RESET);

        System.out.println("\n" + BOLD + "内核子系统" + RESET);
        printSubsystemCatalog(true);

        System.out.println("\n" + BOLD + "通用选项" + RESET);
        System.out.printf("  %-20s %s\n", "--db <db_type>", "覆盖数据库类型");
        System.out.printf("  %-20s %s\n", "-url <jdbc_url>", "覆盖数据库连接地址");
        System.out.printf("  %-20s %s\n", "-user <username>", "覆盖数据库用户名");
        System.out.printf("  %-20s %s\n", "-password <pwd>", "覆盖数据库密码");

        System.out.println("\n" + BOLD + "示例" + RESET);
        System.out.println(CYAN + "  java -jar " + jarName + " sql plan_flip -duration 300000 -threads 16 -interval 60000" + RESET);
        System.out.println(CYAN + "  java -jar " + jarName + " --db opengauss session max_connection -mode conn_storm -duration 60000" + RESET);
        System.out.println(CYAN + "  java -jar " + jarName + " txn uncommitted_txn -duration 60000 -table bmsql_stock -holders 2 -rows 500" + RESET);
        System.out.println(DIM + "\n帮助：" + RESET);
        System.out.println(DIM + "  java -jar " + jarName + " sql --help" + RESET);
        System.out.println(DIM + "  java -jar " + jarName + " txn duplicate_txn --help" + RESET);
        System.out.println();
    }

    private static void printSubsystemCatalog(boolean includeCases) {
        for (String[] subsystem : SUBSYSTEM_CATALOG) {
            System.out.println("  " + CYAN + subsystem[0] + RESET + "  " + BOLD + subsystem[1] + RESET);
            if (includeCases) {
                printCasesForSubsystem(subsystem[0], "    ");
            }
        }
    }

    private static void printCasesForSubsystem(String subsystem, String indent) {
        for (String[] c : CASE_CATALOG) {
            if (subsystem.equals(c[0])) {
                System.out.println(indent + YELLOW + c[1] + RESET + "  " + c[2]);
            }
        }
    }

    private static void printSubsystemHelp(String dbType, String subsystem, boolean missingCase) {
        String jarName = buildJarName();
        String title = getSubsystemTitle(subsystem);

        if (missingCase) {
            System.out.println(RED + " 缺少 Case，请先选择子系统下的具体入口。" + RESET);
        }

        System.out.println("\n" + BOLD + "内核子系统" + RESET);
        System.out.println("  " + CYAN + subsystem + RESET + "  " + BOLD + title + RESET);
        System.out.println("\n" + BOLD + "可用 Case" + RESET);
        printCasesForSubsystem(subsystem, "  ");

        if ("sql".equals(subsystem)) {
            System.out.println("\n" + BOLD + "说明" + RESET);
            System.out.println("  " + DIM + "stack_overflow 在 sql 子系统下默认使用 sql_depth，可选 mode: sql_depth | view_nest | join_bomb" + RESET);
        } else if ("exec".equals(subsystem)) {
            System.out.println("\n" + BOLD + "说明" + RESET);
            System.out.println("  " + DIM + "stack_overflow 在 exec 子系统下默认使用 func_recurse，可选 mode: func_recurse | proc_recurse | trans_recurse" + RESET);
        }

        System.out.println("\n" + BOLD + "示例" + RESET);
        if ("session".equals(subsystem)) {
            System.out.println(CYAN + "  java -jar " + jarName + " --db " + dbType + " session max_connection -mode conn_storm -duration 60000" + RESET);
        } else if ("sql".equals(subsystem)) {
            System.out.println(CYAN + "  java -jar " + jarName + " --db " + dbType + " sql plan_flip -duration 300000 -threads 16 -interval 60000" + RESET);
        } else if ("exec".equals(subsystem)) {
            System.out.println(CYAN + "  java -jar " + jarName + " --db " + dbType + " exec stack_overflow -mode func_recurse -duration 60000 -interval 1000" + RESET);
        } else if ("txn".equals(subsystem)) {
            System.out.println(CYAN + "  java -jar " + jarName + " --db " + dbType + " txn uncommitted_txn -duration 60000 -table bmsql_stock -holders 2 -rows 500" + RESET);
        } else if ("storage".equals(subsystem)) {
            System.out.println(CYAN + "  java -jar " + jarName + " --db " + dbType + " storage memory_pressure -duration 60000 -batch 50 -threads 4" + RESET);
        } else if ("log".equals(subsystem)) {
            System.out.println(CYAN + "  java -jar " + jarName + " --db " + dbType + " log massive_rollback -duration 60000 -threads 16 -rate 0.7" + RESET);
        }
        System.out.println();
    }

    private static void printCaseHelp(CommandContext command) {
        String jarName = buildJarName();

        System.out.println("\n" + BOLD + "Case 上下文" + RESET);
        System.out.printf("  %-14s %s\n", "数据库类型", command.dbType);
        System.out.printf("  %-14s %s\n", "内核子系统", command.subsystem);
        System.out.printf("  %-14s  %s\n", "不利类型", command.caseKey);
        System.out.println("  " + DIM + findCaseDescription(command.subsystem, command.caseKey) + RESET);

        if ("stack_overflow".equals(command.caseKey)) {
            if ("sql".equals(command.subsystem)) {
                System.out.println("  " + DIM + "当前子系统允许的 mode: sql_depth | view_nest | join_bomb" + RESET);
            } else if ("exec".equals(command.subsystem)) {
                System.out.println("  " + DIM + "当前子系统允许的 mode: func_recurse | proc_recurse | trans_recurse" + RESET);
            }
        }

        System.out.println("\n" + BOLD + "调用形式" + RESET);
        System.out.println(CYAN + "  java -jar " + jarName + " [--db <DB_TYPE>] " + command.subsystem + " " + command.caseKey + " [OPTIONS]" + RESET);
        System.out.println();
    }

    private static void printUnsupportedDb(String dbType) {
        System.out.println(RED + BOLD + " 不支持的数据库类型: " + dbType + RESET);
        System.out.println(DIM + " 支持的数据库类型: " + RESET + CYAN + "opengauss | postgresql | mysql" + RESET);
    }

    private static void printLegacyDbSyntaxHint(String dbTypeToken) {
        String jarName = buildJarName();
        System.out.println(RED + BOLD + " 命令结构已调整" + RESET);
        System.out.println(DIM + " 数据库类型不再占用第一个位置，请使用 --db 作为可选项，或直接使用 db.properties 中的默认 type。" + RESET);
        System.out.println(YELLOW + " 示例: java -jar " + jarName + " --db " + dbTypeToken + " sql plan_flip ..." + RESET);
    }

    private static void printMissingSubsystemHint(String caseKey) {
        String jarName = buildJarName();
        System.out.println(RED + BOLD + " 缺少内核子系统" + RESET);
        System.out.println(DIM + " 当前版本要求先选择 SUBSYSTEM，再进入具体 CASE。" + RESET);
        System.out.println(YELLOW + " 示例: java -jar " + jarName + " sql " + caseKey + " ..." + RESET);
    }

    private static void printUnknownSubsystem(String subsystem) {
        System.out.println(RED + BOLD + " 未知的内核子系统: " + subsystem + RESET);
        System.out.println(DIM + " 请从下列子系统中选择：" + RESET);
        printSubsystemCatalog(false);
    }

    private static void printCaseMismatch(String subsystem, String caseKey) {
        System.out.println(RED + BOLD + " Case 与子系统不匹配: " + caseKey + RESET);
        System.out.println(DIM + " 当前子系统: " + getSubsystemTitle(subsystem) + " (" + subsystem + ")" + RESET);

        List<String> owners = findSubsystemsForCase(caseKey);
        if (!owners.isEmpty()) {
            System.out.println(DIM + " 该 Case 可归属于: " + joinSubsystemTitles(owners) + RESET);
        } else {
            System.out.println(DIM + " 当前未找到该 Case 的归属定义。" + RESET);
        }
        printSubsystemHelp(resolveDefaultDbType(), subsystem, false);
    }

    private static CommandContext parseCommandContext(String[] args) {
        CommandContext command = new CommandContext();
        command.dbType = resolveDefaultDbType();

        List<String> positional = new ArrayList<String>();
        List<String> caseArgs = new ArrayList<String>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if ("--db".equalsIgnoreCase(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--db 缺少数据库类型值");
                }
                command.dbType = args[++i].toLowerCase();
                continue;
            }

            if ("-url".equalsIgnoreCase(arg) || "-user".equalsIgnoreCase(arg) || "-password".equalsIgnoreCase(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(arg + " 缺少参数值");
                }
                caseArgs.add(arg);
                caseArgs.add(args[++i]);
                continue;
            }

            if (isHelpToken(arg)) {
                command.helpRequested = true;
                continue;
            }

            if (positional.size() < 2) {
                positional.add(arg.toLowerCase());
            } else {
                caseArgs.add(arg);
            }
        }

        if (!positional.isEmpty()) {
            command.subsystem = positional.get(0);
        }
        if (positional.size() > 1) {
            command.caseKey = positional.get(1);
        }

        command.caseArgs = caseArgs.toArray(new String[caseArgs.size()]);
        return command;
    }

    private static String[] normalizeCaseArgs(String subsystem, String caseKey, String[] caseArgs) {
        if (!"stack_overflow".equals(caseKey)) {
            return caseArgs;
        }

        String mode = findOptionValue(caseArgs, "-mode");
        if (mode == null) {
            mode = findOptionValue(caseArgs, "-type");
        }

        if ("sql".equals(subsystem)) {
            if (mode == null) {
                return appendArgs(caseArgs, "-mode", "sql_depth");
            }
            if (!STACK_SQL_MODES.contains(mode.toLowerCase())) {
                throw new IllegalArgumentException("sql 子系统下 stack_overflow 仅支持 mode: sql_depth | view_nest | join_bomb");
            }
        }

        if ("exec".equals(subsystem)) {
            if (mode == null) {
                return appendArgs(caseArgs, "-mode", "func_recurse");
            }
            if (!STACK_EXEC_MODES.contains(mode.toLowerCase())) {
                throw new IllegalArgumentException("exec 子系统下 stack_overflow 仅支持 mode: func_recurse | proc_recurse | trans_recurse");
            }
        }

        return caseArgs;
    }

    private static boolean isKnownSubsystem(String subsystem) {
        return SUBSYSTEM_KEYWORDS.contains(subsystem.toLowerCase());
    }

    private static boolean isCaseAllowedInSubsystem(String subsystem, String caseKey) {
        for (String[] c : CASE_CATALOG) {
            if (subsystem.equals(c[0]) && caseKey.equals(c[1])) {
                return true;
            }
        }
        return false;
    }

    private static String getSubsystemTitle(String subsystem) {
        for (String[] s : SUBSYSTEM_CATALOG) {
            if (subsystem.equals(s[0])) {
                return s[1];
            }
        }
        return subsystem;
    }

    private static boolean subsystemHasCases(String subsystem) {
        for (String[] c : CASE_CATALOG) {
            if (subsystem.equals(c[0])) {
                return true;
            }
        }
        return false;
    }

    private static List<String> findSubsystemsForCase(String caseKey) {
        List<String> owners = new ArrayList<String>();
        for (String[] c : CASE_CATALOG) {
            if (caseKey.equals(c[1]) && !owners.contains(c[0])) {
                owners.add(c[0]);
            }
        }
        return owners;
    }

    private static String joinSubsystemTitles(List<String> subsystems) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < subsystems.size(); i++) {
            if (i > 0) {
                builder.append(" | ");
            }
            String key = subsystems.get(i);
            builder.append(getSubsystemTitle(key)).append(" (").append(key).append(")");
        }
        return builder.toString();
    }

    private static String findCaseDescription(String subsystem, String caseKey) {
        for (String[] c : CASE_CATALOG) {
            if (subsystem.equals(c[0]) && caseKey.equals(c[1])) {
                return c[2];
            }
        }
        return "";
    }

    private static String buildJarName() {
        String name = appProps.getProperty("cli.name", "DBChaos");
        String version = appProps.getProperty("cli.version", "1.0.0");
        return name + "-" + version + ".jar";
    }

    private static boolean isHelpToken(String arg) {
        return "-h".equals(arg) || "--help".equals(arg);
    }

    private static String resolveDefaultDbType() {
        String configured = dbProps.getProperty("type");
        if (configured == null || configured.trim().isEmpty()) {
            return "opengauss";
        }
        return configured.trim().toLowerCase();
    }

    private static void resetGlobalOverrides() {
        BaseFaultInject.overrideUrl = null;
        BaseFaultInject.overrideUser = null;
        BaseFaultInject.overridePassword = null;
    }

    private static void parseGlobalOverrides(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("-url".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                BaseFaultInject.overrideUrl = args[++i];
            } else if ("-user".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                BaseFaultInject.overrideUser = args[++i];
            } else if ("-password".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                BaseFaultInject.overridePassword = args[++i];
            }
        }
    }

    private static String findOptionValue(String[] args, String target) {
        for (int i = 0; i < args.length - 1; i++) {
            if (target.equalsIgnoreCase(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static String[] appendArgs(String[] args, String... extra) {
        String[] merged = new String[args.length + extra.length];
        System.arraycopy(args, 0, merged, 0, args.length);
        System.arraycopy(extra, 0, merged, args.length, extra.length);
        return merged;
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static BaseFaultInject createInjector(String dbType, String faultType) {
        if (faultType == null) return null;
        switch (faultType.toLowerCase()) {
            case "max_connection": return new chaos.inject.MaxConnectionInject(dbType);
            case "massive_rollback": return new chaos.inject.MassiveRollbackInject(dbType);
            case "plan_flip": return new chaos.inject.PlanFlipInject(dbType);
            case "stack_overflow": return new chaos.inject.StackOverflowInject(dbType);
            case "memory":
            case "memory_pressure": return new chaos.inject.MemoryPressureFault(dbType);
            case "max_prepared": return new chaos.inject.MaxPreparedInject(dbType);
            case "uncommitted_txn": return new chaos.inject.UncommittedTxnInject(dbType);
            case "duplicate_txn": return new chaos.inject.DuplicateTxnInject(dbType);
            case "base": return new BaseFaultInject(dbType, "BASE") {
                @Override public void execute(String[] args) { this.printHelp(); }
            };
            default: return null;
        }
    }

    private static final class CommandContext {
        private String dbType;
        private String subsystem;
        private String caseKey;
        private String[] caseArgs = new String[0];
        private boolean helpRequested;
    }
}
