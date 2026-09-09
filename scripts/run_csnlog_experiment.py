#!/usr/bin/env python3
"""Run CSNLog Thrashing resilience experiment on 2c8g openGauss container."""
import json, os, pathlib, subprocess, time, threading, sys, signal, csv, math, re

ROOT = pathlib.Path("/home/fenglin/ResBench")
EXP_DIR = ROOT / "experiment_csnlog"
CONTAINER = "dbchaos-og-sinval-test"
JAR = ROOT / "DBChaos/DBChaos-0.0.1.jar"
CP = str(ROOT / "DBChaos/target/classes") + ":" + str(JAR)
SQLCP = str(ROOT / "dbchaos_opengauss_2c8g_20260909/scripts") + ":" + str(JAR)
PROP = EXP_DIR / "benchmark.properties"
BMS = ROOT / "dbchaos_opengauss_2c8g_20260909/source/benchmarksql/run"

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

def main():
    print(time.strftime("%F %T"), "=== 开始 openGauss 2核8GB CSNLog 页面淘汰与提交护航韧性实验 ===", flush=True)

    # 1. 重置数据库环境
    print(time.strftime("%F %T"), "正在通过种子 dump 恢复纯净数据库...", flush=True)
    subprocess.run(["docker", "exec", "-u", "omm", "-e", "LD_LIBRARY_PATH=/usr/local/opengauss/lib",
                    CONTAINER, "/usr/local/opengauss/bin/gsql", "-d", "postgres", "-c",
                    "DROP DATABASE IF EXISTS dbchaos; CREATE DATABASE dbchaos;"], check=True)
    subprocess.run(["docker", "exec", "-u", "omm", "-e", "LD_LIBRARY_PATH=/usr/local/opengauss/lib",
                    CONTAINER, "/usr/local/opengauss/bin/gs_restore", "-U", "omm", "-d", "dbchaos",
                    "/tmp/dbchaos_seed.dump"], check=True)
    print(time.strftime("%F %T"), "数据库重置完成！", flush=True)

    # 2. 准备日志文件
    mf = (EXP_DIR / "monitor.log").open("w")
    bf = (EXP_DIR / "benchmark.log").open("w")
    cf = (EXP_DIR / "injector.log").open("w")

    # 3. 启动健康监控与容器资源监控
    mon = subprocess.Popen(["java", "-cp", SQLCP, "Monitor", str(PROP), str(EXP_DIR / "health.csv")],
                           stdout=mf, stderr=mf, start_new_session=True)
    done = threading.Event()
    rt = threading.Thread(target=resources, args=(EXP_DIR / "resources.jsonl", done), daemon=True)
    rt.start()

    # 4. 启动 TPC-C 业务负载 (运行 3 分钟 = 180s)
    bench = subprocess.Popen(["bash", "runBenchmark.sh", str(PROP)], cwd=BMS,
                             stdout=bf, stderr=bf, start_new_session=True)
    
    meta = {
        "case": {
            "key": "csnlog_thrashing_resilience",
            "title": "CSNLog 页面淘汰争抢与提交护航震荡",
            "subsystem": "txn",
            "caseKey": "csnlog_thrashing",
            "args": ["-duration", "60000", "-threads", "16", "-anchors", "2"],
            "seconds": 60
        },
        "benchmark_launch_epoch": time.time(),
        "run_minutes": 3,
        "baseline_seconds": 30
    }

    try:
        # 等待 TPC-C 正式出数据
        info = EXP_DIR / "benchmark_data_csnlog/data/runInfo.csv"
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
        print(time.strftime("%F %T"), "TPC-C 业务已启动，进入 30s 基线观察期...", flush=True)
        time.sleep(30)

        # 5. 阶段 2: 注入不利 (60s)
        cmd = [
            "java", "-Xmx2g", "-cp", CP, "chaos.Main",
            "--db", "opengauss",
            "-url", "jdbc:opengauss://127.0.0.1:25433/dbchaos?connectTimeout=5&socketTimeout=180",
            "-user", "chaos",
            "-password", "ChaosTest@2026",
            "txn", "csnlog_thrashing",
            "-duration", "60000",
            "-threads", "16",
            "-anchors", "2"
        ]
        meta["command"] = cmd
        meta["inject_start_epoch"] = time.time()
        print(time.strftime("%F %T"), ">>> 开始注入不利: csnlog_thrashing (60秒)...", flush=True)
        inj = subprocess.Popen(cmd, stdout=cf, stderr=cf, start_new_session=True)
        try:
            meta["injector_exit"] = inj.wait(timeout=115)
        except subprocess.TimeoutExpired:
            meta["injector_timed_out"] = True
            stop(inj)
            meta["injector_exit"] = inj.returncode
        meta["inject_end_epoch"] = time.time()
        print(time.strftime("%F %T"), ">>> 不利注入结束并撤除，进入自愈观察期...", flush=True)

        # 6. 阶段 3: 自愈观察期 (直到 3 分钟结束)
        try:
            bench.wait(timeout=180 + 30 - (time.time() - meta["benchmark_launch_epoch"]))
        except subprocess.TimeoutExpired:
            meta["benchmark_timed_out"] = True
            stop(bench)
        meta["benchmark_end_epoch"] = time.time()
        meta["observation_end_epoch"] = time.time()
        print(time.strftime("%F %T"), "=== TPC-C 测试全程结束 ===", flush=True)

    except Exception as e:
        meta["runner_error"] = str(e)
        print("运行错误:", e, flush=True)
        stop(bench)
    finally:
        stop(mon)
        done.set()
        rt.join(20)
        mf.close()
        bf.close()
        cf.close()

    (EXP_DIR / "metadata.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False))
    print(time.strftime("%F %T"), "实验数据采集完成，开始执行韧性量化评估与报告生成...", flush=True)

if __name__ == "__main__":
    main()
