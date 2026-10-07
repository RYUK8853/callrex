#!/usr/bin/env python3
"""Merge translated <string> fragments into a locale's resource files.

Each key is routed to the same file it lives in under values/, and inserted in
sorted position, so the locale keeps the layout the other locales already have.
Existing keys are left untouched — this only fills gaps.

Usage, from the repo root:

    ./gradlew :app:checkTranslations          # lists exactly what each locale is missing
    python3 scripts/merge-translations.py de /tmp/add-de.xml
    ./gradlew :app:checkTranslations          # must go green

The fragment is a bare <resources> file holding only the new strings; it does
not need to name the target file, and the keys may be in any order.

Written for the 2026-07-29 backfill, where eight locales were 46 strings behind
and hand-editing eighteen files was the obvious way to lose one. It rewrites
whole files, so let git show you the diff before committing — verify the count
went up by what you expected and nothing vanished.

Caveat: only single-line <string> elements are recognised. A multi-line string
is left in place rather than sorted, which is safe but untidy; the locale files
had none when this was written.
"""
import re
import sys
from pathlib import Path

RES = Path("app/src/main/res")
STRING_RE = re.compile(r'^\s*<string name="([^"]+)".*?</string>\s*$', re.DOTALL)


def read_strings(path):
    """Return [(name, full_line)] for every single-line <string> in the file."""
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = STRING_RE.match(line)
        if m:
            out.append((m.group(1), line))
    return out


def key_to_base_file():
    """Map every base string name to the filename that defines it."""
    mapping = {}
    for f in sorted(RES.glob("values/*.xml")):
        for name, _ in read_strings(f):
            mapping[name] = f.name
    return mapping


def merge(locale_dir, fragment_path):
    routing = key_to_base_file()
    additions = read_strings(fragment_path)

    by_file = {}
    for name, line in additions:
        target = routing.get(name)
        if target is None:
            sys.exit(f"ERROR: {name} is not defined in the base locale")
        by_file.setdefault(target, []).append((name, line))

    for filename, entries in sorted(by_file.items()):
        path = locale_dir / filename
        if not path.exists():
            sys.exit(f"ERROR: {path} does not exist")
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines()

        existing = {n for n, _ in read_strings(path)}
        fresh = [(n, l) for n, l in entries if n not in existing]
        if not fresh:
            print(f"  {path}: nothing to add")
            continue

        close_at = next(i for i in range(len(lines) - 1, -1, -1)
                        if lines[i].strip() == "</resources>")
        body = lines[:close_at]
        tail = lines[close_at:]

        merged = body + [l for _, l in fresh]
        # Re-sort only the <string> lines, leaving any header/comment lines in place.
        head = [l for l in merged if not STRING_RE.match(l)]
        strings = sorted((l for l in merged if STRING_RE.match(l)),
                         key=lambda l: STRING_RE.match(l).group(1))
        path.write_text("\n".join(head + strings + tail) + "\n", encoding="utf-8")
        print(f"  {path}: +{len(fresh)}")


if __name__ == "__main__":
    merge(RES / f"values-{sys.argv[1]}", Path(sys.argv[2]))
