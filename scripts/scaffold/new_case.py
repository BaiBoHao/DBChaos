#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
TEMPLATE_FILE = PROJECT_ROOT / "templates" / "CaseInject.java.template"
INJECT_DIR = PROJECT_ROOT / "src" / "chaos" / "inject"
CASE_REGISTRY = PROJECT_ROOT / "resources" / "registry" / "cases.tsv"
GENERATOR_REGISTRY = PROJECT_ROOT / "resources" / "registry" / "generator_profiles.tsv"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Scaffold a new DBChaos case and append registry metadata.")
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


def append_tsv_row(path: Path, row: str) -> None:
    text = path.read_text(encoding="utf-8")
    if row.strip() in text:
        raise SystemExit(f"Registry row already exists in {path.name}: {row}")
    if not text.endswith("\n"):
        text += "\n"
    text += row + "\n"
    path.write_text(text, encoding="utf-8")


def main() -> int:
    args = build_parser().parse_args()

    target_file = INJECT_DIR / f"{args.class_name}.java"
    if target_file.exists():
        raise SystemExit(f"Target injector already exists: {target_file}")

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

    case_row = "\t".join(
        [
            args.subsystem,
            args.case_key,
            args.title,
            args.description,
            f"chaos.inject.{args.class_name}",
            args.example_args,
            args.default_mode,
            args.allowed_modes,
            args.aliases,
        ]
    )
    append_tsv_row(CASE_REGISTRY, case_row)

    if args.generator_id is not None:
        required = {
            "generator-key": args.generator_key,
            "generator-description": args.generator_description,
            "generator-category": args.generator_category,
            "generator-args": args.generator_args,
        }
        missing = [name for name, value in required.items() if not value]
        if missing:
            raise SystemExit("Missing generator fields: " + ", ".join(missing))

        generator_row = "\t".join(
            [
                str(args.generator_id),
                args.generator_key,
                args.subsystem,
                args.case_key,
                args.generator_description,
                args.generator_category,
                args.generator_args,
                str(args.generator_during_sec),
            ]
        )
        append_tsv_row(GENERATOR_REGISTRY, generator_row)

    print(f"Created injector: {target_file}")
    print(f"Updated registry: {CASE_REGISTRY}")
    if args.generator_id is not None:
        print(f"Updated generator registry: {GENERATOR_REGISTRY}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
