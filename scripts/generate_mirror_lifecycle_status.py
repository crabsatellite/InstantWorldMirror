#!/usr/bin/env python3
from __future__ import annotations

import argparse
import fnmatch
import json
import re
import sys
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
MANIFEST_PATH = PROJECT_ROOT / "mirror-lifecycle" / "mirror-lifecycle-map.json"
STATUS_DIR = PROJECT_ROOT / "mirror-lifecycle-status"

OUTPUT_FILES = {
    "raw": STATUS_DIR / "raw.json",
    "report": STATUS_DIR / "report-index.md",
    "route": STATUS_DIR / "route-index.md",
    "matrix_json": STATUS_DIR / "coverage-matrix.json",
    "matrix_md": STATUS_DIR / "coverage-matrix.md",
    "tasks": STATUS_DIR / "tasks.md",
    "completion_json": STATUS_DIR / "completion.json",
    "completion_md": STATUS_DIR / "completion.md",
}


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def detect_minecraft_version(root: Path) -> str:
    props = root / "gradle.properties"
    for line in props.read_text(encoding="utf-8").splitlines():
        if line.startswith("minecraft_version="):
            return line.split("=", 1)[1].strip()
    return "unknown"


def applies_to_version(entry: dict[str, Any], version: str) -> bool:
    versions = entry.get("versions")
    return not versions or "all" in versions or version in versions


def read_text(root: Path, relative: str) -> str:
    return (root / relative).read_text(encoding="utf-8", errors="replace")


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
        semicolon_index = source.find(";", match.end(), brace_index)
        if semicolon_index >= 0:
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


def json_path_value(document: Any, path: str) -> Any:
    value = document
    for part in path.split("."):
        if not isinstance(value, dict) or part not in value:
            raise KeyError(path)
        value = value[part]
    return value


def evaluate_leaf(root: Path, version: str, check: dict[str, Any]) -> dict[str, Any]:
    adapter = check["adapter"]
    if not applies_to_version(check, version):
        return {
            "passed": True,
            "skipped": True,
            "message": f"skipped for {version}",
        }

    try:
        if adapter == "file_contains":
            text = read_text(root, check["file"])
            needle = check["text"]
            passed = needle in text
            return {
                "passed": passed,
                "message": "found text" if passed else f"missing text in {check['file']}",
            }
        if adapter == "file_contains_all":
            text = read_text(root, check["file"])
            needles = check["texts"]
            missing = [needle for needle in needles if needle not in text]
            return {
                "passed": not missing,
                "message": "all text found" if not missing else f"missing {missing} in {check['file']}",
            }
        if adapter == "file_contains_in_order":
            text = read_text(root, check["file"])
            needles = check["texts"]
            passed = contains_in_order(text, needles)
            return {
                "passed": passed,
                "message": "ordered text found" if passed else f"text order mismatch in {check['file']}",
            }
        if adapter == "file_not_contains":
            text = read_text(root, check["file"])
            needle = check["text"]
            passed = needle not in text
            return {
                "passed": passed,
                "message": "text absent" if passed else f"forbidden text in {check['file']}",
            }
        if adapter == "method_contains_all":
            text = read_text(root, check["file"])
            method = extract_method_text(text, check["method"], check.get("signatureContains"))
            needles = check["texts"]
            missing = [needle for needle in needles if needle not in method]
            return {
                "passed": not missing,
                "message": "method contains all text" if not missing else f"method {check['method']} missing {missing}",
            }
        if adapter == "method_contains_in_order":
            text = read_text(root, check["file"])
            method = extract_method_text(text, check["method"], check.get("signatureContains"))
            needles = check["texts"]
            passed = contains_in_order(method, needles)
            return {
                "passed": passed,
                "message": "method contains ordered text" if passed else f"method {check['method']} order mismatch",
            }
        if adapter == "method_contains_none":
            text = read_text(root, check["file"])
            method = extract_method_text(text, check["method"], check.get("signatureContains"))
            forbidden = [needle for needle in check["texts"] if needle in method]
            return {
                "passed": not forbidden,
                "message": "method forbidden text absent" if not forbidden else f"method {check['method']} contains {forbidden}",
            }
        if adapter == "file_exists":
            passed = (root / check["file"]).is_file()
            return {
                "passed": passed,
                "message": "file exists" if passed else f"missing file {check['file']}",
            }
        if adapter == "json_parse":
            read_json(root / check["file"])
            return {"passed": True, "message": "json parsed"}
        if adapter == "glob_count":
            matches = sorted(
                path for path in root.rglob("*")
                if fnmatch.fnmatch(path.relative_to(root).as_posix(), check["glob"])
            )
            expected = int(check["count"])
            passed = len(matches) == expected
            return {
                "passed": passed,
                "message": f"{len(matches)} matched, expected {expected}",
                "matches": [path.relative_to(root).as_posix() for path in matches],
            }
        if adapter == "glob_json_values_equal":
            matches = sorted(
                path for path in root.rglob("*")
                if fnmatch.fnmatch(path.relative_to(root).as_posix(), check["glob"])
            )
            if "count" in check and len(matches) != int(check["count"]):
                return {
                    "passed": False,
                    "message": f"{len(matches)} matched, expected {check['count']}",
                    "matches": [path.relative_to(root).as_posix() for path in matches],
                }

            failures = []
            for path in matches:
                parsed = read_json(path)
                for json_path, expected in check["values"].items():
                    actual = json_path_value(parsed, json_path)
                    if actual != expected:
                        failures.append(
                            f"{path.relative_to(root).as_posix()} {json_path}={actual!r}, expected {expected!r}"
                        )
            return {
                "passed": not failures,
                "message": "all json values matched" if not failures else "; ".join(failures),
                "matches": [path.relative_to(root).as_posix() for path in matches],
            }
    except Exception as exc:  # noqa: BLE001
        return {"passed": False, "message": str(exc)}

    return {"passed": False, "message": f"unknown adapter {adapter}"}


