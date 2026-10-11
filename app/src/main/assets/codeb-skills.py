"""CodeB text-only Skill installer. Python standard library; no install hooks."""
import fcntl
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile


def regular(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise ValueError("Refusing a link or non-regular file: " + path.name)


def directory(path, create=False):
    # Check every component before writing, including ~/.agents and the skills directory.
    for part in [*reversed(path.parents), path]:
        if not part.exists() and not part.is_symlink() and create:
            part.mkdir(mode=0o700)
        if part.is_symlink() or not part.is_dir():
            raise ValueError("Skill directory is missing or is a symbolic link")


def read(path):
    regular(path)
    with path.open("rb") as stream:
        value = stream.read(65537)
    if len(value) > 65536:
        raise ValueError("Skill file exceeds 64 KiB")
    return value


def digest(content):
    return hashlib.sha256(content).hexdigest()


def atomic(path, content):
    if path.exists() or path.is_symlink():
        regular(path)
    fd, temporary = tempfile.mkstemp(prefix=".codeb-", dir=str(path.parent))
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def installed(folder):
    try:
        directory(folder)
        receipt = json.loads(read(folder / ".codeb-market.json"))
        if receipt["id"] != folder.name or receipt.get("manager") != "codeb":
            raise ValueError("Not a CodeB installation")
        current = digest(read(folder / "SKILL.md"))
        return {"id": folder.name, "version": receipt["version"], "sha256": receipt["sha256"],
                "modified": current != receipt["sha256"], "path": str(folder / "SKILL.md")}
    except (OSError, ValueError, KeyError):
        return {"id": folder.name, "version": "", "sha256": "", "modified": True,
                "path": str(folder / "SKILL.md")}


def run(payload, home=None):
    action = payload.get("action")
    if action not in ("list", "install", "remove"):
        raise ValueError("Unknown skill action")
    root = Path(home or Path.home()).resolve(strict=True) / ".agents" / "skills"
    if action == "list" and not root.exists() and not root.is_symlink():
        return {"items": [], "root": str(root)}
    directory(root, create=action == "install")
    lock = root / ".codeb-market.lock"
    if lock.exists() or lock.is_symlink():
        regular(lock)
    # ponytail: one nonblocking lock serializes market changes; large catalogs can use per-skill locks.
    with os.fdopen(os.open(str(lock), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), "r+") as guard:
        fcntl.flock(guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if action == "list":
            return {"root": str(root), "items": [installed(p) for p in sorted(root.iterdir())
                if re.fullmatch(r"codeb-[a-z0-9]+(?:-[a-z0-9]+)*", p.name) and p.is_dir()]}
        skill_id = payload.get("id", "")
        if len(skill_id) > 63 or not re.fullmatch(r"codeb-[a-z0-9]+(?:-[a-z0-9]+)*", skill_id):
            raise ValueError("Invalid skill ID")
        folder = root / skill_id
        exists = folder.exists() or folder.is_symlink()
        before = installed(folder) if exists else None
        if before and (before["modified"] or before["sha256"] != payload.get("expectedSha256")):
            raise ValueError("Skill changed or is not managed by CodeB; refresh and preserve your edits")
        if action == "remove":
            if not exists:
                raise ValueError("Skill is no longer installed")
            if set(p.name for p in folder.iterdir()) != {"SKILL.md", ".codeb-market.json"}:
                raise ValueError("Skill contains additional files; manage them in Files")
            # Do not recursively remove directories or user-created resources.
            (folder / "SKILL.md").unlink()
            (folder / ".codeb-market.json").unlink()
            folder.rmdir()
            return {"removed": skill_id}
        content = payload.get("content", "").encode("utf-8")
        version = payload.get("version", "")
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
            raise ValueError("Invalid version")
        if not 1 <= len(content) <= 65536 or b"\0" in content or digest(content) != payload.get("sha256"):
            raise ValueError("Skill checksum or size is invalid")
        if not re.match(r"\A---\nname: " + re.escape(skill_id) + r"\ndescription: [^\n]+\n---\n", content.decode("utf-8")):
            raise ValueError("Invalid SKILL.md header")
        if before and tuple(map(int, version.split('.'))) < tuple(map(int, before["version"].split('.'))):
            raise ValueError("Refusing a version downgrade")
        if before and before["version"] == version and before["sha256"] != digest(content):
            raise ValueError("A changed skill needs a newer version")
        if not exists:
            folder.mkdir(mode=0o700)
        old_content = read(folder / "SKILL.md") if before else None
        receipt = {"manager": "codeb", "id": skill_id, "version": version, "sha256": digest(content)}
        try:
            atomic(folder / "SKILL.md", content)
            atomic(folder / ".codeb-market.json", json.dumps(receipt).encode("utf-8"))
        except Exception:
            if old_content is not None:
                atomic(folder / "SKILL.md", old_content)
            # An interrupted new install remains visibly unmanaged; never silently overwrite it.
            raise
        return installed(folder)


if __name__ == "__main__":
    try:
        encoded = sys.stdin.buffer.read(786433)
        if len(encoded) > 786432:
            raise ValueError("Skill request is too large")
        print(json.dumps(run(json.loads(base64.b64decode(encoded, validate=True))), ensure_ascii=False))
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
