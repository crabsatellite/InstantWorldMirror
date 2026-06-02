from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts import generate_mirror_lifecycle_status as generator


class MirrorLifecycleStatusGeneratorTest(unittest.TestCase):
    def test_file_contains_check_drives_completion(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "gradle.properties").write_text("minecraft_version=1.21.1\n", encoding="utf-8")
            source = root / "src" / "Example.java"
            source.parent.mkdir(parents=True)
            source.write_text("class Example { void covered() {} }\n", encoding="utf-8")

            manifest = root / "mirror-lifecycle" / "mirror-lifecycle-map.json"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(json.dumps({
                "schemaVersion": 1,
                "project": "Example",
                "truthContract": "test",
                "routes": [{
                    "id": "route",
                    "title": "Route",
                    "stages": [{
                        "id": "stage",
                        "title": "Stage",
                        "checks": ["example.contains"],
                    }],
                }],
                "checks": [{
                    "id": "example.contains",
                    "adapter": "file_contains",
                    "file": "src/Example.java",
                    "text": "covered",
                }],
            }), encoding="utf-8")

            snapshot = generator.build_snapshot(root, manifest)
            self.assertTrue(snapshot["completion"]["complete"])

            source.write_text("class Example {}\n", encoding="utf-8")
            snapshot = generator.build_snapshot(root, manifest)
            self.assertFalse(snapshot["completion"]["complete"])
            self.assertEqual(["example.contains"], snapshot["completion"]["failedChecks"])

    def test_method_order_and_scope_checks_drive_completion(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "gradle.properties").write_text("minecraft_version=1.21.1\n", encoding="utf-8")
            source = root / "src" / "Example.java"
            source.parent.mkdir(parents=True)
            source.write_text(
                "\n".join([
                    "class Example {",
                    "  public void unrelated() { first(); }",
                    "  public boolean route(boolean gate) {",
                    "    first();",
                    "    if (!gate) { return false; }",
                    "    second();",
                    "    return true;",
                    "  }",
                    "}",
                    "",
                ]),
                encoding="utf-8",
            )

            manifest = root / "mirror-lifecycle" / "mirror-lifecycle-map.json"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(json.dumps({
                "schemaVersion": 1,
                "project": "Example",
                "truthContract": "test",
                "routes": [{
                    "id": "route",
                    "title": "Route",
                    "stages": [{
                        "id": "stage",
                        "title": "Stage",
                        "checks": ["example.method_order", "example.method_forbidden"],
                    }],
                }],
                "checks": [
                    {
                        "id": "example.method_order",
                        "adapter": "method_contains_in_order",
                        "file": "src/Example.java",
                        "method": "route",
                        "signatureContains": ["boolean gate"],
                        "texts": ["first();", "if (!gate)", "second();"],
                    },
                    {
                        "id": "example.method_forbidden",
                        "adapter": "method_contains_none",
                        "file": "src/Example.java",
                        "method": "route",
                        "texts": ["unrelated"],
                    },
                ],
            }), encoding="utf-8")

            snapshot = generator.build_snapshot(root, manifest)
            self.assertTrue(snapshot["completion"]["complete"])

            source.write_text(source.read_text(encoding="utf-8").replace("second();", ""), encoding="utf-8")
            snapshot = generator.build_snapshot(root, manifest)
            self.assertFalse(snapshot["completion"]["complete"])
            self.assertEqual(["example.method_order"], snapshot["completion"]["failedChecks"])

    def test_glob_json_values_equal_checks_all_matches(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "gradle.properties").write_text("minecraft_version=1.21.1\n", encoding="utf-8")
            data_dir = root / "data"
            data_dir.mkdir()
            for index in range(2):
                (data_dir / f"dimension_{index}.json").write_text(json.dumps({
                    "type": "instantworldmirror:mirror_world",
                    "generator": {
                        "type": "instantworldmirror:mirror_chunk_generator",
                    },
                }), encoding="utf-8")

            manifest = root / "mirror-lifecycle" / "mirror-lifecycle-map.json"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(json.dumps({
                "schemaVersion": 1,
                "project": "Example",
                "truthContract": "test",
                "routes": [{
                    "id": "route",
                    "title": "Route",
                    "stages": [{
                        "id": "stage",
                        "title": "Stage",
                        "checks": ["example.dimensions"],
                    }],
                }],
                "checks": [{
                    "id": "example.dimensions",
                    "adapter": "glob_json_values_equal",
                    "glob": "data/dimension_*.json",
                    "count": 2,
                    "values": {
                        "type": "instantworldmirror:mirror_world",
                        "generator.type": "instantworldmirror:mirror_chunk_generator",
                    },
                }],
            }), encoding="utf-8")

            snapshot = generator.build_snapshot(root, manifest)
            self.assertTrue(snapshot["completion"]["complete"])

            (data_dir / "dimension_1.json").write_text(json.dumps({
                "type": "minecraft:overworld",
                "generator": {
                    "type": "instantworldmirror:mirror_chunk_generator",
                },
            }), encoding="utf-8")
            snapshot = generator.build_snapshot(root, manifest)
            self.assertFalse(snapshot["completion"]["complete"])
            self.assertEqual(["example.dimensions"], snapshot["completion"]["failedChecks"])


if __name__ == "__main__":
    unittest.main()
