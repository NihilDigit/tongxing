# /// script
# requires-python = ">=3.11"
# ///
"""Prepare selected model artifacts using the checked-in SHA-256 manifest."""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import urllib.request
from pathlib import Path

from prepare_flatc import prepare as prepare_flatc


def checksum(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare(
    root: Path, groups: list[str], source: Path | None, flatc: Path | None = None
) -> None:
    manifest = json.loads((root / "models.lock.json").read_text(encoding="utf-8"))
    artifacts = {item["name"]: item for item in manifest["artifacts"]}

    def prepare_artifact(artifact: dict) -> Path:
        destination = (root / artifact["destination"]).resolve()
        if not destination.is_relative_to(root):
            raise ValueError("Model destination is outside the project")
        expected = artifact["sha256"]
        if destination.exists():
            if checksum(destination) != expected:
                raise ValueError(f"Existing model differs from manifest: {destination}")
            print(f"Verified {artifact['name']}")
            return destination
        candidate = (source / artifact["name"]) if source else None
        if candidate is not None and not candidate.is_file():
            candidate = source / Path(artifact["name"]).name
        if candidate is not None and not candidate.is_file():
            candidate = None
        url = artifact.get("url")
        recipe = artifact.get("recipe")
        if candidate is None and url is None and recipe is None:
            raise FileNotFoundError(
                f"Supply {artifact['name']} via --source-dir; see models.lock.json"
            )
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(
                dir=destination.parent, delete=False
            ) as output:
                temporary = Path(output.name)
                if candidate is not None:
                    with candidate.open("rb") as input_file:
                        shutil.copyfileobj(input_file, output)
                elif url is not None:
                    with urllib.request.urlopen(url, timeout=120) as response:
                        shutil.copyfileobj(response, output)
            if candidate is None and recipe is not None:
                if recipe != "mobilenet-features-v1":
                    raise ValueError(f"Unknown model recipe: {recipe}")
                source_model = prepare_artifact(artifacts[artifact["source_artifact"]])
                compiler = flatc or prepare_flatc(root)
                subprocess.run(
                    [
                        "uv",
                        "run",
                        str(Path(__file__).with_name("export_mobilenet_features.py")),
                        str(source_model),
                        str(temporary),
                        "--flatc",
                        str(compiler),
                    ],
                    check=True,
                )
            if (
                temporary.stat().st_size != artifact["size"]
                or checksum(temporary) != expected
            ):
                raise ValueError(f"Model checksum mismatch: {artifact['name']}")
            os.replace(temporary, destination)
            provenance = temporary.with_suffix(".provenance.json")
            if provenance.exists():
                os.replace(provenance, destination.with_suffix(".provenance.json"))
            print(f"Prepared {artifact['name']}")
            return destination
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
                temporary.with_suffix(".provenance.json").unlink(missing_ok=True)

    for artifact in manifest["artifacts"]:
        if artifact["group"] in groups:
            prepare_artifact(artifact)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--group",
        action="append",
        required=True,
        choices=["vision", "asr", "llm", "feature-source"],
    )
    parser.add_argument("--source-dir", type=Path)
    parser.add_argument("--flatc", type=Path, help="Use an installed flatc 25.2.10")
    args = parser.parse_args()
    prepare(
        Path(__file__).resolve().parents[1],
        args.group,
        args.source_dir.resolve() if args.source_dir else None,
        args.flatc.resolve() if args.flatc else None,
    )
