#!/usr/bin/env python3
"""Automated batch runner for openGauss resilience test suite."""
import json, os, pathlib, subprocess, time, threading, sys, signal, csv, math, re

ROOT = pathlib.Path("/home/fenglin/ResBench")
BATCH_DIR = ROOT / "experiment_batch"
CONTAINER = "dbchaos-og-sinval-test"
JAR = ROOT / "DBChaos/DBChaos-0.0.1.jar"
CP = str(ROOT / "DBChaos/target/classes") + ":" + str(JAR)
SQLCP = str(ROOT / "dbchaos_opengauss_2c8g_20260909/scripts") + ":" + str(JAR)
BMS = ROOT / "dbchaos_opengauss_2c8g_20260909/source/benchmarksql/run"

CASES_TO_RUN = [
    {
        "key": "ustore_undo_lag",
        "title": "Ustore 超长 Undo 链刺穿与后台回收滞后",
        "subsystem": "storage",
        "caseKey": "ustore_undo_lag",
        "args": ["-duration", "60000", "-threads", "8"],
        "seconds": 60
    },
    {
        "key": "temp_catalog_bloat",
        "title": "临时表高频生灭导致系统目录膨胀与元数据锁竞争",
        "subsystem": "storage",
        "caseKey": "temp_catalog_bloat",
        "args": ["-duration", "60000", "-threads", "16"],
        "seconds": 60
    },
    {
        "key": "nested_worker_exhaustion",
        "title": "自主事务与嵌套查询引发的线程池饥饿死锁",
        "subsystem": "session",
        "caseKey": "nested_worker_exhaustion",
        "args": ["-duration", "60000", "-threads", "36"],
        "seconds": 60
    },
    {
        "key": "smp_mem_explosion",
        "title": "SMP 算子内存乘积爆炸与向量化执行器 OOM",
        "subsystem": "exec",
        "caseKey": "smp_mem_explosion",
        "args": ["-duration", "60000", "-threads", "6", "-dop", "4"],
        "seconds": 60
    }
]

def stop(p):
    if p.poll() is None:
        try:
            os.killpg(p.pid, signal.SIGTERM)
            p.wait(8)
        except Exception:
            try:
                os.killpg(p.pid, signal.SIGKILL)
                p.wait()
            except Exception:
                pass

def resources(path, event):
    with path.open("w") as f:
        while not event.is_set():
            try:
                r = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}", CONTAINER],
                                   capture_output=True, text=True, timeout=15)
                f.write(json.dumps({"epoch_ms": int(time.time() * 1000), "stdout": r.stdout.strip(), "error": r.stderr.strip()}) + "\n")
                f.flush()
            except Exception as e:
                f.write(json.dumps({"error": str(e)}) + "\n")
            event.wait(3)

