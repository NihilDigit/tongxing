# Contributing

Follow the build and test instructions in `README.md`. Keep changes focused on one behavior and include
the checks needed to reproduce it. Pull requests should describe the trigger,
resulting behavior, and validation environment.

Use the existing Kotlin, Gradle, CMake and C++ toolchains. Python preparation
tools run with uv; check them with Ruff. Native changes need ARM64 Android
validation. Changes to QNN inference need a compatible Snapdragon device.

Keep credentials, personal service URLs, recordings and model weights out of
commits. Use synthetic data or public fixtures in tests. Preserve upstream
copyright and license notices.

Comments should explain data semantics and non-obvious constraints. Record
unverified hypotheses and broader follow-up work in an issue or design note.

Contributions to original TongXing code are submitted under Apache-2.0.
Changes to third-party code follow that component's existing license.
