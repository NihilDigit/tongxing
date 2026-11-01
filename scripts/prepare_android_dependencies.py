# /// script
# requires-python = ">=3.11"
# ///
"""Fetch the pinned Sherpa AAR required by the Android app."""

import hashlib
import os
import tempfile
import urllib.request
from pathlib import Path

VERSION = "1.12.18"
NAME = f"sherpa-onnx-{VERSION}.aar"
URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/{NAME}"
SHA256 = "8f39642456f14ee471fed5e158d68e684dc68683da543e52e3a55c698b479a92"


def checksum(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare(destination: Path) -> None:
    if destination.exists():
        if checksum(destination) != SHA256:
            raise RuntimeError(f"Unexpected existing AAR checksum: {destination}")
        print(f"Verified {NAME}")
        return

    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            dir=destination.parent, prefix=f"{NAME}.", suffix=".part", delete=False
        ) as output:
            temporary = Path(output.name)
            with urllib.request.urlopen(URL, timeout=60) as response:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
        if checksum(temporary) != SHA256:
            raise RuntimeError("Downloaded Sherpa AAR failed SHA-256 verification")
        os.replace(temporary, destination)
        print(f"Downloaded and verified {NAME}")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    prepare(root / "app" / "libs" / NAME)
