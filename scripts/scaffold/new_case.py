#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shlex
from pathlib import Path
from typing import Any, Dict, List


PROJECT_ROOT = Path(__file__).resolve().parents[2]
TEMPLATE_FILE = PROJECT_ROOT / "templates" / "CaseInject.java.template"
INJECT_DIR = PROJECT_ROOT / "src" / "chaos" / "inject"
REGISTRY_FILE = PROJECT_ROOT / "resources" / "registry" / "registry.json"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Scaffold a new DBChaos case and append JSON registry metadata.")
    parser.add_argument("--subsystem", required=True)
    parser.add_argument("--case", required=True, dest="case_key")
    parser.add_argument("--class-name", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--description", required=True)
    parser.add_argument("--example-args", default="-duration 60000")
    parser.add_argument("--default-mode", default="")
    parser.add_argument("--allowed-modes", default="")
    parser.add_argument("--aliases", default="")
    parser.add_argument("--generator-id", type=int)
    parser.add_argument("--generator-key", default="")
    parser.add_argument("--generator-description", default="")
    parser.add_argument("--generator-category", default="")
    parser.add_argument("--generator-args", default="")
    parser.add_argument("--generator-during-sec", type=int, default=60)
    return parser


def split_pipe(text: str) -> List[str]:
    return [item.strip() for item in text.split("|") if item.strip()]


def load_registry() -> Dict[str, Any]:
    return json.loads(REGISTRY_FILE.read_text(encoding="utf-8"))


def write_registry(data: Dict[str, Any]) -> None:
    REGISTRY_FILE.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def ensure_subsystem_exists(data: Dict[str, Any], subsystem: str) -> None:
    subsystem_keys = {item.get("key", "").strip().lower() for item in data.get("subsystems", [])}
    if subsystem.lower() not in subsystem_keys:
        raise SystemExit(f"Unknown subsystem: {subsystem}")


def ensure_case_not_exists(data: Dict[str, Any], subsystem: str, case_key: str) -> None:
    for case in data.get("cases", []):
        if (
            str(case.get("subsystem", "")).strip().lower() == subsystem.lower()
            and str(case.get("caseKey", "")).strip().lower() == case_key.lower()
        ):
            raise SystemExit(f"Case already exists in registry: {subsystem}/{case_key}")


def ensure_generator_not_exists(data: Dict[str, Any], generator_id: int, generator_key: str) -> None:
    for case in data.get("cases", []):
        for profile in case.get("generatorProfiles", []):
            if int(profile.get("id")) == generator_id:
                raise SystemExit(f"Generator id already exists: {generator_id}")
            if str(profile.get("key", "")).strip().lower() == generator_key.lower():
                raise SystemExit(f"Generator key already exists: {generator_key}")


def build_case_object(args: argparse.Namespace) -> Dict[str, Any]:
    case_object: Dict[str, Any] = {
        "subsystem": args.subsystem,
        "caseKey": args.case_key,
        "title": args.title,
        "description": args.description,
        "injectorClass": f"chaos.inject.{args.class_name}",
        "exampleArgs": args.example_args,
    }
    allowed_modes = split_pipe(args.allowed_modes)
    aliases = split_pipe(args.aliases)
    if args.default_mode:
        case_object["defaultMode"] = args.default_mode
    if allowed_modes:
        case_object["allowedModes"] = allowed_modes
    if aliases:
        case_object["aliases"] = aliases
    return case_object


def maybe_attach_generator_profile(case_object: Dict[str, Any], data: Dict[str, Any], args: argparse.Namespace) -> None:
    if args.generator_id is None:
        return

    required = {
        "generator-key": args.generator_key,
        "generator-description": args.generator_description,
        "generator-category": args.generator_category,
        "generator-args": args.generator_args,
    }
    missing = [name for name, value in required.items() if not value]
    if missing:
        raise SystemExit("Missing generator fields: " + ", ".join(missing))

    ensure_generator_not_exists(data, args.generator_id, args.generator_key)
    case_object["generatorProfiles"] = [
        {
            "id": args.generator_id,
            "key": args.generator_key,
            "description": args.generator_description,
            "category": args.generator_category,
            "args": shlex.split(args.generator_args),
            "duringSec": args.generator_during_sec,
        }
    ]


def main() -> int:
    args = build_parser().parse_args()

    target_file = INJECT_DIR / f"{args.class_name}.java"
    if target_file.exists():
        raise SystemExit(f"Target injector already exists: {target_file}")

    registry = load_registry()
    ensure_subsystem_exists(registry, args.subsystem)
    ensure_case_not_exists(registry, args.subsystem, args.case_key)

    template = TEMPLATE_FILE.read_text(encoding="utf-8")
    rendered = (
        template.replace("{{CLASS_NAME}}", args.class_name)
        .replace("{{CASE_TITLE}}", args.title)
        .replace("{{CASE_DESCRIPTION}}", args.description)
        .replace("{{CASE_KEY}}", args.case_key)
        .replace("{{CASE_KEY_UPPER}}", args.case_key.upper())
        .replace("{{SUBSYSTEM}}", args.subsystem)
        .replace("{{EXAMPLE_ARGS}}", args.example_args)
    )
    target_file.write_text(rendered, encoding="utf-8")

    case_object = build_case_object(args)
    maybe_attach_generator_profile(case_object, registry, args)
    registry.setdefault("cases", []).append(case_object)
    write_registry(registry)

    print(f"Created injector: {target_file}")
    print(f"Updated registry: {REGISTRY_FILE}")
    if args.generator_id is not None:
        print(f"Added generator profile: {args.generator_key}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
