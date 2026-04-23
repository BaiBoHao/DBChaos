package chaos.inject;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;

import chaos.core.BaseFaultInject;

/**
 * 栈溢出故障注入实现。
 * 依赖基类的 SQLType 进行语系路由 (PostgreSQL / MySQL)
 * 依赖基类的 dbType 进行特定引擎路由 (openGauss / OceanBase 等)
 */
public class StackOverflowInject extends BaseFaultInject {

    // ANSI 颜色定义
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";
    private static final String GREEN  = "\u001B[32m";

    private String setStackDepthSql = "";
    private String setJoinLimitSql = "";

    public StackOverflowInject(String dbType) {
        super(dbType, "STACK_OVERFLOW");
        initEngineSpecificTemplates();
    }

    /**
     * 根据基类提供的 SQL 语系和具体引擎，初始化特有参数
     */
    private void initEngineSpecificTemplates() {
        String sqlFamily = getStandardDbType();
        String engine = this.dbType.toLowerCase();

        // 1. 按语系设定通用基准参数
        if ("postgresql".equals(sqlFamily)) {
            this.setStackDepthSql = "SET max_stack_depth = '2MB'";
            this.setJoinLimitSql = "SET geqo = off; SET join_collapse_limit = %d; SET from_collapse_limit = %d;";
        } else if ("mysql".equals(sqlFamily)) {
            this.setStackDepthSql = "SELECT 1"; // MySQL 语系不支持动态修改栈深，使用无害查询占位
            this.setJoinLimitSql = "SET optimizer_search_depth = 62;";
        }

        // 2. 按具体引擎进行精准微调，追加或覆盖基准参数
        switch (engine) {
            case "opengauss":
            case "og":
                // openGauss 特有的执行计划参数微调，关闭 HashJoin 强迫走嵌套循环
                this.setJoinLimitSql += " SET enable_hashjoin = off;";
                break;
            case "oceanbase":
            case "ob":
                // OceanBase 特有的超时控制，防止因内部超时机制提前中断复杂计算
                this.setJoinLimitSql = "SET ob_query_timeout = 100000000;"; 
                break;
            default:
                break;
        }
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        String mode = getArg(args, "-mode");
        if (mode == null) mode = getArg(args, "-type");
        String durationStr = getArg(args, "-duration");
        String intervalStr = getArg(args, "-interval");
    
        if (durationStr == null) {
            System.err.println(RED + " ✘ 错误：缺失必填参数 -duration (ms)" + RESET);
            printHelp();
            return;
        }

        long durationMs = Long.parseLong(durationStr);
        long intervalMs = (intervalStr != null) ? Long.parseLong(intervalStr) : 1000;
        String finalMode = (mode != null) ? mode.toLowerCase() : "func_recurse";

        System.out.println(CYAN + " >>> " + RESET + BOLD + "正在启动故障注入: " + RESET + YELLOW + "栈溢出 (Stack Overflow)" + RESET);
        System.out.println("   模式: " + finalMode + " | 间隔: " + intervalMs + "ms | 持续: " + durationMs + "ms");
        
        runFaultLoop(finalMode, durationMs, intervalMs);
    }

    @Override
    public void printHelp() {
        System.out.println("\n" + BOLD + "故障画像用法: " + YELLOW + "stack_overflow" + RESET);
        System.out.println("  通过深度递归、表达式爆破或视图嵌套，模拟内核栈溢出场景，验证数据库熔断机制。");
        System.out.println("\n" + BOLD + "参数列表:" + RESET);
        System.out.printf("  %-15s %s\n", "-duration", "必填。故障总时长 (ms)");
        System.out.printf("  %-15s %s\n", "-mode", "选填。爆栈策略 (默认 func_recurse)");
        System.out.printf("  %-15s %s\n", "-interval", "选填。注入频率间隔 (默认 1000ms)");
        
        System.out.println("\n" + BOLD + "支持的模式 (-mode):" + RESET);
        System.out.printf("  %-15s %s\n", "func_recurse", "函数递归爆栈");
        System.out.printf("  %-15s %s\n", "proc_recurse", "存储过程递归爆栈");
        System.out.printf("  %-15s %s\n", "sql_depth", "超深度逻辑表达式解析爆栈");
        System.out.printf("  %-15s %s\n", "view_nest", "超深度视图嵌套爆栈");
        System.out.printf("  %-15s %s\n", "join_bomb", "多表连接执行计划搜索爆栈");

        System.out.println("\n" + BOLD + "示例:" + RESET);
        System.out.println(CYAN + "  ... stack_overflow -duration 60000 -mode view_nest -interval 5000" + RESET);
    }

