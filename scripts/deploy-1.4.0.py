#!/usr/bin/env python3
"""Deploy Bots4Velo core 3.0.9 + Bots4VeloRental 1.4.0 (long-term packages, combat bots).

  deploy.py --staging DIR [--root /mnt/data/minecraft] [--backup-dir DIR]            # dry run: prints the plan only
  deploy.py --staging DIR ... --apply                                               # back up, then change files
  deploy.py --rollback BACKUP_DIR [--root ...]                                      # restore exactly what --apply touched

It never restarts anything. Every file it touches is copied to the backup directory first and listed in
manifest.json, which is what --rollback uses. Re-running --apply is safe: edits are skipped when already present.
"""
import argparse, hashlib, json, os, re, shutil, sys, time
import yaml

LONG_BOTS = range(11, 23)                       # BOT_11 .. BOT_22
BACKEND_SERVERS = ("redstone", "survival")
VELOCITY_CORE = "bots4velo-3.0.9.jar"
ADDON_JAR = "bots4velo-rental-addon-1.4.0.jar"
PAPER_JAR = "bots4velo-rental-paper-1.4.0.jar"
MENU_FILES = ("机器人租赁.yml", "机器人长期挂机.yml", "机器人套餐管理.yml")

PACKAGES_YAML = '''
# Long-term prepaid packages (1.4.0). The addon owns this price table; the paper bridges only display it.
packages:
  enabled: true
  pool:
''' + "".join(f'    - "BOT_{n}"\n' for n in LONG_BOTS) + '''  max-per-player: 3
  max-combat: 4
  allowed-servers: [redstone, survival]
  refund-percent: 80            # of the unused whole days when a running package is cancelled
  combat-surcharge-percent: 50
  durations:                    # days bought : days charged
    1: 1
    7: 6
    30: 20
  tiers:                        # load radius = simulation-distance chunks around the bot
    small:  {name: 小, simulation-distance: 3, view-distance: 4,  day-price: 450}
    medium: {name: 中, simulation-distance: 5, view-distance: 7,  day-price: 900}
    large:  {name: 大, simulation-distance: 8, view-distance: 10, day-price: 1800}
'''

COMBAT_YAML = '''
# Combat bots (long-term packages with the combat option): they hold an item handed over with
# /botrent gear <slot> and hit nearby hostile mobs. They never fight players, pets or villagers and
# never pick up items or experience.
combat:
  enabled: true
  interval-ticks: 4
  reach: 3.0
  line-of-sight: true
  exclude-types:
    - ENDERMAN
    - ZOMBIFIED_PIGLIN
    - PIGLIN
    - PIGLIN_BRUTE
    - WARDEN
'''


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read(path):
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


