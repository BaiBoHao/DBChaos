#!/usr/bin/env python3
"""
Generate TPC-C ChaosBlade bridge XML files for DBChaos.

The generator writes three files:
  1. opengauss_tpcc_config_chaosblade.xml
     Contains all DBChaos fault cases under <faultCases>.
  2. tpcc_worker.xml
     Points the worker phase at the selected testSuite.
  3. fault-cases-generic.xml
     Contains the final selected testSuite instance.

Example:
  python scripts/generate_configs.py \
    --template-config "/path/to/opengauss_tpccbbh_config_chaosblade.xml" \
    --template-worker "/path/to/tpccbbh-worker.xml" \
    --template-suites "/path/to/fault-cases-generic.xml" \
    --select plan_flip,max_connection_conn_storm,memory_pressure
"""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from configparser import ConfigParser
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


XI_NS = "http://www.w3.org/2001/XInclude"
ET.register_namespace("xi", XI_NS)

DEFAULT_OUTPUT_CONFIG = "opengauss_tpcc_config_chaosblade.xml"
DEFAULT_OUTPUT_WORKER = "tpcc_worker.xml"
DEFAULT_OUTPUT_SUITES = "fault-cases-generic.xml"
DEFAULT_SUITE_NAME = "dbchaos-generated-suite"
DEFAULT_SELECTED_CASE_KEYS: Tuple[str, ...] = ("all",)


@dataclass(frozen=True)
class FaultSpec:
    """One DBChaos fault point exposed as an upstream fault case."""

    id: int
    key: str
    fault_type: str
    description: str
    category: str
    args: Tuple[str, ...]
    default_during_sec: int = 60