    private void runFaultLoop(String mode, long duration, long interval) {
        long endTime = System.currentTimeMillis() + duration;
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        int count = 0;

        while (System.currentTimeMillis() < endTime) {
            count++;
            System.out.print("[" + sdf.format(new Date()) + "] #" + count + " 尝试触发 " + mode + " ... ");

            try (Connection conn = getConnection()) {
                executeFault(conn, mode);
                System.out.println(GREEN + "指令已送达" + RESET);
            } catch (SQLException e) {
                // 栈溢出通常返回 54001 (PostgreSQL) 或相关错误码
                System.out.println(YELLOW + "触发预定异常 -> [" + e.getSQLState() + "] " + e.getMessage().split("\n")[0] + RESET);
            } catch (Exception e) {
                System.out.println(RED + "执行异常: " + e.getMessage() + RESET);
            }

            if (System.currentTimeMillis() + interval < endTime) {
                try { Thread.sleep(interval); } catch (InterruptedException e) { break; }
            } else {
                break;
            }
        }
        System.out.println(BOLD + "\n ✔ 栈溢出注入任务结束" + RESET);
    }

    private void executeFault(Connection conn, String mode) throws SQLException {
        switch (mode) {
            case "func_recurse":
                testFunctionStackOverflow(conn);
                break;
            case "proc_recurse":
                testProcedureStackOverflow(conn);
                break;
            case "trans_recurse":
                testTransactionStackOverflow(conn);
                break;
            case "sql_depth":
                testNestedExpressionStackOverflow(conn);
                break;
            case "view_nest":
                testViewBombStackOverflow(conn);
                break;
            case "join_bomb":
                testJoinBombStackOverflow(conn);
                break;
            default:
                throw new IllegalArgumentException("未知的故障类型: " + mode);
        }
    }

    private void testFunctionStackOverflow(Connection conn) throws SQLException {
        String sqlFamily = getStandardDbType();
        try (Statement stmt = conn.createStatement()) {
            if ("postgresql".equals(sqlFamily)) {
                stmt.execute("CREATE OR REPLACE FUNCTION chaos_recurse_func(n INTEGER) RETURNS INT AS $$ BEGIN RETURN chaos_recurse_func(n + 1); END; $$ LANGUAGE plpgsql;");
                stmt.execute("SELECT chaos_recurse_func(1)");
            } else if ("mysql".equals(sqlFamily)) {
                System.out.print("[语法转换: MySQL系不支持函数递归，自动转为存储过程] ");
                testProcedureStackOverflow(conn);
            }
        }
    }

    private void testProcedureStackOverflow(Connection conn) throws SQLException {
        String sqlFamily = getStandardDbType();
        try (Statement stmt = conn.createStatement()) {
            if ("postgresql".equals(sqlFamily)) {
                stmt.execute("CREATE OR REPLACE PROCEDURE chaos_recurse_proc(n INTEGER) AS $$ BEGIN CALL chaos_recurse_proc(n + 1); END; $$ LANGUAGE plpgsql;");
                stmt.execute("CALL chaos_recurse_proc(1)");
            } else if ("mysql".equals(sqlFamily)) {
                stmt.execute("SET @@session.max_sp_recursion_depth = 255");
                stmt.execute("DROP PROCEDURE IF EXISTS chaos_recurse_proc");
                stmt.execute("CREATE PROCEDURE chaos_recurse_proc(n INT) BEGIN CALL chaos_recurse_proc(n + 1); END;");
                stmt.execute("CALL chaos_recurse_proc(1)");
            }
        }
    }

