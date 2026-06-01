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


if __name__ == "__main__":
    unittest.main()