FAULT_SPECS: Tuple[FaultSpec, ...] = (
    FaultSpec(
        id=201,
        key="plan_flip",
        fault_type="plan_flip",
        description="DBChaos Plan Flip",
        category="SQL parsing and optimization",
        args=("-duration", "60000", "-threads", "16", "-count", "1000000", "-interval", "60000"),
    ),
    FaultSpec(
        id=202,
        key="max_connection_conn_storm",
        fault_type="max_connection",
        description="DBChaos Connection Storm",
        category="connection management",
        args=("-mode", "conn_storm", "-count", "200", "-duration", "60000"),
    ),
    FaultSpec(
        id=203,
        key="max_connection_conn_exhaustion",
        fault_type="max_connection",
        description="DBChaos Connection Exhaustion",
        category="connection management",
        args=("-mode", "conn_exhaustion", "-count", "200", "-duration", "60000"),
    ),
    FaultSpec(
        id=204,
        key="max_connection_thread_saturation",
        fault_type="max_connection",
        description="DBChaos Thread Pool Saturation",
        category="execution engine",
        args=("-mode", "thread_saturation", "-count", "32", "-duration", "60000"),
    ),
    FaultSpec(
        id=205,
        key="uncommitted_txn",
        fault_type="uncommitted_txn",
        description="DBChaos Uncommitted Transaction Lock",
        category="transaction concurrency management",
        args=("-duration", "60000", "-table", "bmsql_stock", "-holders", "2", "-rows", "500"),
    ),
    FaultSpec(
        id=206,
        key="duplicate_txn_update",
        fault_type="duplicate_txn",
        description="DBChaos Hot Row Update Conflict",
        category="transaction concurrency management",
        args=("-sessions", "50", "-duration", "60", "-mode", "UPDATE"),
    ),
    FaultSpec(
        id=207,
        key="duplicate_txn_insert",
        fault_type="duplicate_txn",
        description="DBChaos Duplicate Insert Conflict",
        category="transaction concurrency management",
        args=("-sessions", "50", "-duration", "60", "-mode", "INSERT"),
    ),
    FaultSpec(
        id=208,
        key="stack_overflow_func_recurse",
        fault_type="stack_overflow",
        description="DBChaos Function Stack Overflow",
        category="execution engine",
        args=("-mode", "func_recurse", "-duration", "60000", "-interval", "1000"),
    ),
    FaultSpec(
        id=209,
        key="stack_overflow_proc_recurse",
        fault_type="stack_overflow",
        description="DBChaos Procedure Stack Overflow",
        category="execution engine",
        args=("-mode", "proc_recurse", "-duration", "60000", "-interval", "1000"),
    ),
    FaultSpec(
        id=210,
        key="stack_overflow_trans_recurse",
        fault_type="stack_overflow",
        description="DBChaos Transaction Stack Overflow",
        category="execution engine",
        args=("-mode", "trans_recurse", "-duration", "60000", "-interval", "1000"),
    ),
    FaultSpec(
        id=211,
        key="stack_overflow_sql_depth",
        fault_type="stack_overflow",
        description="DBChaos SQL Depth Stack Overflow",
        category="SQL parsing and optimization",
        args=("-mode", "sql_depth", "-duration", "60000", "-interval", "1000"),
    ),
    FaultSpec(
        id=212,
        key="stack_overflow_view_nest",
        fault_type="stack_overflow",
        description="DBChaos Nested View Stack Overflow",
        category="SQL parsing and optimization",
        args=("-mode", "view_nest", "-duration", "60000", "-interval", "5000"),
    ),
    FaultSpec(
        id=213,
        key="stack_overflow_join_bomb",
        fault_type="stack_overflow",
        description="DBChaos Join Search Stack Overflow",
        category="SQL parsing and optimization",
        args=("-mode", "join_bomb", "-duration", "60000", "-interval", "5000"),
    ),
    FaultSpec(
        id=214,
        key="massive_rollback",
        fault_type="massive_rollback",
        description="DBChaos Massive Transaction Rollback",
        category="storage engine",
        args=("-duration", "60000", "-threads", "16", "-rate", "0.7"),
    ),
    FaultSpec(
        id=215,
        key="memory_pressure",
        fault_type="memory_pressure",
        description="DBChaos Memory Pressure",
        category="memory",
        args=("-duration", "60000", "-batch", "50", "-threads", "4"),
    ),
    FaultSpec(
        id=216,
        key="max_prepared",
        fault_type="max_prepared",
        description="DBChaos Prepared Transaction Limit",
        category="transaction concurrency management",
        args=("-count", "201", "-duration", "60", "-concurrency", "50"),
    ),
)

FAULT_BY_KEY: Dict[str, FaultSpec] = {spec.key: spec for spec in FAULT_SPECS}
FAULT_BY_ID: Dict[int, FaultSpec] = {spec.id: spec for spec in FAULT_SPECS}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate DBChaos faultCases, tpcc_worker, and fault-cases-generic XML files."
    )
    parser.add_argument("--template-config", type=Path, help="OpenGauss TPC-C ChaosBlade config template. If missing, a default config skeleton is created.")
    parser.add_argument("--template-worker", type=Path, help="TPC-C worker template. If missing, a default worker skeleton is created.")
    parser.add_argument("--template-suites", type=Path, help="fault-cases-generic template. If missing, a default suite skeleton is created.")
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--output-config", default=DEFAULT_OUTPUT_CONFIG)
    parser.add_argument("--output-worker", default=DEFAULT_OUTPUT_WORKER)
    parser.add_argument("--output-suites", default=DEFAULT_OUTPUT_SUITES)
    parser.add_argument("--worker-include-href", default=DEFAULT_OUTPUT_WORKER)

    parser.add_argument("--db-type", default=None, help="DBChaos DB type argument. Defaults to template <type>.")
    parser.add_argument("--java-cmd", default="/opt/java-21/bin/java")
    parser.add_argument("--jar-path", default="scripts/java/DBChaos-0.0.1.jar")
    parser.add_argument("--agent", action="append", help="Target agent endpoint. Can be repeated.")
    parser.add_argument("--no-db-overrides", action="store_true", help="Do not pass -url/-user/-password to DBChaos.")

    parser.add_argument("--suite-name", default=DEFAULT_SUITE_NAME)
    parser.add_argument(
        "--select",
        default=None,
        help="Comma-separated keys, generated IDs, list numbers, or all. Prefix with '-' to exclude from all.",
    )
    parser.add_argument("--selection-file", type=Path, help="JSON file with optional cases/suite/timing settings.")
    parser.add_argument("--interactive", action="store_true", help="Interactively choose the final testSuite cases.")

    parser.add_argument("--planning-start-sec", type=int, default=120)
    parser.add_argument("--planning-step-sec", type=int, default=80)
    parser.add_argument("--during-sec", type=int, default=60)
    parser.add_argument(
        "--worker-time",
        default="auto",
        help="Worker time in seconds, or auto to fit the selected suite.",
    )
    parser.add_argument("--worker-rate", default=None, help="Override worker <rate>; defaults to worker template.")
    parser.add_argument("--worker-weights", default=None, help="Override worker <weights>; defaults to worker template.")
    parser.add_argument("--worker-arrival", default=None, help="Override work arrival attr; defaults to worker template.")
    parser.add_argument("--list", action="store_true", help="List known DBChaos fault cases and exit.")
    return parser