def evaluate_check(root: Path, version: str, check: dict[str, Any]) -> dict[str, Any]:
    adapter = check["adapter"]
    if adapter in {"any", "all"}:
        children = [evaluate_leaf(root, version, child) for child in check["checks"]]
        active = [child for child in children if not child.get("skipped")]
        if adapter == "any":
            passed = any(child["passed"] for child in active) if active else True
        else:
            passed = all(child["passed"] for child in active)
        return {
            "id": check["id"],
            "adapter": adapter,
            "passed": passed,
            "children": children,
            "message": "composite passed" if passed else "composite failed",
        }

    result = evaluate_leaf(root, version, check)
    return {
        "id": check["id"],
        "adapter": adapter,
        **result,
    }


def status_for_checks(check_ids: list[str], results: dict[str, dict[str, Any]]) -> str:
    if not check_ids:
        return "blocked"
    if all(results[check_id]["passed"] for check_id in check_ids):
        return "covered"
    if any(results[check_id]["passed"] for check_id in check_ids):
        return "partial"
    return "planned"


def build_snapshot(root: Path, manifest_path: Path) -> dict[str, Any]:
    manifest = read_json(manifest_path)
    version = detect_minecraft_version(root)
    results = {
        check["id"]: evaluate_check(root, version, check)
        for check in manifest["checks"]
    }

    routes = []
    for route in manifest["routes"]:
        stages = []
        for stage in route["stages"]:
            check_ids = stage["checks"]
            stages.append({
                "id": stage["id"],
                "title": stage["title"],
                "checks": check_ids,
                "status": status_for_checks(check_ids, results),
            })
        status = "covered" if all(stage["status"] == "covered" for stage in stages) else "partial"
        routes.append({
            "id": route["id"],
            "title": route["title"],
            "status": status,
            "stages": stages,
        })

    failed_checks = [check_id for check_id, result in results.items() if not result["passed"]]
    completion = {
        "complete": not failed_checks and all(route["status"] == "covered" for route in routes),
        "failedChecks": failed_checks,
        "routeCount": len(routes),
        "coveredRouteCount": sum(1 for route in routes if route["status"] == "covered"),
        "checkCount": len(results),
        "passedCheckCount": sum(1 for result in results.values() if result["passed"]),
    }

    return {
        "schemaVersion": manifest["schemaVersion"],
        "project": manifest["project"],
        "minecraftVersion": version,
        "truthContract": manifest["truthContract"],
        "routes": routes,
        "checks": results,
        "completion": completion,
    }


def render_report(snapshot: dict[str, Any]) -> str:
    return "\n".join([
        "# Mirror lifecycle reports",
        "",
        f"- project: `{snapshot['project']}`",
        f"- minecraft version: `{snapshot['minecraftVersion']}`",
        f"- complete: `{str(snapshot['completion']['complete']).lower()}`",
        "",
        "Generated from `mirror-lifecycle/mirror-lifecycle-map.json`.",
        "",
        "## Reports",
        "",
        "- `raw.json`",
        "- `route-index.md`",
        "- `coverage-matrix.md` / `coverage-matrix.json`",
        "- `tasks.md`",
        "- `completion.md` / `completion.json`",
        "",
    ])


