"""Run: python3 tools/check-skills-market.py (Linux/Termux)."""
import importlib.util
import tempfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("installer", Path(__file__).resolve().parents[1] / "app/src/main/assets/codeb-skills.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)

with tempfile.TemporaryDirectory() as temporary:
    home = Path(temporary)
    def call(action, **kwargs):
        return installer.run(dict(action=action, **kwargs), home)
    def rejected(action, **kwargs):
        try:
            call(action, **kwargs)
        except (ValueError, OSError):
            return
        raise AssertionError("Unsafe action accepted")
    content = "---\nname: codeb-check\ndescription: Check installation safely.\n---\n# Test\n"
    item = dict(id="codeb-check", version="1.0.0", content=content, sha256=installer.digest(content.encode()))
    assert call("list")["items"] == []
    rejected("install", **dict(item, id="../escape"))
    rejected("install", **dict(item, sha256="0" * 64))
    result = call("install", **item)
    path = Path(result["path"])
    assert path.read_text() == content and not result["modified"]
    rejected("install", **item)  # stale/unknown expected revision
    changed = content + "Updated\n"
    update = dict(item, version="1.1.0", content=changed, sha256=installer.digest(changed.encode()), expectedSha256=item["sha256"])
    call("install", **update)
    assert path.read_text() == changed
    rejected("install", **dict(item, expectedSha256=update["sha256"]))  # downgrade
    path.write_text("User edits")
    assert call("list")["items"][0]["modified"]
    rejected("remove", id=item["id"], expectedSha256=update["sha256"])
    rejected("install", **dict(update, expectedSha256=update["sha256"]))
    path.write_text(changed)
    extra = path.parent / "personal.txt"
    extra.write_text("Keep")
    rejected("remove", id=item["id"], expectedSha256=update["sha256"])
    extra.unlink()
    path.unlink()
    target = home / "outside.txt"
    target.write_text("Keep")
    path.symlink_to(target)
    rejected("install", **dict(update, expectedSha256=update["sha256"]))
    assert target.read_text() == "Keep"
    path.unlink()
    path.write_text(changed)
    call("remove", id=item["id"], expectedSha256=update["sha256"])
    assert call("list")["items"] == []
    skills = home / ".agents/skills"
    (skills / ".codeb-market.lock").unlink()
    skills.rmdir()
    skills.symlink_to(home, target_is_directory=True)
    rejected("install", **item)
print("Skills market: install/update/remove, checksums, conflicts, extra files, links and traversal OK")