def optional_path(path: Optional[Path], label: str) -> Optional[Path]:
    if path is None:
        raise SystemExit(f"ERROR: --{label} is required unless --list is used.")
    return path if path.exists() else None


def read_selection_file(path: Optional[Path]) -> Dict[str, object]:
    if path is None:
        return {}
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise SystemExit("ERROR: selection file must be a JSON object.")
    return data


def print_fault_catalog() -> None:
    print("Known DBChaos fault cases:")
    for idx, spec in enumerate(FAULT_SPECS, start=1):
        print(f"  {idx:02d}. id={spec.id} key={spec.key:<34} category={spec.category}")


def split_tokens(text: Optional[str]) -> List[str]:
    if text is None:
        return []
    return [part.strip() for part in text.replace(";", ",").split(",") if part.strip()]


def resolve_token(token: str) -> FaultSpec:
    lowered = token.lower()
    if lowered in FAULT_BY_KEY:
        return FAULT_BY_KEY[lowered]
    if lowered.isdigit():
        number = int(lowered)
        if 1 <= number <= len(FAULT_SPECS):
            return FAULT_SPECS[number - 1]
        if number in FAULT_BY_ID:
            return FAULT_BY_ID[number]
    raise SystemExit(f"ERROR: unknown fault selector: {token}")


def resolve_selection(select_text: Optional[str], interactive: bool) -> List[FaultSpec]:
    if interactive:
        print_fault_catalog()
        prompt = (
            "\nSelect cases by key, generated id, or list number. "
            "Use 'all' for every case, e.g. all,-memory_pressure. "
            "Press Enter to use DEFAULT_SELECTED_CASE_KEYS: "
        )
        entered = input(prompt).strip()
        if entered:
            select_text = entered

    tokens = split_tokens(select_text)
    if not tokens:
        tokens = list(DEFAULT_SELECTED_CASE_KEYS)

    selected: List[FaultSpec]
    excludes: List[FaultSpec] = []
    include_tokens: List[str] = []

    for token in tokens:
        if token.lower() == "all":
            include_tokens.append("all")
        elif token.startswith("-"):
            excludes.append(resolve_token(token[1:]))
        else:
            include_tokens.append(token)

    if "all" in include_tokens:
        selected = list(FAULT_SPECS)
    else:
        selected = []
        for token in include_tokens:
            spec = resolve_token(token)
            if spec not in selected:
                selected.append(spec)

    for spec in excludes:
        selected = [item for item in selected if item != spec]

    if not selected:
        raise SystemExit("ERROR: final testSuite selection is empty.")
    return selected


