#!/usr/bin/env python3
"""Static sanity check for the Home Assistant blueprint (used by CI).

Checks that the YAML parses (with HA's !input tag), that every !input refers to a declared
input (inputs may be grouped into sections), and that every declared input is used.
A runtime test against a real Home Assistant is described in README.md ("Testing").
"""
import sys

import yaml


class Input(str):
    pass


def input_constructor(loader, node):
    return Input(loader.construct_scalar(node))


yaml.SafeLoader.add_constructor("!input", input_constructor)


def declared_inputs(inputs):
    names = {}
    for key, spec in (inputs or {}).items():
        if isinstance(spec, dict) and "input" in spec and isinstance(spec["input"], dict):
            names.update(declared_inputs(spec["input"]))  # a section
        else:
            names[key] = spec or {}
    return names


def used_inputs(node, out):
    if isinstance(node, Input):
        out.add(str(node))
    elif isinstance(node, dict):
        for v in node.values():
            used_inputs(v, out)
    elif isinstance(node, list):
        for v in node:
            used_inputs(v, out)
    return out


def main(path):
    with open(path, encoding="utf-8") as fh:
        doc = yaml.safe_load(fh)
    errors = []
    bp = doc.get("blueprint") or {}
    for key in ("name", "domain", "input"):
        if key not in bp:
            errors.append(f"blueprint.{key} missing")
    declared = declared_inputs(bp.get("input"))
    body = {k: v for k, v in doc.items() if k != "blueprint"}
    used = used_inputs(body, set())
    for name in sorted(used - declared.keys()):
        errors.append(f"!input {name} is used but not declared")
    for name in sorted(declared.keys() - used):
        errors.append(f"input {name} is declared but never used")
    for name, spec in declared.items():
        if "selector" not in spec:
            errors.append(f"input {name} has no selector")
    for key in ("triggers", "actions"):
        if key not in body:
            errors.append(f"{key} missing")
    if errors:
        print("\n".join(f"ERROR: {e}" for e in errors))
        return 1
    print(f"OK: {path} ({len(declared)} inputs)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
