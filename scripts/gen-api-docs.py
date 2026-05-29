#!/usr/bin/env python3
"""Source-scraped API doc generator for the ARCP Java SDK.

Walks library modules (skipping examples, recipes, tests, generated sources),
extracts package declarations, top-level Javadoc comments and signatures for
public classes/interfaces/enums/records/methods, and emits one Markdown file
per package under docs/api/, plus a docs/api/index.md.

Output is intended to be ingested by the arpc.dev site, which globs
<lang>-sdk/docs/**/*.md at build time.
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "api"

LIBRARY_MODULES = [
    "arcp", "arcp-client", "arcp-core", "arcp-runtime", "arcp-runtime-jetty",
    "arcp-otel", "arcp-tck", "arcp-middleware-jakarta",
    "arcp-middleware-spring-boot", "arcp-middleware-vertx",
]

PKG_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
TYPE_RE = re.compile(
    r"public\s+(?:static\s+)?(?:final\s+|abstract\s+|sealed\s+|non-sealed\s+)?"
    r"(class|interface|enum|record|@interface)\s+(\w+)"
)
METHOD_RE = re.compile(
    r"public\s+(?:static\s+|final\s+|abstract\s+|default\s+|synchronized\s+|native\s+)*"
    r"(?:<[^>]+>\s+)?"
    r"([\w.<>\[\],\s?]+?)\s+(\w+)\s*\(([^)]*)\)"
)
KW = {"if", "for", "while", "switch", "catch", "return", "synchronized", "new"}
LABEL = {
    "class": ("class", "classes"),
    "interface": ("interface", "interfaces"),
    "enum": ("enum", "enums"),
    "record": ("record", "records"),
    "@interface": ("annotation type", "annotation types"),
}


@dataclass
class TypeInfo:
    name: str
    kind: str
    doc: str
    file: Path
    # Each method: (name, signature, doc)
    methods: list[tuple[str, str, str]] = field(default_factory=list)


def clean_javadoc(raw: str) -> str:
    """Strip leading ``*``, normalize whitespace, inline ``{@link/@code}``."""
    out = []
    for line in raw.splitlines():
        line = line.strip()
        if line.startswith("*"):
            line = line[1:].lstrip()
        out.append(line)
    text = re.sub(r"\n{3,}", "\n\n", "\n".join(out).strip())
    text = re.sub(r"\{@link(?:plain)?\s+([^}]+)\}",
                  lambda m: f"`{m.group(1).split()[0]}`", text)
    text = re.sub(r"\{@code\s+([^}]+)\}", r"`\1`", text)
    text = re.sub(r"\{@literal\s+([^}]+)\}", r"\1", text)
    return text


def preceding_doc(src: str, start: int) -> str:
    """Find the nearest /** ... */ block immediately before ``start``."""
    head = src[:start]
    m = re.search(r"/\*\*(.*?)\*/\s*(?:@\w+(?:\([^)]*\))?\s*)*\Z", head, re.DOTALL)
    return clean_javadoc(m.group(1)) if m else ""


def parse_file(path: Path) -> tuple[str, list[TypeInfo]]:
    src = path.read_text(encoding="utf-8", errors="replace")
    pkg_m = PKG_RE.search(src)
    if not pkg_m:
        return "", []
    pkg = pkg_m.group(1)

    types = [
        TypeInfo(name=m.group(2), kind=m.group(1),
                 doc=preceding_doc(src, m.start()), file=path)
        for m in TYPE_RE.finditer(src)
    ]
    if not types:
        return pkg, []

    # Each type's body range: opening { and matching close }.
    ranges: list[tuple[TypeInfo, int, int]] = []
    for ti in types:
        idx = src.find(ti.name, src.find(f" {ti.kind} "))
        brace = src.find("{", idx)
        if brace == -1:
            ranges.append((ti, -1, -1))
            continue
        depth = 0
        end = brace
        for i in range(brace, len(src)):
            c = src[i]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    end = i
                    break
        ranges.append((ti, brace, end))

    for m in METHOD_RE.finditer(src):
        ret, name, params = m.group(1).strip(), m.group(2), m.group(3).strip()
        if name in KW:
            continue
        owner = None
        for ti, b, e in ranges:
            if b <= m.start() <= e:
                owner = ti
        if owner is None:
            continue
        owner.methods.append(
            (name, f"public {ret} {name}({params})", preceding_doc(src, m.start()))
        )

    return pkg, types


def write_package_md(pkg: str, type_infos: list[TypeInfo]) -> Path:
    out_path = OUT / f"{pkg.replace('.', '/')}.md"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    by_kind: dict[str, list[TypeInfo]] = defaultdict(list)
    for ti in sorted(type_infos, key=lambda t: t.name):
        by_kind[ti.kind].append(ti)

    def fmt(k: str, n: int) -> str:
        sing, plur = LABEL.get(k, (k, k + "s"))
        return f"{n} {sing if n == 1 else plur}"

    lines = [
        f"# Package `{pkg}`",
        "",
        "_" + ", ".join(fmt(k, len(v)) for k, v in sorted(by_kind.items())) + "_",
        "",
        "| Type | Name | Summary |",
        "| --- | --- | --- |",
    ]
    for ti in sorted(type_infos, key=lambda t: t.name):
        first = ti.doc.split("\n\n", 1)[0].replace("\n", " ").strip() or "_(undocumented)_"
        lines.append(f"| {ti.kind} | [`{ti.name}`](#{ti.name.lower()}) | {first.replace('|', '\\|')} |")
    lines.append("")

    for ti in sorted(type_infos, key=lambda t: t.name):
        rel_src = ti.file.relative_to(ROOT)
        lines += [
            f"## {ti.name}", "",
            f"`{ti.kind} {pkg}.{ti.name}`", "",
            f"Source: `{rel_src}`", "",
        ]
        if ti.doc:
            lines += [ti.doc, ""]
        if ti.methods:
            lines += ["### Public methods", ""]
            for name, sig, doc in sorted(ti.methods):
                lines += [f"#### `{name}`", "", "```java", sig, "```", ""]
                if doc:
                    lines += [doc, ""]

    out_path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    return out_path


def clean_out() -> None:
    if not OUT.exists():
        return
    for p in sorted(OUT.rglob("*.md"), reverse=True):
        p.unlink()
    for d in sorted([p for p in OUT.rglob("*") if p.is_dir()], reverse=True):
        d.rmdir()


def main() -> int:
    clean_out()
    OUT.mkdir(parents=True, exist_ok=True)

    pkg_to_types: dict[str, list[TypeInfo]] = defaultdict(list)
    file_count = 0
    for module in LIBRARY_MODULES:
        src_root = ROOT / module / "src" / "main" / "java"
        if not src_root.exists():
            continue
        for jf in src_root.rglob("*.java"):
            if "/generated-sources/" in str(jf) or "/target/" in str(jf):
                continue
            pkg, types = parse_file(jf)
            if not pkg or not types:
                continue
            pkg_to_types[pkg].extend(types)
            file_count += 1

    for pkg, type_infos in sorted(pkg_to_types.items()):
        write_package_md(pkg, type_infos)

    total_types = sum(len(v) for v in pkg_to_types.values())
    lines = [
        "# Java SDK API reference",
        "",
        "Source-scraped API index for the ARCP Java SDK. Each package below",
        "links to a Markdown file listing its public types and methods with",
        "their Javadoc summaries.",
        "",
        f"_{len(pkg_to_types)} packages, {total_types} types, scraped from {file_count} source files._",
        "",
        "## Packages",
        "",
    ]
    for pkg in sorted(pkg_to_types):
        ntypes = len(pkg_to_types[pkg])
        noun = "type" if ntypes == 1 else "types"
        lines.append(f"- [`{pkg}`]({pkg.replace('.', '/')}.md) — {ntypes} {noun}")
    lines.append("")
    (OUT / "index.md").write_text("\n".join(lines), encoding="utf-8")

    print(f"Wrote {len(pkg_to_types) + 1} Markdown files under {OUT.relative_to(ROOT)}/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
