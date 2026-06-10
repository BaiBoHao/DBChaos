package chaos.inject;

import chaos.core.BaseFaultInject;

/**
 * Test_Case 联动验证 故障注入实现。
 * 通过模板生成后，请补充实际注入逻辑、参数校验与清理策略。
 */
public class TestCaseInject extends BaseFaultInject {
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    public TestCaseInject(String dbType) {
        super(dbType, "TEST_CASE");
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        String durationStr = getArg(args, "-duration");
        if (durationStr == null) {
            System.err.println(RED + " 参数错误: 缺失必填参数 -duration (ms)" + RESET);
            printHelp();
            return;
        }

        long durationMs = Long.parseLong(durationStr);

        System.out.println(CYAN + " >>> " + RESET + BOLD + "启动 Test_Case 联动验证" + RESET);
        System.out.println("   持续时间: " + durationMs + " ms");

        // TODO: 在这里补充具体注入逻辑。
    }

    @Override
    public void printHelp() {
        System.out.println("\n" + BOLD + "不利 Case 用法: " + YELLOW + "quota test_case" + RESET);
        System.out.println("  用于验证 JSON 注册、CLI 帮助页与配置生成器联动是否生效。");
        System.out.println("\n" + BOLD + "参数列表:" + RESET);
        System.out.printf("  %-15s %s\n", "-duration", "必填。故障总时长 (ms)");
        System.out.println("\n" + BOLD + "示例:" + RESET);
        System.out.println(CYAN + "  java -jar DBChaos-0.0.1.jar --db opengauss quota test_case -duration 60000" + RESET);
    }
}
