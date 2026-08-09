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
import chaos.registry.CaseDescriptor;
import chaos.registry.CaseRegistry;
import chaos.registry.SubsystemDescriptor;

/**
 * DBChaos 项目启动主类。
 * 负责统一解析命令入口、数据库类型、内核子系统与具体不利 Case。
 */
public class Main {
    private static final Properties appProps = new Properties();
    private static final Properties dbProps = new Properties();
    private static final CaseRegistry REGISTRY = CaseRegistry.getInstance();

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

        if (REGISTRY.isKnownCaseKeyword(command.subsystem)) {
            printMissingSubsystemHint(command.subsystem);
            return;
        }

        if (!REGISTRY.isKnownSubsystem(command.subsystem)) {
            printUnknownSubsystem(command.subsystem);
            return;
        }

        if (command.caseKey == null) {
            printSubsystemHelp(command.dbType, command.subsystem, !command.helpRequested);
            return;
        }

        if (REGISTRY.findCaseDescriptor(command.subsystem, command.caseKey) == null) {
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

        BaseFaultInject injector = REGISTRY.createInjector(command.dbType, command.subsystem, command.caseKey);
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
        for (CaseDescriptor descriptor : REGISTRY.getExampleCases(6)) {
            System.out.println(CYAN + "  " + buildExampleCommand(jarName, "opengauss", descriptor) + RESET);
        }
        System.out.println(DIM + "\n帮助：" + RESET);
        System.out.println(DIM + "  java -jar " + jarName + " sql --help" + RESET);
        System.out.println(DIM + "  java -jar " + jarName + " txn duplicate_txn --help" + RESET);
        System.out.println();
    }

    private static void printSubsystemCatalog(boolean includeCases) {
        for (SubsystemDescriptor subsystem : REGISTRY.getSubsystems()) {
            System.out.println("  " + CYAN + subsystem.getKey() + RESET + "  " + BOLD + subsystem.getTitle() + RESET);
            if (includeCases) {
                printCasesForSubsystem(subsystem.getKey(), "    ");
            }
        }
    }

    private static void printCasesForSubsystem(String subsystem, String indent) {
        for (CaseDescriptor descriptor : REGISTRY.getCasesForSubsystem(subsystem)) {
            System.out.println(indent + YELLOW + descriptor.getCaseKey() + RESET + "  " + descriptor.getTitle());
        }
    }

    private static void printSubsystemHelp(String dbType, String subsystem, boolean missingCase) {
        String jarName = buildJarName();
        String title = REGISTRY.getSubsystemTitle(subsystem);

        if (missingCase) {
            System.out.println(RED + " 缺少 Case，请先选择子系统下的具体入口。" + RESET);
        }

        System.out.println("\n" + BOLD + "内核子系统" + RESET);
        System.out.println("  " + CYAN + subsystem + RESET + "  " + BOLD + title + RESET);
        System.out.println("\n" + BOLD + "可用 Case" + RESET);
        printCasesForSubsystem(subsystem, "  ");

        System.out.println("\n" + BOLD + "示例" + RESET);
        for (CaseDescriptor descriptor : REGISTRY.getCasesForSubsystem(subsystem)) {
            if (descriptor.getExampleArgs() != null && !descriptor.getExampleArgs().trim().isEmpty()) {
                System.out.println(CYAN + "  " + buildExampleCommand(jarName, dbType, descriptor) + RESET);
            }
        }
        System.out.println();
    }

    private static void printCaseHelp(CommandContext command) {
        String jarName = buildJarName();
        CaseDescriptor descriptor = REGISTRY.findCaseDescriptor(command.subsystem, command.caseKey);

        System.out.println("\n" + BOLD + "Case 上下文" + RESET);
        System.out.printf("  %-14s %s\n", "数据库类型", command.dbType);
        System.out.printf("  %-14s %s (%s)\n", "内核子系统", REGISTRY.getSubsystemTitle(command.subsystem), command.subsystem);
        System.out.printf("  %-14s %s\n", "不利类型", descriptor == null ? command.caseKey : descriptor.getCaseKey());
        if (descriptor != null) {
            System.out.println("  " + DIM + descriptor.getTitle() + RESET);
            System.out.println("  " + DIM + descriptor.getDescription() + RESET);
            if (descriptor.hasModeConstraint()) {
                System.out.println("  " + DIM + "允许的 mode: " + joinModes(descriptor.getAllowedModes()) + RESET);
            }
        }

        System.out.println("\n" + BOLD + "调用形式" + RESET);
        System.out.println(CYAN + "  java -jar " + jarName + " [--db <DB_TYPE>] " + command.subsystem + " " + command.caseKey + " [OPTIONS]" + RESET);
        if (descriptor != null && descriptor.getExampleArgs() != null && !descriptor.getExampleArgs().trim().isEmpty()) {
            System.out.println(CYAN + "  " + buildExampleCommand(jarName, command.dbType, descriptor) + RESET);
        }
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
        System.out.println(DIM + " 当前子系统: " + REGISTRY.getSubsystemTitle(subsystem) + " (" + subsystem + ")" + RESET);

        List<String> owners = REGISTRY.findSubsystemsForCase(caseKey);
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
        CaseDescriptor descriptor = REGISTRY.findCaseDescriptor(subsystem, caseKey);
        if (descriptor == null || !descriptor.hasModeConstraint()) {
            return caseArgs;
        }

        String mode = findOptionValue(caseArgs, "-mode");
        if (mode == null) {
            mode = findOptionValue(caseArgs, "-type");
        }

        if (mode == null && descriptor.hasDefaultMode()) {
            return appendArgs(caseArgs, "-mode", descriptor.getDefaultMode());
        }
        if (mode != null && !containsIgnoreCase(descriptor.getAllowedModes(), mode)) {
            throw new IllegalArgumentException(subsystem + " 子系统下 " + caseKey + " 仅支持 mode: " + joinModes(descriptor.getAllowedModes()));
        }

        return caseArgs;
    }

    private static String joinSubsystemTitles(List<String> subsystems) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < subsystems.size(); i++) {
            if (i > 0) {
                builder.append(" | ");
            }
            String key = subsystems.get(i);
            builder.append(REGISTRY.getSubsystemTitle(key)).append(" (").append(key).append(")");
        }
        return builder.toString();
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

    private static String buildExampleCommand(String jarName, String dbType, CaseDescriptor descriptor) {
        StringBuilder builder = new StringBuilder();
        builder.append("java -jar ").append(jarName).append(" --db ").append(dbType).append(" ")
                .append(descriptor.getSubsystem()).append(" ").append(descriptor.getCaseKey());
        if (descriptor.getExampleArgs() != null && !descriptor.getExampleArgs().trim().isEmpty()) {
            builder.append(" ").append(descriptor.getExampleArgs().trim());
        }
        return builder.toString();
    }

    private static boolean containsIgnoreCase(List<String> values, String target) {
        for (String value : values) {
            if (value.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private static String joinModes(List<String> modes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < modes.size(); i++) {
            if (i > 0) {
                builder.append(" | ");
            }
            builder.append(modes.get(i));
        }
        return builder.toString();
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static final class CommandContext {
        private String dbType;
        private String subsystem;
        private String caseKey;
        private String[] caseArgs = new String[0];
        private boolean helpRequested;
    }
}
