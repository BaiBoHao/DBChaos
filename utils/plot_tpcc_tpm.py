#!/usr/bin/env python3
"""
Plot TPCC tpmTOTAL and tpmC(NewOrder) similar to benchmarksql charts.

Usage:
  python3 scripts/plot_tpcc_tpm.py
  python3 scripts/plot_tpcc_tpm.py --run-prefix tpcc_2026-04-14_23-07-53
  python3 scripts/plot_tpcc_tpm.py --results-dir results --output results/plots/tpcc_tpm.png
    python3 scripts/plot_tpcc_tpm.py --skip-minutes 2
"""

from __future__ import annotations

import argparse
import csv
import glob
import os
import re
import sys
from datetime import datetime
from typing import Dict, List, Optional, Tuple

try:
    import matplotlib.pyplot as plt
    from matplotlib.ticker import FuncFormatter
except ImportError:
    print("ERROR: Missing matplotlib. Install with: pip3 install matplotlib", file=sys.stderr)
    sys.exit(1)


SUMMARY_RE = re.compile(r"^(?P<prefix>.+)\.summary\.json$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Plot TPCC tpmTOTAL and tpmC(NewOrder)")
    parser.add_argument("--results-dir", default="results", help="BenchBase results directory")
    parser.add_argument("--run-prefix", default=None, help="Run prefix like tpcc_2026-04-14_23-07-53")
    parser.add_argument("--output", default=None, help="Output PNG path")
    parser.add_argument(
        "--title",
        default=None,
        help="Custom chart title. Default uses run prefix.",
    )
    parser.add_argument(
        "--skip-minutes",
        type=float,
        default=0.0,
        help="Skip first N minutes (like benchmarksql generateGraphs.sh SKIP_MINUTES)",
    )
    parser.add_argument(
        "--interval-sec",
        type=int,
        default=0,
        help="Bucket interval in seconds. 0 means auto-select like benchmarksql.",
    )
    return parser.parse_args()


def parse_prefix_datetime(prefix: str) -> datetime:
    tail = prefix.rsplit("_", 2)
    if len(tail) < 3:
        return datetime.min
    dt_text = f"{tail[-2]}_{tail[-1]}"
    try:
        return datetime.strptime(dt_text, "%Y-%m-%d_%H-%M-%S")
    except ValueError:
        return datetime.min


def find_prefixes(results_dir: str) -> List[str]:
    prefixes: List[str] = []
    for path in glob.glob(os.path.join(results_dir, "*.summary.json")):
        base = os.path.basename(path)
        m = SUMMARY_RE.match(base)
        if m:
            prefixes.append(m.group("prefix"))
    return prefixes


def pick_latest_prefix(results_dir: str) -> Optional[str]:
    prefixes = find_prefixes(results_dir)
    if not prefixes:
        return None
    return sorted(prefixes, key=parse_prefix_datetime)[-1]


def choose_interval_seconds(run_seconds: float, override: int) -> int:
    if override > 0:
        return override
    for interval in (1, 2, 5, 10, 20, 60, 120, 300, 600):
        if run_seconds / interval <= 1000:
            return interval
    return 600


def compute_elapsed_ms(start_seconds: float, latency_us: float, base_start_seconds: float) -> float:
    # BenchBase raw.csv uses epoch seconds in the start column despite the header saying microseconds.
    completion_seconds = start_seconds + latency_us / 1_000_000.0
    return (completion_seconds - base_start_seconds) * 1000.0