def text_element(tag: str, value: object) -> ET.Element:
    node = ET.Element(tag)
    node.text = str(value)
    return node


def set_child_text(parent: ET.Element, tag: str, value: object) -> None:
    child = parent.find(tag)
    if child is None:
        child = ET.SubElement(parent, tag)
    child.text = str(value)


def command_element(tag: str, cmd: str, args: Iterable[str]) -> ET.Element:
    node = ET.Element(tag)
    node.append(text_element("cmd", cmd))
    for arg in args:
        node.append(text_element("arg", arg))
    return node


def parse_xml(path: Path) -> ET.ElementTree:
    try:
        return ET.parse(path)
    except ET.ParseError as exc:
        raise SystemExit(f"ERROR: invalid XML in {path}: {exc}") from exc


def load_properties_file(path: Path) -> Dict[str, str]:
    parser = ConfigParser()
    parser.optionxform = str
    parser.read_string("[root]\n" + path.read_text(encoding="utf-8"))
    return dict(parser["root"])


def infer_driver_from_db_type(db_type: str) -> str:
    lower = db_type.lower()
    if lower in ("opengauss", "og", "postgresql", "pg"):
        return "org.postgresql.Driver"
    if lower in ("mysql", "oceanbase", "ob"):
        return "com.mysql.cj.jdbc.Driver"
    return "org.postgresql.Driver"


def normalize_jdbc_url(url: str, db_type: str) -> str:
    stripped = (url or "").strip()
    if not stripped:
        return "jdbc:postgresql://localhost:15432/tpcc_100"
    if db_type.lower() in ("opengauss", "og") and stripped.startswith("jdbc:opengauss://"):
        return "jdbc:postgresql://" + stripped[len("jdbc:opengauss://"):]
    return stripped


def default_transaction_types() -> ET.Element:
    node = ET.Element("transactiontypes")
    for name in ("NewOrder", "Payment", "OrderStatus", "Delivery", "StockLevel"):
        txn = ET.SubElement(node, "transactiontype")
        txn.append(text_element("name", name))
    return node


def build_default_config_tree(template_path: Path, db_type_hint: Optional[str], worker_include_href: str) -> ET.ElementTree:
    repo_root = Path(__file__).resolve().parents[1]
    props = load_properties_file(repo_root / "resources" / "db.properties")
    raw_db_type = (db_type_hint or props.get("type") or "opengauss").strip()
    normalized_type = raw_db_type.upper()
    jdbc_url = normalize_jdbc_url(props.get("url", ""), raw_db_type)

    root = ET.Element("parameters")
    root.append(text_element("type", normalized_type))
    root.append(text_element("driver", infer_driver_from_db_type(raw_db_type)))
    root.append(text_element("url", jdbc_url))
    nodes = ET.SubElement(root, "nodes")
    nodes.append(text_element("node", jdbc_url))
    root.append(text_element("username", props.get("user", "benchmarksql")))
    root.append(text_element("password", props.get("password", "BenchmarkSql@123")))
    root.append(text_element("reconnectOnConnectionFailure", "true"))
    root.append(text_element("isolation", "TRANSACTION_READ_COMMITTED"))
    root.append(text_element("batchsize", "128"))
    root.append(text_element("scalefactor", "10"))
    root.append(text_element("data_cktype", "ONLY_SPECIAL"))
    root.append(text_element("terminals", "16"))
    root.append(ET.Element("faultCases"))
    include = ET.Element(f"{{{XI_NS}}}include", {"href": worker_include_href, "parse": "xml"})
    root.append(include)
    root.append(default_transaction_types())
    return ET.ElementTree(root)


def load_or_create_config_tree(template_path: Optional[Path], db_type_hint: Optional[str], worker_include_href: str) -> ET.ElementTree:
    if template_path is None:
        print("INFO: template-config not found; bootstrapping default config from resources/db.properties")
        return build_default_config_tree(Path(DEFAULT_OUTPUT_CONFIG), db_type_hint, worker_include_href)
    return parse_xml(template_path)


