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
        String mode = "func_recurse"; 
        long duration = 0;
        long interval = 1000;

        for (int i = 0; i < args.length; i++) {
            if ("-type".equalsIgnoreCase(args[i]) || "-mode".equalsIgnoreCase(args[i])) {
                mode = args[++i].toLowerCase();
            } else if ("-duration".equalsIgnoreCase(args[i])) {
                duration = Long.parseLong(args[++i]);
            } else if ("-interval".equalsIgnoreCase(args[i])) {
                interval = Long.parseLong(args[++i]);
            }
        }

        if (duration <= 0) {
            System.err.println("错误：请指定大于 0 的持续时间，例如: -duration 10000");
            printUsage();
            return;
        }

        System.out.println("[栈溢出注入] 引擎: " + this.dbType + " | 语系: " + getStandardDbType() + " | 模式: " + mode);
        runFaultInjection(mode, duration, interval);
    }

    private void printUsage() {
        System.err.println("可选模式 (-mode 或 -type):");
        System.err.println("  func_recurse  : 函数递归爆栈");
        System.err.println("  proc_recurse  : 存储过程递归爆栈");
        System.err.println("  trans_recurse : 事务中触发函数递归");
        System.err.println("  sql_depth     : 深度 SQL 解析爆栈");
        System.err.println("  view_nest     : 视图深度嵌套爆栈");
        System.err.println("  join_bomb     : 多表连接执行计划爆栈");
    }

    private void runFaultInjection(String mode, long duration, long interval) {
        long startTime = System.currentTimeMillis();
        long endTime = startTime + duration;
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        int count = 0;

        while (System.currentTimeMillis() < endTime) {
            count++;
            System.out.print("[" + sdf.format(new Date()) + "] 第 " + count + " 次注入尝试 (" + mode + ")... ");

            try (Connection conn = getConnection()) {
                executeFault(conn, mode);
                System.out.println("指令发送成功");
            } catch (SQLException e) {
                System.out.println("触发异常 -> " + e.getSQLState() + ": " + e.getMessage().split("\n")[0]);
            } catch (Exception e) {
                System.out.println("执行错误: " + e.getMessage());
            }

            if (System.currentTimeMillis() + interval < endTime) {
                try { Thread.sleep(interval); } catch (InterruptedException e) { break; }
            } else {
                break;
            }
        }
        System.out.println(">>> 注入任务执行结束。");
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