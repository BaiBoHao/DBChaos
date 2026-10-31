package chaos.monitor;

import chaos.registry.SimpleJsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Readable summary of recorded results; also usable for existing experiment directories. */
public final class ResilienceReport {
    private static final String DETAILS = "=== 原始执行与观测明细 ===\n";
    private final Map<?, ?> data;
    private final StringBuilder text = new StringBuilder("=== DBChaos 测试报告 ===\n\n");
    private final StringBuilder csv = new StringBuilder("类别,项目,结论或数值,单位,依据\r\n");

    private ResilienceReport(Map<?, ?> data) { this.data = data; }

    /** Prepend a summary without changing recorded metrics or duplicating previous summaries. */
    public static String write(Path directory) throws IOException {
        Object parsed = SimpleJsonParser.parse(new String(Files.readAllBytes(directory.resolve("resilience.json")), StandardCharsets.UTF_8));
        if (!(parsed instanceof Map)) throw new IOException("resilience.json 必须为对象");
        ResilienceReport report = new ResilienceReport((Map<?, ?>) parsed);
        String summary = report.render();
        Path target = directory.resolve("report.txt");
        String details = Files.exists(target) ? new String(Files.readAllBytes(target), StandardCharsets.UTF_8) : "";
        int marker = details.indexOf(DETAILS);
        if (details.startsWith("=== DBChaos 测试报告 ===") && marker >= 0) details = details.substring(marker + DETAILS.length());
        Files.write(directory.resolve("summary.csv"), report.csv.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(target, (summary + "\n" + DETAILS + details).getBytes(StandardCharsets.UTF_8));
        return summary;
    }

    private String render() {
        String status = Boolean.TRUE.equals(data.get("execution_failed")) ? "测试执行失败"
                : Boolean.TRUE.equals(data.get("evidence_complete")) ? "已完成观测；结论限定于本次故障与负载"
                : "证据不足，不能确认完整的韧性结论";
        text.append("测试状态：").append(status).append("\n\n一、三项核心结论\n\n");
        row("状态", "测试状态", status, "", "");
        text.append("| 问题 | 结论 | 主要依据 |\n| --- | --- | --- |\n");
        conclusion("隔离效果好不好", "isolation", "最大吞吐下降 " + value("max_drop_pct", "%")
                + "；最差窗口 P95/基线 " + value("fault_p95_multiplier", " 倍")
                + "；故障错误率 " + value("fault_error_pct", "%"));
        conclusion("故障期间是否可用", "continuity", "故障最低成功 TPS " + value("Pd", "")
                + "；故障错误率 " + value("fault_error_pct", "%") + "；成功响应 P95 " + value("fault_p95_ms", "ms"));
        String duration = recoveryDuration();
        conclusion("撤销后恢复如何", "recovery", "吞吐恢复至基线 " + value("restored_baseline_pct", "%")
                + "；恢复末期 P95 " + value("recovery_p95_ms", "ms") + "；错误率 "
                + value("recovery_error_pct", "%") + "；确认恢复耗时 " + duration);

        text.append("\n二、阶段指标对比\n\n| 指标 | 正常基线 | 故障期间 | 恢复末期 |\n| --- | --- | --- | --- |\n");
        comparison("成功吞吐 TPS", "P0", "Pd", "Pr", "TPS");
        comparison("成功响应 P95", "baseline_p95_ms", "fault_p95_ms", "recovery_p95_ms", "ms");
        comparison("业务错误率", "baseline_error_pct", "fault_error_pct", "recovery_error_pct", "%");
        text.append("吞吐口径：基线为稳定窗口均值；故障为完整窗口最低值 Pd；恢复为恢复窗口组均值 Pr。\n")
                .append("P95 仅统计成功请求；故障列为阶段汇总 P95，隔离评价另使用最差窗口 P95。\n");
        text.append("\n三、时间与数据库响应证据\n\n");
        timestamp("故障开始 t0", "t0_epoch_ms");
        timestamp("吞吐最低窗口结束 td", "td_epoch_ms");
        timestamp("故障撤销", "fault_withdrawal_epoch_ms");
        timestamp("确认服务恢复 tr", "tr_epoch_ms");
        item("恢复耗时", duration);
        Object probe = data.get("database_probe_fault_windows_all_responsive");
        item("数据库探针", probe == null ? "未评估/没有足够探针证据"
                : Boolean.TRUE.equals(probe) ? "每个完整故障窗口均有 SELECT 1 成功响应"
                : "部分完整故障窗口没有确认 SELECT 1 成功响应");
        text.append("探针与业务可用性分开评价；探针响应不能代表业务正常，失败也不能直接证明数据库宕机。\n");
        text.append("\n四、失败原因与结论限制\n\n");
        if (data.get("failure_phase") != null) item("失败阶段", String.valueOf(data.get("failure_phase")));
        entries("具体失败原因", data.get("failure_samples"));
        entries("检查建议", data.get("check_hints"));
        if (data.get("monitoring_error") != null) item("观测问题", String.valueOf(data.get("monitoring_error")));
        Object missing = data.get("unavailable_reasons");
        if (missing instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) missing).entrySet())
            item(String.valueOf(e.getKey()) + " 未获取/未确认", String.valueOf(e.getValue()));
        item("证据完整", Boolean.TRUE.equals(data.get("evidence_complete")) ? "是" : "否；三项结论应结合缺失原因阅读");
        item("时间对齐", field("time_alignment"));
        item("统计窗口", value("window_ms", "ms") + "；恢复连续窗口数 " + value("stable_windows", ""));
        item("恢复判据", "每窗口 TPS ≥ 基线 " + value("recovery_target_pct", "%")
                + "，TPS CV ≤ " + value("cv_limit_pct", "%") + "；P95 ≤ 基线 × "
                + value("recovery_latency_multiplier_limit", "") + " + 1ms；错误率 ≤ 基线 + "
                + value("max_error_increase_percentage_points", " 个百分点"));
        item("较好隔离判据", "最大吞吐下降 ≤ " + value("isolation_max_drop_pct", "%")
                + "；最差窗口 P95 ≤ 基线 × " + value("isolation_latency_multiplier_limit", "")
                + " + 1ms；错误率 ≤ 基线 + " + value("max_error_increase_percentage_points", " 个百分点"));
        text.append("未获取/未确认不等于零或测试失败；未确认恢复时不以观测结束时间替代 tr。\n")
                .append("这些结论描述本次业务受到的影响，不推断数据库对所有故障均有内部隔离能力。\n")
                .append("\n文件：report.txt（本报告）；summary.csv（结论与指标）；throughput.csv（逐窗口数据）；")
                .append("database-health.csv（探针）；resilience.json（完整结构化结果）。\n");
        return text.toString();
    }

    private void conclusion(String question, String key, String evidence) {
        String result = field(key);
        text.append("| ").append(cell(question)).append(" | ").append(cell(result)).append(" | ").append(cell(evidence)).append(" |\n");
        row("核心结论", question, result, "", evidence);
    }
    private void comparison(String label, String base, String fault, String recovery, String unit) {
        text.append("| ").append(label).append(" | ").append(value(base, unit)).append(" | ")
                .append(value(fault, unit)).append(" | ").append(value(recovery, unit)).append(" |\n");
        row("阶段指标", "基线 " + label, value(base, ""), unit, base);
        row("阶段指标", "故障 " + label, value(fault, ""), unit, fault);
        row("阶段指标", "恢复 " + label, value(recovery, ""), unit, recovery);
    }
    private void timestamp(String label, String key) {
        Object n = data.get(key);
        item(label, n instanceof Number ? Instant.ofEpochMilli(((Number) n).longValue()).toString() + "（UTC）" : "未获取/未确认");
    }
    private String recoveryDuration() {
        Object end = data.get("tr_epoch_ms"), start = data.get("fault_withdrawal_epoch_ms");
        return end instanceof Number && start instanceof Number
                ? String.format(Locale.ROOT, "%.3fs", (((Number) end).longValue() - ((Number) start).longValue()) / 1000.0)
                : "未确认（详见恢复结论与缺失原因）";
    }
    private void entries(String label, Object values) {
        if (values instanceof List) for (Object value : (List<?>) values) item(label, String.valueOf(value));
    }
    private void item(String label, String value) {
        text.append(label).append("：").append(value).append('\n');
        row("说明", label, value, "", "");
    }
    private String field(String key) { Object v = data.get(key); return v == null ? "未获取/未确认" : v.toString(); }
    private String value(String key, String unit) {
        Object v = data.get(key);
        return v instanceof Number ? String.format(Locale.ROOT, "%.2f", ((Number) v).doubleValue()) + unit : "未获取/未确认";
    }
    private static String cell(String s) { return s.replace("|", "／").replace("\r", " ").replace("\n", " "); }
    private void row(String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) csv.append(',');
            csv.append('"').append(values[i].replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("参数：已有 DBChaos 报告目录");
        System.out.print(write(Paths.get(args[0])));
    }
}