def load_or_create_worker_tree(template_path: Optional[Path]) -> ET.ElementTree:
    if template_path is None:
        print("INFO: template-worker not found; bootstrapping default worker skeleton")
        return ET.ElementTree(ET.Element("works"))
    return parse_xml(template_path)


def load_or_create_suites_tree(template_path: Optional[Path]) -> ET.ElementTree:
    if template_path is None:
        print("INFO: template-suites not found; bootstrapping default suite skeleton")
        return ET.ElementTree(ET.Element("testSuites"))
    return parse_xml(template_path)


def indent_xml(root: ET.Element) -> None:
    if hasattr(ET, "indent"):
        ET.indent(root, space="    ")
        return

    def _indent(elem: ET.Element, level: int = 0) -> None:
        padding = "\n" + level * "    "
        child_padding = "\n" + (level + 1) * "    "
        if len(elem):
            if not elem.text or not elem.text.strip():
                elem.text = child_padding
            for child in elem:
                _indent(child, level + 1)
            if not elem[-1].tail or not elem[-1].tail.strip():
                elem[-1].tail = padding
        if level and (not elem.tail or not elem.tail.strip()):
            elem.tail = padding

    _indent(root)


def ensure_output_file(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        path.touch()


def write_xml(tree: ET.ElementTree, path: Path) -> None:
    ensure_output_file(path)
    indent_xml(tree.getroot())
    tree.write(path, encoding="utf-8", xml_declaration=True, short_empty_elements=False)


def clean_db_type(raw_type: Optional[str]) -> str:
    value = (raw_type or "opengauss").strip().lower()
    if value == "og":
        return "opengauss"
    return value


def extract_template_agents(config_root: ET.Element) -> List[str]:
    first_agents = config_root.find("./faultCases/case/agents")
    if first_agents is None:
        return ["master:8000"]
    agents = [node.text.strip() for node in first_agents.findall("agent") if node.text and node.text.strip()]
    return agents or ["master:8000"]


def jar_basename(jar_path: str) -> str:
    return jar_path.replace("\\", "/").rstrip("/").split("/")[-1] or "DBChaos"


def db_override_args(config_root: ET.Element, enabled: bool) -> Tuple[str, ...]:
    if not enabled:
        return ()
    url = config_root.findtext("url")
    user = config_root.findtext("username")
    password = config_root.findtext("password")
    args: List[str] = []
    if url:
        args.extend(["-url", url.strip()])
    if user:
        args.extend(["-user", user.strip()])
    if password:
        args.extend(["-password", password.strip()])
    return tuple(args)


def build_fault_case_element(
    spec: FaultSpec,
    agents: Sequence[str],
    java_cmd: str,
    jar_path: str,
    db_type: str,
    extra_db_args: Sequence[str],
) -> ET.Element:
    case = ET.Element("case")
    case.append(text_element("id", spec.id))
    case.append(text_element("description", spec.description))

    agents_node = ET.SubElement(case, "agents")
    for agent in agents:
        agents_node.append(text_element("agent", agent))

    injection_args = ["-jar", jar_path, db_type, spec.fault_type]
    injection_args.extend(spec.args)
    injection_args.extend(extra_db_args)
    case.append(command_element("injection", java_cmd, injection_args))

    recovery_pattern = f"{jar_basename(jar_path)}.*{spec.fault_type}"
    case.append(command_element("recovery", "pkill", ("-f", "-9", recovery_pattern)))
    case.append(text_element("autoRecovery", "0"))
    return case


def replace_fault_cases(
    config_tree: ET.ElementTree,
    specs: Sequence[FaultSpec],
    agents: Sequence[str],
    java_cmd: str,
    jar_path: str,
    db_type: str,
    include_db_overrides: bool,
    worker_include_href: str,
) -> None:
    root = config_tree.getroot()
    db_args = db_override_args(root, include_db_overrides)

    old_fault_cases = root.find("faultCases")
    children = list(root)
    insert_at = children.index(old_fault_cases) if old_fault_cases is not None else len(children)
    if old_fault_cases is not None:
        root.remove(old_fault_cases)

    fault_cases = ET.Element("faultCases")
    for spec in specs:
        fault_cases.append(build_fault_case_element(spec, agents, java_cmd, jar_path, db_type, db_args))
    root.insert(insert_at, fault_cases)

    include_tag = f"{{{XI_NS}}}include"
    include = None
    for child in root:
        if child.tag == include_tag:
            include = child
            break
    if include is None:
        include = ET.Element(include_tag, {"href": worker_include_href, "parse": "xml"})
        root.insert(insert_at + 1, include)
    else:
        include.set("href", worker_include_href)
        include.set("parse", "xml")


def first_work_template(worker_root: ET.Element) -> Optional[ET.Element]:
    return worker_root.find("work")


def template_child_text(template: Optional[ET.Element], tag: str, fallback: str) -> str:
    if template is None:
        return fallback
    value = template.findtext(tag)
    return value.strip() if value and value.strip() else fallback


def selected_suite_end_sec(
    selected: Sequence[FaultSpec],
    planning_start_sec: int,
    planning_step_sec: int,
    during_sec: int,
) -> int:
    last_planning = planning_start_sec + (len(selected) - 1) * planning_step_sec
    return last_planning + during_sec


def compute_worker_time(
    worker_time: str,
    selected: Sequence[FaultSpec],
    planning_start_sec: int,
    planning_step_sec: int,
    during_sec: int,
) -> int:
    if worker_time.lower() != "auto":
        try:
            return int(worker_time)
        except ValueError as exc:
            raise SystemExit("--worker-time must be an integer or auto.") from exc
    return selected_suite_end_sec(selected, planning_start_sec, planning_step_sec, during_sec) + 60


def build_worker_tree(
    template_worker: Optional[Path],
    suite_name: str,
    selected: Sequence[FaultSpec],
    planning_start_sec: int,
    planning_step_sec: int,
    during_sec: int,
    worker_time: str,
    worker_rate: Optional[str],
    worker_weights: Optional[str],
    worker_arrival: Optional[str],
) -> ET.ElementTree:
    tree = load_or_create_worker_tree(template_worker)
    root = tree.getroot()
    if root.tag != "works":
        raise SystemExit(f"ERROR: worker XML root must be <works>, got <{root.tag}>.")

    template = first_work_template(root)
    arrival = worker_arrival or (template.get("arrival") if template is not None else None) or "REGULAR"
    rate = worker_rate or template_child_text(template, "rate", "50000")
    weights = worker_weights or template_child_text(template, "weights", "5,25,20,15,15")
    total_time = compute_worker_time(worker_time, selected, planning_start_sec, planning_step_sec, during_sec)

    root.clear()
    work = ET.SubElement(root, "work", {"arrival": arrival})
    work.append(text_element("time", total_time))
    work.append(text_element("rate", rate))
    work.append(text_element("weights", weights))
    work.append(text_element("testSuite", suite_name))
    return tree


def build_suite_case(spec: FaultSpec, planning: int, during: int) -> ET.Element:
    case = ET.Element("case", {"id": str(spec.id)})
    case.append(text_element("description", spec.description))
    case.append(text_element("planning", planning))
    case.append(text_element("during", during))
    return case


def build_suites_tree(
    template_suites: Optional[Path],
    suite_name: str,
    selected: Sequence[FaultSpec],
    planning_start_sec: int,
    planning_step_sec: int,
    during_sec: int,
) -> ET.ElementTree:
    tree = load_or_create_suites_tree(template_suites)
    root = tree.getroot()
    if root.tag != "testSuites":
        raise SystemExit(f"ERROR: suites XML root must be <testSuites>, got <{root.tag}>.")

    for existing in list(root.findall("testSuite")):
        if existing.get("name") == suite_name:
            root.remove(existing)

    suite = ET.Element("testSuite", {"name": suite_name})
    for index, spec in enumerate(selected):
        planning = planning_start_sec + index * planning_step_sec
        suite.append(build_suite_case(spec, planning, during_sec))
    root.append(suite)
    return tree


def apply_selection_file(args: argparse.Namespace, settings: Dict[str, object]) -> None:
    if "suite_name" in settings and args.suite_name == DEFAULT_SUITE_NAME:
        args.suite_name = str(settings["suite_name"])
    if "cases" in settings and args.select is None:
        cases = settings["cases"]
        if not isinstance(cases, list):
            raise SystemExit("ERROR: selection file 'cases' must be a list.")
        args.select = ",".join(str(item) for item in cases)
    for attr, key in (
        ("planning_start_sec", "planning_start_sec"),
        ("planning_step_sec", "planning_step_sec"),
        ("during_sec", "during_sec"),
        ("worker_time", "worker_time"),
        ("worker_rate", "worker_rate"),
        ("worker_weights", "worker_weights"),
        ("worker_arrival", "worker_arrival"),
    ):
        if key in settings and getattr(args, attr) == build_parser().get_default(attr):
            setattr(args, attr, settings[key])


def run(args: argparse.Namespace) -> int:
    if args.list:
        print_fault_catalog()
        return 0

    selection_settings = read_selection_file(args.selection_file)
    apply_selection_file(args, selection_settings)

    template_config = optional_path(args.template_config, "template-config")
    template_worker = optional_path(args.template_worker, "template-worker")
    template_suites = optional_path(args.template_suites, "template-suites")

    selected = resolve_selection(args.select, args.interactive)
    config_tree = load_or_create_config_tree(template_config, args.db_type, args.worker_include_href)
    config_root = config_tree.getroot()
    if config_root.tag != "parameters":
        raise SystemExit(f"ERROR: config XML root must be <parameters>, got <{config_root.tag}>.")

    db_type = clean_db_type(args.db_type or config_root.findtext("type"))
    agents = args.agent or extract_template_agents(config_root)

    replace_fault_cases(
        config_tree=config_tree,
        specs=FAULT_SPECS,
        agents=agents,
        java_cmd=args.java_cmd,
        jar_path=args.jar_path,
        db_type=db_type,
        include_db_overrides=not args.no_db_overrides,
        worker_include_href=args.worker_include_href,
    )

    worker_tree = build_worker_tree(
        template_worker=template_worker,
        suite_name=args.suite_name,
        selected=selected,
        planning_start_sec=int(args.planning_start_sec),
        planning_step_sec=int(args.planning_step_sec),
        during_sec=int(args.during_sec),
        worker_time=str(args.worker_time),
        worker_rate=args.worker_rate,
        worker_weights=args.worker_weights,
        worker_arrival=args.worker_arrival,
    )

    suites_tree = build_suites_tree(
        template_suites=template_suites,
        suite_name=args.suite_name,
        selected=selected,
        planning_start_sec=int(args.planning_start_sec),
        planning_step_sec=int(args.planning_step_sec),
        during_sec=int(args.during_sec),
    )

    output_dir = args.output_dir.resolve()
    config_out = output_dir / args.output_config
    worker_out = output_dir / args.output_worker
    suites_out = output_dir / args.output_suites

    write_xml(config_tree, config_out)
    write_xml(worker_tree, worker_out)
    write_xml(suites_tree, suites_out)

    print(f"Wrote config: {config_out}")
    print(f"Wrote worker: {worker_out}")
    print(f"Wrote suites: {suites_out}")
    print(f"Selected suite: {args.suite_name} ({len(selected)} cases)")
    for spec in selected:
        print(f"  - {spec.id}: {spec.key}")
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