    private void testTransactionStackOverflow(Connection conn) throws SQLException {
        String sqlFamily = getStandardDbType();
        try (Statement stmt = conn.createStatement()) {
            if ("postgresql".equals(sqlFamily)) {
                stmt.execute("CREATE OR REPLACE FUNCTION chaos_recurse_func(n INTEGER) RETURNS INT AS $$ BEGIN RETURN chaos_recurse_func(n + 1); END; $$ LANGUAGE plpgsql;");
            } else if ("mysql".equals(sqlFamily)) {
                stmt.execute("SET @@session.max_sp_recursion_depth = 255");
                stmt.execute("DROP PROCEDURE IF EXISTS chaos_recurse_proc");
                stmt.execute("CREATE PROCEDURE chaos_recurse_proc(n INT) BEGIN CALL chaos_recurse_proc(n + 1); END;");
            }
        }
        
        conn.setAutoCommit(false);
        try (Statement transStmt = conn.createStatement()) {
            if ("postgresql".equals(sqlFamily)) {
                transStmt.execute("SELECT chaos_recurse_func(1)");
            } else {
                transStmt.execute("CALL chaos_recurse_proc(1)");
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void testNestedExpressionStackOverflow(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            try { stmt.execute(setStackDepthSql); } catch (SQLException ignored) {}
            StringBuilder sb = new StringBuilder("SELECT 1 WHERE 1=1");
            for (int i = 0; i < 50000; i++) sb.append(" OR 1=1");
            stmt.execute(sb.toString());
        }
    }

    private void testViewBombStackOverflow(Connection conn) throws SQLException {
        String sqlFamily = getStandardDbType();
        try (Statement stmt = conn.createStatement()) {
            try { stmt.execute(setStackDepthSql); } catch (SQLException ignored) {}
            
            String suffix = "_" + (System.currentTimeMillis() % 10000);
            String baseTable = "chaos_base" + suffix;
            
            // 解决 MySQL 视图定义中严禁引用临时表的限制
            if ("mysql".equals(sqlFamily)) {
                stmt.execute("CREATE TABLE IF NOT EXISTS " + baseTable + " (id int)");
            } else {
                stmt.execute("CREATE TEMPORARY TABLE IF NOT EXISTS " + baseTable + " (id int)");
            }

            String prevRelation = baseTable;
            for (int i = 600; i >= 0; i--) {
                String currView = "chaos_v_" + i + suffix;
                stmt.execute("CREATE OR REPLACE VIEW " + currView + " AS SELECT id FROM " + prevRelation);
                prevRelation = currView;
            }
            stmt.execute("SELECT * FROM chaos_v_0" + suffix);
        }
    }

    private void testJoinBombStackOverflow(Connection conn) throws SQLException {
        String sqlFamily = getStandardDbType();
        try (Statement stmt = conn.createStatement()) {
            int tableCount = 15;
            
            if ("postgresql".equals(sqlFamily)) {
                stmt.execute(String.format(setJoinLimitSql, tableCount + 1, tableCount + 1));
            } else {
                stmt.execute(setJoinLimitSql);
            }

            StringBuilder sqlBuilder = new StringBuilder("SELECT * FROM ");
            for (int i = 0; i < tableCount; i++) {
                String tableName = "chaos_t_" + i;
                if ("mysql".equals(sqlFamily)) {
                     stmt.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (id int)");
                } else {
                     stmt.execute("CREATE TEMPORARY TABLE IF NOT EXISTS " + tableName + " (id int)");
                }
                
                if (i == 0) sqlBuilder.append(tableName);
                else sqlBuilder.append(" JOIN ").append(tableName).append(" ON chaos_t_").append(i-1).append(".id = ").append(tableName).append(".id");
            }
            stmt.execute(sqlBuilder.toString());
        }
    }
}