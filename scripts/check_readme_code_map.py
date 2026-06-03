#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MAP = PROJECT_ROOT / "docs" / "readme-code-map.json"


def read_text(root: Path, relative_path: str) -> str:
    return (root / relative_path).read_text(encoding="utf-8", errors="replace")


def contains_in_order(haystack: str, needles: list[str]) -> bool:
    cursor = 0
    for needle in needles:
        index = haystack.find(needle, cursor)
        if index < 0:
            return False
        cursor = index + len(needle)
    return True


def extract_method_text(source: str, method_name: str, signature_contains: list[str] | None = None) -> str:
    signature_contains = signature_contains or []
    declaration = re.compile(
        r"\b(?:public|protected|private)\s+"
        r"(?:(?:static|final|synchronized)\s+)*"
        r"[\w<>\[\], ?.@]+\s+"
        + re.escape(method_name)
        + r"\s*\(",
        re.MULTILINE,
    )

    for match in declaration.finditer(source):
        brace_index = source.find("{", match.end())
        if brace_index < 0:
            continue
        signature = source[match.start():brace_index]
        if not all(needle in signature for needle in signature_contains):
            continue

        depth = 0
        for index in range(brace_index, len(source)):
            char = source[index]
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    return source[match.start():index + 1]

    qualifier = f" with signature containing {signature_contains}" if signature_contains else ""
    raise ValueError(f"method {method_name}{qualifier} not found")


def evaluate_anchor(root: Path, anchor: dict[str, Any]) -> list[str]:
    adapter = anchor["adapter"]
    label = anchor.get("label", anchor.get("file", adapter))

    try:
        if adapter == "file_contains_all":
            text = read_text(root, anchor["file"])
            missing = [needle for needle in anchor["texts"] if needle not in text]
            return [f"{label}: missing {missing}"] if missing else []

        if adapter == "file_contains_in_order":
            text = read_text(root, anchor["file"])
            return [] if contains_in_order(text, anchor["texts"]) else [f"{label}: text order mismatch"]

        if adapter == "method_contains_all":
            text = read_text(root, anchor["file"])
            method = extract_method_text(text, anchor["method"], anchor.get("signatureContains"))
            missing = [needle for needle in anchor["texts"] if needle not in method]
            return [f"{label}: method {anchor['method']} missing {missing}"] if missing else []

        if adapter == "method_contains_in_order":
            text = read_text(root, anchor["file"])
            method = extract_method_text(text, anchor["method"], anchor.get("signatureContains"))
            return [] if contains_in_order(method, anchor["texts"]) else [
                f"{label}: method {anchor['method']} order mismatch"
            ]

        if adapter == "glob_count":
            existing: list[str] = []
            for pattern in anchor["patterns"]:
                existing.extend(path.as_posix() for path in root.glob(pattern) if path.is_file())
            existing = sorted(existing)
            expected = int(anchor["count"])
            return [] if len(existing) == expected else [
                f"{label}: found {len(existing)} files, expected {expected}"
            ]

    except Exception as exc:  # noqa: BLE001
        return [f"{label}: {exc}"]

    return [f"{label}: unknown adapter {adapter}"]


def check_project(root: Path = PROJECT_ROOT, map_path: Path = DEFAULT_MAP) -> list[str]:
    manifest = json.loads(map_path.read_text(encoding="utf-8"))
    readme_path = manifest.get("readme", "README.md")
    readme_text = read_text(root, readme_path)
    failures: list[str] = []

    for feature in manifest["features"]:
        feature_id = feature["id"]
        for snippet in feature.get("readmeTexts", []):
            if snippet not in readme_text:
                failures.append(f"{feature_id}: README missing {snippet!r}")
        for anchor in feature.get("code", []):
            failures.extend(f"{feature_id}: {failure}" for failure in evaluate_anchor(root, anchor))

    return failures


def main() -> int:
    map_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_MAP
    failures = check_project(PROJECT_ROOT, map_path)
    if failures:
        print("README code map check failed:")
        for failure in failures:
            print(f"- {failure}")
        return 1
    print("README code map check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