class Deployer:
    def __init__(self, root, staging, backup, apply):
        self.root, self.staging, self.backup, self.apply = root, staging, backup, apply
        self.manifest = []          # {"path", "existed", "backup"}
        self.log = []

    # ---------------------------------------------------------------- bookkeeping
    def note(self, text):
        self.log.append(text)
        print(("APPLY  " if self.apply else "PLAN   ") + text)

    def snapshot(self, path):
        """Remember the original state of `path` before it is changed (once)."""
        if any(m["path"] == path for m in self.manifest):
            return
        existed = os.path.lexists(path)
        entry = {"path": path, "existed": existed, "backup": None}
        if existed and self.apply:
            rel = os.path.relpath(path, self.root).replace(os.sep, "__")
            dest = os.path.join(self.backup, "files", rel)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            shutil.copy2(path, dest)
            entry["backup"] = dest
        self.manifest.append(entry)

    def write_text(self, path, text):
        self.snapshot(path)
        if self.apply:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            tmp = path + ".deploy-tmp"
            with open(tmp, "w", encoding="utf-8", newline="") as f:
                f.write(text)
            os.replace(tmp, path)

    def install_file(self, src, dst):
        self.snapshot(dst)
        if self.apply:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            tmp = dst + ".deploy-tmp"
            shutil.copy2(src, tmp)
            os.replace(tmp, dst)

    def remove_file(self, path):
        if not os.path.lexists(path):
            return
        self.snapshot(path)
        if self.apply:
            os.remove(path)

    # ---------------------------------------------------------------- steps
    def preflight(self):
        sums = os.path.join(self.staging, "SHA256SUMS")
        if not os.path.isfile(sums):
            sys.exit("staging has no SHA256SUMS")
        for line in read(sums).splitlines():
            if not line.strip():
                continue
            digest, name = line.split(None, 1)
            name = name.strip().lstrip("*")
            path = os.path.join(self.staging, name)
            if not os.path.isfile(path) or sha256(path) != digest:
                sys.exit(f"staged file missing or corrupted: {name}")
        for need in (VELOCITY_CORE, ADDON_JAR, PAPER_JAR) + MENU_FILES:
            if not os.path.isfile(os.path.join(self.staging, need)):
                sys.exit(f"staging is missing {need}")
        must = [os.path.join(self.root, "velocity/plugins/bots4velo/config.yml"),
                os.path.join(self.root, "velocity/plugins/bots4velo/addons/bot-rental/config.yml")]
        for s in BACKEND_SERVERS:
            must.append(os.path.join(self.root, s, "plugins/Bots4VeloRental/config.yml"))
            must.append(os.path.join(self.root, s, "plugins/zMenu/inventories", MENU_FILES[0]))
        for path in must:
            if not os.path.isfile(path):
                sys.exit(f"expected file not found: {path}")
        self.note(f"preflight ok: {len(read(sums).splitlines())} staged files verified, {len(must)} target files present")

    def velocity_bots(self):
        path = os.path.join(self.root, "velocity/plugins/bots4velo/config.yml")
        text = read(path)
        data = yaml.safe_load(text)
        existing = set(data["bots"])
        wanted = [f"BOT_{n}" for n in LONG_BOTS]
        missing = [b for b in wanted if b not in existing]
        if not missing:
            lowered = re.search(r"(?m)^  maximum-bots:\s*(\d+)", text)
            if lowered and int(lowered.group(1)) < 40:
                self.write_text(path, re.sub(r"(?m)^(  maximum-bots:\s*)\d+", r"\g<1>40", text))
                self.note("velocity bots4velo config: runtime.maximum-bots raised to 40")
            else:
                self.note("velocity bots4velo config: BOT_11..BOT_22 already defined, skipped")
            return
        if not re.search(r"^bots:\s*$", text, re.M) or re.search(r"^[A-Za-z][^\n]*:\s*$", text[text.index("\nbots:") + 6:], re.M):
            sys.exit("bots: is not the last top-level key of the bots4velo config; refusing to guess where to insert")
        m = re.search(r"^  BOT_1:\n(?:(?:    .*|)\n)*", text, re.M)
        if not m:
            sys.exit("could not find the BOT_1 block to clone")
        template = m.group(0)
        out = text if text.endswith("\n") else text + "\n"
        for name in missing:
            out += template.replace("BOT_1", name)
        # The core refuses to start when the number of defined bots exceeds runtime.maximum-bots (default there: 20).
        out = re.sub(r"(?m)^(  maximum-bots:\s*)(\d+)[ \t]*$", lambda m: m.group(1) + str(max(int(m.group(2)), 40)), out)
        parsed = yaml.safe_load(out)
        assert parsed["runtime"]["maximum-bots"] >= len(parsed["bots"]) + 10, "runtime.maximum-bots is too low for the defined bots"
        after = parsed["bots"]
        assert all(b in after for b in wanted), "cloned bots did not parse"
        users = [v["username"] for v in after.values()]
        assert len(users) == len(set(users)), "duplicate bot usernames after edit"
        assert all(after[b]["enabled"] is False for b in wanted), "long-term bots must be disabled (the addon starts them on demand)"
        self.write_text(path, out)
        self.note(f"velocity bots4velo config: added {len(missing)} bots (BOT_{LONG_BOTS[0]}..BOT_{LONG_BOTS[-1]}), cloned from BOT_1, enabled: false")

    def addon_config(self):
        path = os.path.join(self.root, "velocity/plugins/bots4velo/addons/bot-rental/config.yml")
        text = read(path)
        if re.search(r"^packages:", text, re.M):
            self.note("addon config: packages section already present, skipped")
            return
        out = (text if text.endswith("\n") else text + "\n") + PACKAGES_YAML
        data = yaml.safe_load(out)
        assert data["packages"]["enabled"] is True and len(data["packages"]["pool"]) == len(LONG_BOTS)
        assert set(data["packages"]["allowed-servers"]) <= set(data["bridge"]["servers"]), "allowed-servers needs a bridge"
        assert not set(data["packages"]["pool"]) & set(data["rental"]["pool"]), "pools overlap"
        self.write_text(path, out)
        self.note("addon config: appended packages section (3 tiers, 1/7/30 days, pool BOT_11..BOT_22, redstone+survival)")

    def paper_config(self, server):
        path = os.path.join(self.root, server, "plugins/Bots4VeloRental/config.yml")
        text = read(path)
        changed = []
        wanted = [f"BOT_{n}" for n in LONG_BOTS]
        have = yaml.safe_load(text).get("bot-usernames", [])
        if not all(b in have for b in wanted):
            m = re.search(r'^(\s*)-\s*"BOT_10"[ \t]*\r?\n', text, re.M)
            if not m:
                sys.exit(f"{server}: could not find the BOT_10 line to extend bot-usernames")
            indent = m.group(1)
            extra = "".join(f'{indent}- "{b}"\n' for b in wanted if b not in have)
            text = text[:m.end()] + extra + text[m.end():]
            changed.append("bot-usernames +12")
        if not re.search(r"^combat:", text, re.M):
            text = (text if text.endswith("\n") else text + "\n") + COMBAT_YAML
            changed.append("combat section")
        if not changed:
            self.note(f"{server} paper config: already up to date, skipped")
            return
        data = yaml.safe_load(text)
        assert len(data["bot-usernames"]) == len(set(data["bot-usernames"])) == 22, "bot-usernames should be 22 unique names"
        assert data["combat"]["enabled"] is True
        self.write_text(path, text)
        self.note(f"{server} paper config: " + ", ".join(changed))

    def jars(self):
        v = os.path.join(self.root, "velocity/plugins")
        for name in sorted(os.listdir(v)):
            if re.fullmatch(r"bots4velo-\d[\w.\-]*\.jar", name) and name != VELOCITY_CORE:
                self.remove_file(os.path.join(v, name))
                self.note(f"velocity: retire old core jar {name} (kept in backup)")
        self.install_file(os.path.join(self.staging, VELOCITY_CORE), os.path.join(v, VELOCITY_CORE))
        self.note(f"velocity: install core {VELOCITY_CORE}")
        a = os.path.join(v, "bots4velo/addons/bot-rental")
        for name in sorted(os.listdir(a)):
            if re.fullmatch(r"bots4velo-rental-addon-[\w.\-]+\.jar", name) and name != ADDON_JAR:
                self.remove_file(os.path.join(a, name))
                self.note(f"velocity: retire old addon jar {name} (kept in backup)")
        self.install_file(os.path.join(self.staging, ADDON_JAR), os.path.join(a, ADDON_JAR))
        self.note(f"velocity: install addon {ADDON_JAR}")
        for s in BACKEND_SERVERS:
            p = os.path.join(self.root, s, "plugins")
            for name in sorted(os.listdir(p)):
                if re.fullmatch(r"bots4velo-rental-paper-[\w.\-]+\.jar", name) and name != PAPER_JAR:
                    self.remove_file(os.path.join(p, name))
                    self.note(f"{s}: retire old bridge jar {name} (kept in backup)")
            self.install_file(os.path.join(self.staging, PAPER_JAR), os.path.join(p, PAPER_JAR))
            self.note(f"{s}: install bridge {PAPER_JAR}")

    def menus(self):
        for s in BACKEND_SERVERS:
            d = os.path.join(self.root, s, "plugins/zMenu/inventories")
            for name in MENU_FILES:
                src = os.path.join(self.staging, name)
                dst = os.path.join(d, name)
                if os.path.isfile(dst) and read(dst) == read(src):
                    self.note(f"{s}: menu {name} already current, skipped")
                    continue
                yaml.safe_load(read(src))
                self.install_file(src, dst)
                self.note(f"{s}: install menu {name}")

    def finish(self):
        if self.apply:
            os.makedirs(self.backup, exist_ok=True)
            with open(os.path.join(self.backup, "manifest.json"), "w", encoding="utf-8") as f:
                json.dump({"root": self.root, "entries": self.manifest, "created": time.strftime("%Y-%m-%d %H:%M:%S")}, f, ensure_ascii=False, indent=1)
            print(f"\nDone. Backup + manifest: {self.backup}\nRollback: deploy.py --rollback {self.backup} --root {self.root}")
        else:
            print(f"\nDry run only: {len(self.manifest)} files would be changed. Re-run with --apply.")