def run_single_case(case_info):
    key = case_info["key"]
    c_dir = BATCH_DIR / key
    c_dir.mkdir(parents=True, exist_ok=True)
    b_data_dir = c_dir / "benchmark_data"

    prop_path = c_dir / "benchmark.properties"
    prop_path.write_text(f"""db=postgres
driver=org.opengauss.Driver
conn=jdbc:opengauss://127.0.0.1:25433/dbchaos?connectTimeout=5&socketTimeout=180
user=chaos
password=ChaosTest@2026
warehouses=1
loadWorkers=2
terminals=4
runTxnsPerTerminal=0
runMins=3
limitTxnsPerMin=0
terminalWarehouseFixed=true
newOrderWeight=45
paymentWeight=43
orderStatusWeight=4
deliveryWeight=4
stockLevelWeight=4
resultDirectory={b_data_dir}
""")

    t_title = case_info.get("title", "")
    print(time.strftime("%F %T"), f"=== 开始测试场景: {key} ({t_title}) ===", flush=True)

    print(time.strftime("%F %T"), "重置数据库环境...", flush=True)
    subprocess.run(["docker", "exec", "-u", "omm", "-e", "LD_LIBRARY_PATH=/usr/local/opengauss/lib",
                    CONTAINER, "/usr/local/opengauss/bin/gsql", "-d", "postgres", "-c",
                    "DROP DATABASE IF EXISTS dbchaos; CREATE DATABASE dbchaos;"], check=True)
    subprocess.run(["docker", "exec", "-u", "omm", "-e", "LD_LIBRARY_PATH=/usr/local/opengauss/lib",
                    CONTAINER, "/usr/local/opengauss/bin/gs_restore", "-U", "omm", "-d", "dbchaos",
                    "/tmp/dbchaos_seed.dump"], check=True)

    mf = (c_dir / "monitor.log").open("w")
    bf = (c_dir / "benchmark.log").open("w")
    cf = (c_dir / "injector.log").open("w")

    mon = subprocess.Popen(["java", "-cp", SQLCP, "Monitor", str(prop_path), str(c_dir / "health.csv")],
                           stdout=mf, stderr=mf, start_new_session=True)
    done = threading.Event()
    rt = threading.Thread(target=resources, args=(c_dir / "resources.jsonl", done), daemon=True)
    rt.start()

    bench = subprocess.Popen(["bash", "runBenchmark.sh", str(prop_path)], cwd=BMS,
                             stdout=bf, stderr=bf, start_new_session=True)

    meta = {
        "case": case_info,
        "benchmark_launch_epoch": time.time(),
        "run_minutes": 3,
        "baseline_seconds": 30
    }

    try:
        info = b_data_dir / "data/runInfo.csv"
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if info.exists() and len(info.read_text().splitlines()) > 1:
                break
            if bench.poll() is not None:
                raise RuntimeError("Benchmark failed before workload start")
            time.sleep(0.5)
        else:
            raise RuntimeError("Benchmark start timed out")

        meta["workload_observed_epoch"] = time.time()
        print(time.strftime("%F %T"), "基线稳定期运行 30s...", flush=True)
        time.sleep(30)

        cmd = [
            "java", "-Xmx2g", "-cp", CP, "chaos.Main",
            "--db", "opengauss",
            "-url", "jdbc:opengauss://127.0.0.1:25433/dbchaos?connectTimeout=5&socketTimeout=180",
            "-user", "chaos",
            "-password", "ChaosTest@2026",
            case_info["subsystem"], case_info["caseKey"],
            *case_info["args"]
        ]
        meta["command"] = cmd
        meta["inject_start_epoch"] = time.time()
        print(time.strftime("%F %T"), f">>> 启动不利注入: {key} (60秒)...", flush=True)
        inj = subprocess.Popen(cmd, stdout=cf, stderr=cf, start_new_session=True)
        try:
            meta["injector_exit"] = inj.wait(timeout=115)
        except subprocess.TimeoutExpired:
            meta["injector_timed_out"] = True
            stop(inj)
            meta["injector_exit"] = inj.returncode
        meta["inject_end_epoch"] = time.time()
        print(time.strftime("%F %T"), f">>> 不利注入已撤除，进入自愈观察期...", flush=True)

        try:
            bench.wait(timeout=180 + 30 - (time.time() - meta["benchmark_launch_epoch"]))
        except subprocess.TimeoutExpired:
            meta["benchmark_timed_out"] = True
            stop(bench)
        meta["benchmark_end_epoch"] = time.time()
        meta["observation_end_epoch"] = time.time()
        print(time.strftime("%F %T"), f"=== 场景 {key} 测试结束 ===", flush=True)

    except Exception as e:
        meta["runner_error"] = str(e)
        print(f"[{key}] 运行异常:", e, flush=True)
        stop(bench)
    finally:
        stop(mon)
        done.set()
        rt.join(20)
        mf.close()
        bf.close()
        cf.close()

    (c_dir / "metadata.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False))

def main():
    BATCH_DIR.mkdir(parents=True, exist_ok=True)
    for c in CASES_TO_RUN:
        run_single_case(c)
        time.sleep(5)
    print(time.strftime("%F %T"), "所有批次实验全部完成！", flush=True)

if __name__ == "__main__":
    main()