def aggregate_tpm_from_raw(
    raw_csv: str,
    skip_minutes: float,
    interval_seconds: int,
) -> Tuple[List[float], List[float], List[float], float]:
    rows: List[Tuple[float, float, str]] = []
    with open(raw_csv, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                start_s = float(row["Start Time (microseconds)"])
                latency_us = float(row["Latency (microseconds)"])
                txn = str(row["Transaction Name"])
            except (KeyError, ValueError):
                continue
            rows.append((start_s, latency_us, txn))

    if not rows:
        return [], [], [], 0.0

    base_start = min(r[0] for r in rows)
    run_elapsed_ms = max(compute_elapsed_ms(r[0], r[1], base_start) for r in rows)
    skip_ms = skip_minutes * 60_000.0
    idiv = float(interval_seconds) * 1000.0

    total_bins: Dict[float, int] = {}
    neworder_bins: Dict[float, int] = {}

    for start_s, latency_us, txn in rows:
        elapsed_ms = compute_elapsed_ms(start_s, latency_us, base_start)
        if elapsed_ms < skip_ms:
            continue
        b = (elapsed_ms // idiv) * idiv
        total_bins[b] = total_bins.get(b, 0) + 1
        if txn == "NewOrder":
            neworder_bins[b] = neworder_bins.get(b, 0) + 1

    x_minutes: List[float] = []
    tpm_total: List[float] = []
    tpm_c: List[float] = []

    all_bins = sorted(set(total_bins.keys()) | set(neworder_bins.keys()))
    for b in all_bins:
        x_minutes.append(b / 60_000.0)
        tpm_total.append(total_bins.get(b, 0) * 60.0 / interval_seconds)
        tpm_c.append(neworder_bins.get(b, 0) * 60.0 / interval_seconds)

    return x_minutes, tpm_total, tpm_c, run_elapsed_ms / 1000.0


def calc_ymax(values: List[float]) -> float:
    if not values:
        return 1.0
    target = max(values)
    ymax = 1.0
    sqrt2 = 2.0 ** 0.5
    while ymax < target:
        ymax *= sqrt2
    if ymax < target * 1.2:
        ymax *= 1.2
    return ymax


def main() -> int:
    args = parse_args()
    results_dir = os.path.abspath(args.results_dir)

    if not os.path.isdir(results_dir):
        print(f"ERROR: results directory not found: {results_dir}", file=sys.stderr)
        return 1

    prefix = args.run_prefix or pick_latest_prefix(results_dir)
    if not prefix:
        print(f"ERROR: no run found in {results_dir}", file=sys.stderr)
        return 1

    raw_csv = os.path.join(results_dir, f"{prefix}.raw.csv")

    if not os.path.exists(raw_csv):
        print(f"ERROR: missing file: {raw_csv}", file=sys.stderr)
        return 1

    # First pass to determine run duration and default interval when auto mode is enabled.
    x0, y0, y1, run_seconds = aggregate_tpm_from_raw(
        raw_csv=raw_csv,
        skip_minutes=args.skip_minutes,
        interval_seconds=choose_interval_seconds(300.0, 5),
    )
    if run_seconds <= 0:
        print("ERROR: no valid rows parsed from raw csv", file=sys.stderr)
        return 1

    interval_sec = choose_interval_seconds(run_seconds, args.interval_sec)
    x_total, y_total, y_new, run_seconds = aggregate_tpm_from_raw(
        raw_csv=raw_csv,
        skip_minutes=args.skip_minutes,
        interval_seconds=interval_sec,
    )
    if not x_total:
        print("ERROR: no datapoints after applying skip-minutes", file=sys.stderr)
        return 1

    y_total_plot = y_total
    y_new_plot = y_new

    if args.output:
        out_png = os.path.abspath(args.output)
    else:
        out_dir = os.path.join(results_dir, "plots")
        os.makedirs(out_dir, exist_ok=True)
        out_png = os.path.join(out_dir, f"{prefix}.tpcc_tpm.png")

    # benchmarksql-like visual style
    fig, ax = plt.subplots(figsize=(12.8, 6.4), dpi=120)
    fig.patch.set_facecolor("#e9e9e9")
    ax.set_facecolor("#f4f4f4")

    ax.plot(x_total, y_total_plot, color="#0033cc", linewidth=1.8, label="tpmTOTAL")
    ax.plot(x_total, y_new_plot, color="#cc0000", linewidth=1.8, label="tpmC (NewOrder only)")

    default_title = f"TPCC Run: {prefix}\nTransactions per Minute"
    ax.set_title(args.title or default_title, fontsize=16, fontweight="bold", y=1.04)
    ax.set_xlabel("Elapsed Minutes", fontsize=13)
    ax.set_ylabel("Transactions per Minute", fontsize=13)
    ax.set_xlim(left=args.skip_minutes, right=max(args.skip_minutes, run_seconds / 60.0))
    ax.set_ylim(bottom=0.0, top=calc_ymax(y_total_plot))
    ax.ticklabel_format(style="plain", axis="y", useOffset=False)
    ax.yaxis.set_major_formatter(FuncFormatter(lambda x, _: f"{int(round(x)):,}"))

    ax.grid(True, linestyle=(0, (2, 3)), linewidth=0.8, alpha=0.55)
    ax.legend(loc="upper left", frameon=True, facecolor="#f0f0f0", edgecolor="black")

    fig.tight_layout()
    fig.savefig(out_png)
    plt.close(fig)

    print(f"OK: generated {out_png}")
    print(
        f"INFO: interval={interval_sec}s skip={args.skip_minutes}min points={len(x_total)} "
        f"ymax={int(round(calc_ymax(y_total_plot)))}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
