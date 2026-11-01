# /// script
# requires-python = ">=3.11"
# ///
"""Prepare the exact llama.cpp Android SDK used by TongXing."""

import subprocess
from pathlib import Path

URL = "https://github.com/NihilDigit/llama.cpp"
REVISION = "219a99d74169bd26006669900af03e4f15b6d163"


def git(directory: Path, *arguments: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(directory), *arguments], text=True
    ).strip()


def prepare(root: Path) -> None:
    destination = root / "third_party" / "llama.cpp"
    if not destination.exists() or not any(destination.iterdir()):
        destination.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["git", "clone", "--no-checkout", URL, str(destination)], check=True
        )
        subprocess.run(
            ["git", "-C", str(destination), "checkout", "--detach", REVISION],
            check=True,
        )
    actual = git(destination, "rev-parse", "HEAD")
    if actual != REVISION:
        raise RuntimeError(f"Expected llama.cpp {REVISION}, found {actual}")
    if git(destination, "status", "--porcelain", "--untracked-files=no"):
        raise RuntimeError("llama.cpp has modified tracked files; review them first")
    sdk = destination / "examples" / "llama.android" / "lib" / "build.gradle.kts"
    if not sdk.is_file():
        raise RuntimeError("Pinned Android SDK source is missing")
    print(f"Verified llama.cpp {REVISION}")


if __name__ == "__main__":
    prepare(Path(__file__).resolve().parents[1])
