package chaos.inject;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import chaos.core.BaseFaultInject;

/**
 * 二阶段提交（Prepared Transaction）上限故障注入。
 * 逻辑：并发触发大量的 PREPARE TRANSACTION，达到或超过数据库 max_prepared_transactions 限制。
 */
public class MaxPreparedInject extends BaseFaultInject {

    private String showLimitSql;
    private final String prefix = "chaos_prepared_";

    public MaxPreparedInject(String dbType) {
        super(dbType, "MAX_PREPARED");
        initSqlTemplates();
    }

    private void initSqlTemplates() {
        String sqlType = getStandardDbType();
        if ("postgresql".equals(sqlType)) {
            this.showLimitSql = "SHOW max_prepared_transactions";
        } else if ("mysql".equals(sqlType)) {
            // MySQL 对应 XA 事务，通常由 max_connections 限制，或在特定存储引擎中有限制
            // 此处以获取系统变量为例
            this.showLimitSql = "SHOW VARIABLES LIKE 'max_prepared_stmt_count'"; 
        }
    }

    @Override
    public void execute(String[] args) throws Exception {
        // 1. 参数解析
        Integer targetCount = null;
        int holdDuration = 30;
        int concurrency = 50;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-count":
                    targetCount = Integer.parseInt(args[++i]);
                    break;
                case "-duration":
                    holdDuration = Integer.parseInt(args[++i]);
                    break;
                case "-concurrency":
                    concurrency = Integer.parseInt(args[++i]);
                    break;
            }
        }

        // 2. 环境探测：获取当前数据库上限
        int sysLimit = getMaxLimit();
        int finalTarget = (targetCount != null && targetCount > 0) ? targetCount : sysLimit + 1;

        System.out.println("[配置信息] 注入目标数: " + finalTarget + " | 系统上限: " + sysLimit);
        System.out.println("[配置信息] 并发度: " + concurrency + " | 持有时间: " + holdDuration + "s");

        // 3. 执行并发准备任务
        List<String> successGids = runPrepareTasks(finalTarget, concurrency);

        System.out.println(">>> 成功准备了 " + successGids.size() + " 条事务。");

        // 4. 持有阶段
        if (!successGids.isEmpty()) {
            System.out.println("⏳ 正在保持事务状态，持续 " + holdDuration + " 秒...");
            Thread.sleep(holdDuration * 1000L);
            
            // 5. 自动清理
            cleanupPreparedTransactions(successGids);
        }
        
        System.out.println(">>> 注入任务执行结束。");
    }

    private int getMaxLimit() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(showLimitSql)) {
            if (rs.next()) {
                // PG 返回单列，MySQL 返回两列
                String val = "mysql".equals(getStandardDbType()) ? rs.getString(2) : rs.getString(1);
                return Integer.parseInt(val.trim());
            }
        } catch (Exception e) {
            System.err.println("无法获取 max_prepared_transactions 上限，默认使用 100: " + e.getMessage());
        }
        return 100;
    }

    private List<String> runPrepareTasks(int total, int concurrency) throws Exception {
        String sqlType = getStandardDbType();
        String batchId = prefix + (System.currentTimeMillis() / 1000);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<String>> futures = new ArrayList<>();
        List<String> successGids = new ArrayList<>();

        for (int i = 1; i <= total; i++) {
            final String gid = batchId + "_" + i;
            futures.add(executor.submit(() -> {
                try (Connection conn = getConnection()) {
                    conn.setAutoCommit(false);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("SELECT 1"); // 开启事务
                        if ("postgresql".equals(sqlType)) {
                            stmt.execute("PREPARE TRANSACTION '" + gid + "'");
                        } else {
                            // MySQL XA 语法示例
                            stmt.execute("XA START '" + gid + "'");
                            stmt.execute("SELECT 1");
                            stmt.execute("XA END '" + gid + "'");
                            stmt.execute("XA PREPARE '" + gid + "'");
                        }
                        return gid;
                    } catch (SQLException e) {
                        return null;
                    }
                } catch (Exception e) {
                    return null;
                }
            }));
        }

        executor.shutdown();
        executor.awaitTermination(600, TimeUnit.SECONDS);

        for (Future<String> future : futures) {
            String gid = future.get();
            if (gid != null) successGids.add(gid);
        }
        return successGids;
    }

    private void cleanupPreparedTransactions(List<String> gids) {
        System.out.println(">>> 开始自动清理准备阶段的事务 (ROLLBACK PREPARED)...");
        String sqlType = getStandardDbType();
        for (String gid : gids) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                if ("postgresql".equals(sqlType)) {
                    stmt.execute("ROLLBACK PREPARED '" + gid + "'");
                } else {
                    stmt.execute("XA ROLLBACK '" + gid + "'");
                }
            } catch (SQLException e) {
                // 忽略清理失败
            }
        }
    }
}