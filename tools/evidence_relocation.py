"""Optional relocation of benchmark evidence folders behind directory junctions.

A deployment may move benchmarks/runs or benchmarks/inputs to another drive and leave junctions at the same project
paths. Declare each relocation here (project-relative path -> target directory); tools then map resolved locations back
to project paths with `logical`, and link guards accept exactly these junctions. Empty by default.
"""
import os
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]
RELOCATED = {}
PER_ENTRY = ("benchmarks/runs",)


def declared_junction(path, root=None):
    """True for a project junction listed in RELOCATED (or a per-entry child) that points at its declared target."""
    root = Path(os.path.abspath(root or PROJECT))
    try:
        relative = Path(os.path.abspath(path)).relative_to(root).as_posix()
    except ValueError:
        return False
    if not os.path.isjunction(path):
        return False
    target = RELOCATED.get(relative)
    if target is None:
        parent, _, name = relative.rpartition("/")
        if parent in PER_ENTRY and parent in RELOCATED and name:
            target = Path(RELOCATED[parent]) / name
    return target is not None and os.path.normcase(os.path.realpath(path)) == os.path.normcase(os.path.realpath(target))


def logical(path, root=None):
    """Resolve a path, mapping a location under a declared relocation target back to its project path."""
    root = Path(root or PROJECT)
    physical = Path(path).resolve()
    for project_relative, target in RELOCATED.items():
        try:
            rest = physical.relative_to(Path(target).resolve())
        except ValueError:
            continue
        return root / project_relative / rest
    return physical