def render_route_index(snapshot: dict[str, Any]) -> str:
    lines = [
        "# Mirror lifecycle route index",
        "",
        f"Complete: `{str(snapshot['completion']['complete']).lower()}`",
        "",
    ]
    for route in snapshot["routes"]:
        lines.append(f"## {route['id']} - {route['status']}")
        lines.append("")
        lines.append(route["title"])
        lines.append("")
        for stage in route["stages"]:
            lines.append(f"- `{stage['id']}`: {stage['status']} - {stage['title']}")
        lines.append("")
    return "\n".join(lines)


def render_matrix(snapshot: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    rows = []
    for route in snapshot["routes"]:
        for stage in route["stages"]:
            rows.append({
                "route": route["id"],
                "stage": stage["id"],
                "status": stage["status"],
                "checks": stage["checks"],
            })

    lines = [
        "# Mirror lifecycle coverage matrix",
        "",
        "| Route | Stage | Status | Checks |",
        "| --- | --- | --- | --- |",
    ]
    for row in rows:
        checks = ", ".join(f"`{check}`" for check in row["checks"])
        lines.append(f"| `{row['route']}` | `{row['stage']}` | `{row['status']}` | {checks} |")
    lines.append("")
    return "\n".join(lines), {"rows": rows}


def render_tasks(snapshot: dict[str, Any]) -> str:
    lines = ["# Mirror lifecycle generated tasks", ""]
    for check_id, result in sorted(snapshot["checks"].items()):
        mark = "x" if result["passed"] else " "
        lines.append(f"- [{mark}] `{check_id}` - {result['message']}")
    lines.append("")
    return "\n".join(lines)


def render_completion(snapshot: dict[str, Any]) -> str:
    completion = snapshot["completion"]
    lines = [
        "# Mirror lifecycle completion",
        "",
        f"- complete: `{str(completion['complete']).lower()}`",
        f"- routes: `{completion['coveredRouteCount']}/{completion['routeCount']}`",
        f"- checks: `{completion['passedCheckCount']}/{completion['checkCount']}`",
        "",
    ]
    if completion["failedChecks"]:
        lines.append("## Failed Checks")
        lines.append("")
        for check_id in completion["failedChecks"]:
            lines.append(f"- `{check_id}`")
        lines.append("")
    return "\n".join(lines)


def generate_outputs(root: Path = PROJECT_ROOT, manifest_path: Path = MANIFEST_PATH) -> dict[Path, str]:
    snapshot = build_snapshot(root, manifest_path)
    matrix_md, matrix_json = render_matrix(snapshot)
    return {
        OUTPUT_FILES["raw"]: json.dumps(snapshot, indent=2, sort_keys=True) + "\n",
        OUTPUT_FILES["report"]: render_report(snapshot),
        OUTPUT_FILES["route"]: render_route_index(snapshot),
        OUTPUT_FILES["matrix_json"]: json.dumps(matrix_json, indent=2, sort_keys=True) + "\n",
        OUTPUT_FILES["matrix_md"]: matrix_md,
        OUTPUT_FILES["tasks"]: render_tasks(snapshot),
        OUTPUT_FILES["completion_json"]: json.dumps(snapshot["completion"], indent=2, sort_keys=True) + "\n",
        OUTPUT_FILES["completion_md"]: render_completion(snapshot),
    }


def write_outputs(outputs: dict[Path, str]) -> None:
    STATUS_DIR.mkdir(parents=True, exist_ok=True)
    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")


def check_outputs(outputs: dict[Path, str]) -> list[str]:
    stale = []
    for path, expected in outputs.items():
        if not path.is_file():
            stale.append(f"missing {path.relative_to(PROJECT_ROOT).as_posix()}")
            continue
        actual = path.read_text(encoding="utf-8")
        if actual != expected:
            stale.append(f"stale {path.relative_to(PROJECT_ROOT).as_posix()}")
    return stale


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--fail-incomplete", action="store_true")
    args = parser.parse_args()

    outputs = generate_outputs()
    snapshot = json.loads(outputs[OUTPUT_FILES["raw"]])

    if args.check:
        stale = check_outputs(outputs)
        if stale:
            for item in stale:
                print(item)
            return 1
    else:
        write_outputs(outputs)

    if args.fail_incomplete and not snapshot["completion"]["complete"]:
        for check_id in snapshot["completion"]["failedChecks"]:
            print(f"failed check: {check_id}")
        return 2

    return 0


if __name__ == "__main__":
    sys.exit(main())