def rollback(backup, root_override):
    manifest = json.load(open(os.path.join(backup, "manifest.json"), encoding="utf-8"))
    root = root_override or manifest["root"]
    restored = removed = 0
    for entry in reversed(manifest["entries"]):
        path = entry["path"]
        if root_override and manifest["root"] != root_override:
            path = path.replace(manifest["root"], root_override, 1)
        if entry["existed"]:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            shutil.copy2(entry["backup"], path)
            restored += 1
        elif os.path.lexists(path):
            os.remove(path)
            removed += 1
    print(f"rolled back: {restored} files restored, {removed} newly created files removed")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--staging")
    ap.add_argument("--root", default="/mnt/data/minecraft")
    ap.add_argument("--backup-dir")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--rollback")
    ap.add_argument("--only", choices=("all", "menus"), default="all", help="menus: only (re)install the zMenu pages")
    args = ap.parse_args()
    if args.rollback:
        return rollback(args.rollback, args.root)
    if not args.staging:
        ap.error("--staging is required")
    backup = args.backup_dir or os.path.join(os.path.dirname(args.root.rstrip("/")), "minecraft-backups",
                                             "botrental-1.4.0-" + time.strftime("%Y%m%d-%H%M%S"))
    d = Deployer(os.path.abspath(args.root), os.path.abspath(args.staging), os.path.abspath(backup), args.apply)
    d.preflight()
    if args.only == "all":
        d.velocity_bots()
        d.addon_config()
        for s in BACKEND_SERVERS:
            d.paper_config(s)
        d.jars()
    d.menus()
    d.finish()


if __name__ == "__main__":
    main()
