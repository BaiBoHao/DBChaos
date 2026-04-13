package chaos;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import chaos.core.BaseFaultInject;

/**
 * DBChaos 项目启动主类。
 * 增加了 ANSI 彩色输出支持，并优化了界面布局以提高阅读性。
 */
public class Main {
    private static Properties appProps = new Properties();

    // ANSI 颜色常量定义
    private static final String RESET = "\u001B[0m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String BOLD = "\u001B[1m";

    static {
        try (InputStream in = Main.class.getResourceAsStream("/chaos.properties")) {
            if (in != null) {
                appProps.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 保持静默，失败时使用默认值
        }
    }

    public static void main(String[] args) {
        // 展示彩色欢迎界面
        printWelcomeScreen();

        if (args.length < 2) {
            printUsage();
            return;
        }

        String dbType = args[0];
        String faultType = args[1];

        parseGlobalOverrides(args);

        BaseFaultInject injector = createInjector(dbType, faultType);

        if (injector == null) {
            System.err.println(RED + "错误：未定义的故障画像类型 '" + faultType + "'" + RESET);
            printUsage();
            return;
        }

        try {
            String[] subArgs = new String[args.length - 2];
            System.arraycopy(args, 2, subArgs, 0, args.length - 2);
            injector.execute(subArgs);
        } catch (Exception e) {
            System.err.println(RED + "故障注入执行失败: " + e.getMessage() + RESET);
        }
    }

    /**
     * 优化后的彩色欢迎界面
     */
    private static void printWelcomeScreen() {
        String banner = appProps.getProperty("cli.banner");
        String name = appProps.getProperty("cli.name", "DBChaos");
        String version = appProps.getProperty("cli.version", "1.0.0");
        String author = appProps.getProperty("cli.author", "baibh");

        // 1. 打印青色加粗的 Banner
        System.out.println(CYAN + BOLD + banner + RESET);
        
        // 2. 打印描述信息
        System.out.println("   " + BOLD + appProps.getProperty("cli.description") + RESET);
        
        // 3. 打印版本和作者（绿色样式）
        System.out.println("\n   " + GREEN + name + " v" + version + RESET + 
                           " | " + appProps.getProperty("cli.license") + 
                           " | by " + YELLOW + author + RESET);
        
        // 4. 分隔线与核心功能
        System.out.println(CYAN + "--------------------------------------------------------------------------------" + RESET);
        System.out.println("   " + BOLD + "核心功能：" + RESET + appProps.getProperty("cli.features"));
        System.out.println(CYAN + "--------------------------------------------------------------------------------" + RESET + "\n");
    }

    private static void parseGlobalOverrides(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("-url".equalsIgnoreCase(args[i])) {
                BaseFaultInject.overrideUrl = args[++i];
            } else if ("-user".equalsIgnoreCase(args[i])) {
                BaseFaultInject.overrideUser = args[++i];
            } else if ("-password".equalsIgnoreCase(args[i])) {
                BaseFaultInject.overridePassword = args[++i];
            }
        }
    }

    private static void printUsage() {
        String name = appProps.getProperty("cli.name", "DBChaos");
        System.out.println(BOLD + "用法提示：" + RESET);
        System.out.println("   java -jar " + name + ".jar <数据库类型> <故障画像> [扩展参数]");
        System.out.println("\n" + BOLD + "受支持的故障画像：" + RESET);
        System.out.println("   " + YELLOW + "plan_flip" + RESET + "         : 执行计划震荡/跳变故障");
        System.out.println("   " + YELLOW + "max_conn" + RESET + "          : 最大连接数挤兑故障");
        System.out.println("   " + YELLOW + "stack_overflow" + RESET + "    : 内核函数递归栈溢出故障");
        System.out.println("   " + YELLOW + "massive_rollback" + RESET + "  : 大规模事务回滚故障");
        System.out.println("   " + YELLOW + "memory" + RESET + "            : 数据库内存占用故障");
        System.out.println("   " + YELLOW + "max_prepared" + RESET + "      : 二阶段提交预处理上限故障");
        System.out.println("   " + YELLOW + "uncommitted_txn" + RESET + "   : 长事务行锁持有故障");
        System.out.println("   " + YELLOW + "duplicate_txn" + RESET + "     : 热点行并发冲突故障");
        System.out.println("\n" + BOLD + "启动示例：" + RESET);
        System.out.println("   java -jar " + name + ".jar opengauss plan_flip -threads 16");
    }

    private static BaseFaultInject createInjector(String dbType, String faultType) {
        if (faultType == null) return null;
        String type = faultType.toLowerCase();
        switch (type) {
            // case "plan_flip":         return new chaos.inject.PlanFlipInject(dbType);
            case "max_connection":    return new chaos.inject.MaxConnectionInject(dbType);
            // case "stack_overflow":    return new chaos.inject.StackOverflowInject(dbType);
            // case "massive_rollback":  return new chaos.inject.MassiveRollbackInject(dbType);
            // case "memory":            return new chaos.inject.MemoryPressureFault(dbType);
            // case "max_prepared":      return new chaos.inject.MaxPreparedInject(dbType);
            // case "uncommitted_txn":   return new chaos.inject.UncommittedTxnInject(dbType);
            // case "duplicate_txn":     return new chaos.inject.DuplicateTxnInject(dbType);
            case "base":
                return new BaseFaultInject(dbType, "BASE_TEST") {
                    @Override public void execute(String[] args) { this.printHelp(); }
                };
            default: return null;
        }
    }
}