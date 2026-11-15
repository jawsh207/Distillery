#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fails when a source file is missing its description or license header.

Every source file in Distillery says, in plain words, what it is and why it
exists (CONTRIBUTING.md). This keeps that true for new files:

  * Kotlin (app/src): an SPDX-License-Identifier line and a /** ... */ comment.
  * Python (tools/): an SPDX line and a module docstring.
  * Shell (tools/): an SPDX line and a comment describing the script.

CI runs it on every push; run it yourself before committing:

    python3 tools/check-docs.py
"""
import ast
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SPDX = "SPDX-License-Identifier: Apache-2.0"


def problems():
    for path in sorted((REPO / "app/src").rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        if SPDX not in text[:300]:
            yield path, "no SPDX-License-Identifier: Apache-2.0 header"
        if "/**" not in text:
            yield path, "no /** … */ description"
    for path in sorted((REPO / "tools").rglob("*.py")):
        text = path.read_text(encoding="utf-8")
        if SPDX not in text[:300]:
            yield path, "no SPDX-License-Identifier: Apache-2.0 header"
        if not ast.get_docstring(ast.parse(text)):
            yield path, "no module docstring"
    for path in sorted((REPO / "tools").rglob("*.sh")):
        lines = path.read_text(encoding="utf-8").splitlines()
        if not any(SPDX in line for line in lines[:5]):
            yield path, "no SPDX-License-Identifier: Apache-2.0 header"
        described = [l for l in lines[1:15] if l.startswith("#") and "SPDX" not in l and l.strip("# ")]
        if not described:
            yield path, "no comment describing the script"


def main():
    found = list(problems())
    for path, why in found:
        print(f"{path.relative_to(REPO)}: {why}")
    if found:
        print(f"\n{len(found)} file(s) need a description; see CONTRIBUTING.md.", file=sys.stderr)
        return 1
    print("Every source file has its description and license header.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
