package chaos;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import chaos.core.BaseFaultInject;

/**
 * DBChaos 项目启动主类。
 * 优化了执行流：支持静默执行、参数校验及更美观的 UI 面板。
 */
public class Main {
    private static Properties appProps = new Properties();

    // 颜色常量
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";
    private static final String DIM    = "\u001B[2m";

    static {
        try (InputStream in = Main.class.getResourceAsStream("/chaos.properties")) {
            if (in != null) {
                appProps.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    public static void main(String[] args) {
        String[] loggers = {
            "org.opengauss",    // openGauss 驱动
            "org.postgresql",   // PostgreSQL 驱动
            "com.mysql.cj",     // MySQL 现代驱动 (Connector/J 8.0+)
            "com.mysql"         // 兼容旧版或通用 MySQL 驱动
        };

        for (String loggerName : loggers) {
            Logger logger = Logger.getLogger(loggerName);
            // 设置为 WARNING，只看报错，不看握手信息
            logger.setLevel(Level.WARNING);
        }

        
        // 1. 帮助触发检查：无参数或包含 -h/--help
        if (args.length == 0 || isHelpRequested(args)) {
            showFullHelp();
            return;
        }

        // 2. 基础参数解析
        if (args.length == 1) {
            System.out.println(YELLOW + BOLD + " ➤ 已选数据库: " + RESET + args[0]);
            System.out.println(RED + " ✘ 缺失参数: 请指定 [故障画像关键字]" + RESET);
            System.out.println(DIM + "\n 可用画像列表：" + RESET);
            printFaultTable();
            return;
        }

        String dbType = args[0];
        String faultType = args[1];

        // 3. 全局覆盖参数处理（不干扰故障指令执行）
        parseGlobalOverrides(args);

        // 4. 路由分发
        BaseFaultInject injector = createInjector(dbType, faultType);

        if (injector == null) {
            System.err.println(RED + BOLD + " ✘ 错误: 未知的故障画像 [" + faultType + "]" + RESET);
            printFaultTable();
            return;
        }

        // 5. 执行注入
        try {
            String[] subArgs = new String[args.length - 2];
            System.arraycopy(args, 2, subArgs, 0, args.length - 2);
            injector.execute(subArgs);
        } catch (Exception e) {
            System.err.println("\n" + RED + BOLD + " ✘ 执行异常: " + RESET + e.getMessage());
        }
    }

    private static boolean isHelpRequested(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> arg.equals("-h") || arg.equals("--help"));
    }

    private static void showFullHelp() {
        printWelcomeScreen();
        printUsage();
    }

    private static void printWelcomeScreen() {
        String banner = appProps.getProperty("cli.banner", "DBChaos");
        String version = appProps.getProperty("cli.version", "1.0.0");
        String author = appProps.getProperty("cli.author", "baibh");

        System.out.println(CYAN + BOLD + banner + RESET);
        System.out.println(BOLD + " " + appProps.getProperty("cli.description") + RESET);
        System.out.println(DIM + " Version: " + RESET + GREEN + version + RESET + 
                           DIM + " | License: " + RESET + "Apache 2.0" + 
                           DIM + " | Author: " + RESET + YELLOW + author + RESET);
        System.out.println(CYAN + " " + "=".repeat(78) + RESET);
    }

    private static void printUsage() {
        String name = appProps.getProperty("cli.name", "DBChaos");
        System.out.println("\n" + BOLD + "用法 (Usage):" + RESET);
        System.out.println(YELLOW + "  java -jar " + name + ".jar <DB_TYPE> <FAULT_TYPE> [OPTIONS]" + RESET);
        
        System.out.println("\n" + BOLD + "可用画像 (Available Fault Profiles):" + RESET);
        printFaultTable();

        System.out.println("\n" + BOLD + "通用选项 (Global Overrides):" + RESET);
        System.out.printf("  %-20s %s\n", "-url <jdbc_url>", "手动覆盖数据库连接地址");
        System.out.printf("  %-20s %s\n", "-user <username>", "手动覆盖用户名");
        System.out.printf("  %-20s %s\n", "-password <pwd>", "手动覆盖密码");

        System.out.println("\n" + BOLD + "示例 (Example):" + RESET);
        System.out.println(CYAN + "  java -jar " + name + ".jar opengauss plan_flip -threads 16" + RESET);
        System.out.println();
    }

    private static void printFaultTable() {
        System.out.printf(DIM + "  %-18s | %s\n" + RESET, "画像关键字 (ID)", "功能描述 (Description)");
        System.out.println("  " + "-".repeat(60));
        String[][] faults = {
            {"plan_flip", "执行计划震荡/跳变故障"},
            {"max_connection", "数据库最大连接数挤兑 (线程池饱和/连接耗尽)"},
            {"stack_overflow", "内核函数递归导致栈溢出"},
            {"massive_rollback", "大规模事务回滚导致的 I/O 压力"},
            {"memory", "模拟数据库内存溢出或占用过高"},
            {"uncommitted_txn", "长事务导致的行锁持有故障"},
            {"duplicate_txn", "热点行高度并发冲突"}
        };
        for (String[] f : faults) {
            System.out.printf("  " + YELLOW + "%-18s" + RESET + " | %s\n", f[0], f[1]);
        }
    }

    private static BaseFaultInject createInjector(String dbType, String faultType) {
        if (faultType == null) return null;
        switch (faultType.toLowerCase()) {
            case "max_connection": return new chaos.inject.MaxConnectionInject(dbType);
            // 其他 case 保持一致...
            case "base": return new BaseFaultInject(dbType, "BASE") {
                @Override public void execute(String[] args) { this.printHelp(); }
            };
            default: return null;
        }
    }

    private static void parseGlobalOverrides(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("-url".equalsIgnoreCase(args[i])) BaseFaultInject.overrideUrl = args[++i];
            else if ("-user".equalsIgnoreCase(args[i])) BaseFaultInject.overrideUser = args[++i];
            else if ("-password".equalsIgnoreCase(args[i])) BaseFaultInject.overridePassword = args[++i];
        }
    }
}