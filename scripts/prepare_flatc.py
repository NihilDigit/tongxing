# /// script
# requires-python = ">=3.11"
# ///
"""Fetch the pinned official FlatBuffers compiler used for feature export."""

import hashlib
import io
import json
import os
import platform
import urllib.request
import zipfile
from pathlib import Path


def prepare(root: Path) -> Path:
    manifest = json.loads(Path(__file__).with_name("flatc.lock.json").read_text())
    system, machine = platform.system(), platform.machine()
    if system == "Windows" and machine.lower() in {"amd64", "x86_64"}:
        machine = "AMD64"
    entry = manifest["platforms"].get(f"{system}/{machine}")
    if entry is None:
        raise RuntimeError(
            f"Install flatc {manifest['version']} and pass --flatc for {system}/{machine}"
        )
    executable = (
        root / "build" / "tools" / "flatc" / manifest["version"] / entry["member"]
    )
    if executable.exists():
        if (
            hashlib.sha256(executable.read_bytes()).hexdigest()
            != entry["binary_sha256"]
        ):
            raise ValueError("Cached flatc checksum mismatch")
        return executable
    with urllib.request.urlopen(entry["url"], timeout=120) as response:
        archive = response.read()
    if hashlib.sha256(archive).hexdigest() != entry["archive_sha256"]:
        raise ValueError("flatc archive checksum mismatch")
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        binary = bundle.read(entry["member"])
    if hashlib.sha256(binary).hexdigest() != entry["binary_sha256"]:
        raise ValueError("flatc executable checksum mismatch")
    executable.parent.mkdir(parents=True, exist_ok=True)
    temporary = executable.with_suffix(".download")
    temporary.write_bytes(binary)
    temporary.chmod(0o755)
    os.replace(temporary, executable)
    return executable


if __name__ == "__main__":
    print(prepare(Path(__file__).resolve().parents[1]))
