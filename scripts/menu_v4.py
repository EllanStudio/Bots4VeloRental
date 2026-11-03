import re, sys, yaml

"""Rental menus in the same visual language as the 艾尔岚酿造志 / 配方书 menus:
gold labels, yellow values and hints, gray descriptions, dark_gray separators and brackets, white names,
green = available / success, red = warning / destructive. State is shown after the name in dark_gray brackets."""

root = "/tmp/claude-0/-root/8c35e095-9614-46ab-b2f3-b0ef170c347f/scratchpad/botdev/"
OUT = root + "rental/zmenu/"
MAIN, HOURLY, BUY, MANAGE = "机器人租赁", "机器人临时租", "机器人长期挂机", "机器人套餐管理"
SEP = "<dark_gray><strikethrough>----------------------------</strikethrough>"
FILL = {"material": "BLACK_STAINED_GLASS_PANE", "name": " "}
COIN = "艾尔岚金币"


def L(*lines):
    return [SEP, *lines, SEP]


def it(material, name, lore=None):
    d = {"material": material, "name": name}
    if lore:
        d["lore"] = lore
    return d


def run(*commands, close=False):
    acts = [{"type": "player-command", "commands": list(commands)}]
    if close:
        acts.append({"type": "close"})
    return acts


def go(inventory):
    return [{"type": "inventory", "inventory": inventory}]


def req(slot_no, extra=None):
    r = [{"type": "placeholder", "placeholder": f"%botrental_slot_{slot_no}_status%", "action": "DIFFERENT_STRING", "value": v} for v in ("空闲", "加载中")]
    if extra:
        r.append({"type": "placeholder", "placeholder": f"%botrental_slot_{slot_no}_combat%", "action": "EQUALS_STRING", "value": "是"})
    return {"requirements": r}


def empty_head(label):
    return {"item": it("GRAY_STAINED_GLASS_PANE", f"<dark_gray>{label} <dark_gray>（<gray>空闲<dark_gray>）")}


titles = {MAIN: "<gold>艾尔岚挂机机器人", HOURLY: "<gold>临时租（按分钟）", BUY: "<gold>长期挂机套餐", MANAGE: "<gold>我的长期套餐"}


COLS = (0, 2, 4, 6, 8)
EMPTY = lambda label, hint: {"item": it("GRAY_STAINED_GLASS_PANE", f"<dark_gray>{label} <dark_gray>（<gray>空闲<dark_gray>）", L(f"<gray>{hint}"))}

# ===================================================================================== HUB (size 27)
main = {}
main["overview"] = {"slot": 4, "item": it("PLAYER_HEAD", "<gold>艾尔岚挂机机器人", L(
    "<gray>机器人会站在你当前所在的位置，替你加载区块。",
    "<gray>先选一种租法，进入后再选具体套餐。"))}
main["hourly"] = {"slot": 11, "item": it("CLOCK", "<gold>临时租 <dark_gray>（按分钟）", L(
    "<gold>适合：<yellow>短时间挂机，随时结束",
    f"<gray>费率：<gold>%botrental_online_rate% <gray>{COIN}/分钟（在线）",
    "<gray>先预存，用多少扣多少，剩余退还",
    "",
    "<gray>空位：<white>%botrental_pool_available%/%botrental_pool_size% <dark_gray>|<gray> 已租：<white>%botrental_active_count%/%botrental_max_per_player%",
    "<yellow>点击进入")), "actions": go(HOURLY)}
main["packages"] = {"slot": 15, "item": it("ENDER_EYE", "<gold>长期挂机套餐 <dark_gray>（按天）", L(
    "<gold>适合：<yellow>长期挂机，价格便宜得多",
    f"<gray>价格：<gold>%botrental_offer_small_1_price% <gray>{COIN}/天起，7 天收 6 天，30 天收 20 天",
    "<gray>可选战斗型：能拿武器自动打怪",
    "",
    "<gray>空位：<white>%botrental_pack_pool_available%/%botrental_pack_pool_size% <dark_gray>|<gray> 已买：<white>%botrental_pack_active_count%/%botrental_pack_max_per_player%",
    "<yellow>点击进入")), "actions": go(BUY)}
main["close"] = {"slot": 22, "item": it("BARRIER", "<red>关闭", L("<yellow>点击关闭")), "actions": [{"type": "close"}]}

# ===================================================================================== HOURLY (54)
hourly = {}
hourly["back"] = {"slot": 0, "item": it("ARROW", "<yellow>返回", L("<gray>回到机器人租赁首页")), "actions": go(MAIN)}
hourly["overview"] = {"slot": 4, "item": it("CLOCK", "<gold>临时租", L(
    f"<gold>费率：<yellow>在线 %botrental_online_rate%<gray>/分钟 <dark_gray>|<yellow> 离线 %botrental_offline_rate%<gray>/分钟",
    "<gray>先预存，用多少扣多少，结束退还剩余。",
    "<gray>机器人放在你现在站的位置。",
    "",
    "<gray>空位：<white>%botrental_pool_available%/%botrental_pool_size% <dark_gray>|<gray> 已租：<white>%botrental_active_count%/%botrental_max_per_player%"))}
hourly["to_packages"] = {"slot": 8, "item": it("ENDER_EYE", "<gold>长期套餐 <dark_gray>→", L("<gray>按天计费，更便宜", "<yellow>点击前往")), "actions": go(BUY)}
for slot, minutes, coins, mat in ((11, 30, 150, "LIME_DYE"), (13, 60, 300, "EMERALD"), (15, 120, 600, "EMERALD_BLOCK")):
    hourly[f"rent_{minutes}"] = {"slot": slot, "item": it(mat, f"<green>租用 {minutes} 分钟", L(
        f"<gray>预存：<gold>{coins} <gray>{COIN}", "<gray>机器人放在你现在站的位置", "<yellow>点击租用")),
        "actions": run(f"botrent rent {minutes}", close=True)}
for n, c in enumerate(COLS, start=1):
    hourly[f"slot_{n}"] = {"slot": 18 + c, "view-requirement": req(n), "item": it("PLAYER_HEAD", f"<gold>租赁位 {n} <dark_gray>（<white>%botrental_slot_{n}_status%<dark_gray>）", L(
        f"<gray>机器人：<white>%botrental_slot_{n}_bot%",
        f"<gray>余额：<gold>%botrental_slot_{n}_reserve% <gray>{COIN}",
        f"<gray>费率：<gold>%botrental_slot_{n}_rate% <gray>/分钟",
        f"<gray>还能挂：<yellow>%botrental_slot_{n}_minutes% 分钟",
        f"<gray>位置：<white>%botrental_slot_{n}_server% %botrental_slot_{n}_location%")),
        "else": EMPTY(f"租赁位 {n}", "点上方按钮租一个")}
    hourly[f"slot_{n}_relocate"] = {"slot": 27 + c, "view-requirement": req(n), "item": it("COMPASS", "<yellow>迁移到这里", L("<gray>把机器人移到你现在的位置", "<yellow>点击迁移")), "actions": run(f"botrent relocate {n}")}
    hourly[f"slot_{n}_topup"] = {"slot": 36 + c, "view-requirement": req(n), "item": it("GOLD_INGOT", f"<gold>充值 300 {COIN}", L("<gray>延长这个机器人的时间", "<yellow>点击充值")), "actions": run(f"botrent topup {n} 300")}
    hourly[f"slot_{n}_cancel"] = {"slot": 45 + c, "view-requirement": req(n), "item": it("BARRIER", "<red>结束租赁", L("<gray>没用完的余额会退还", "<yellow>点击结束")), "actions": run(f"botrent cancel {n}", close=True)}

# ===================================================================================== BUY (54)
buy = {}
buy["back"] = {"slot": 0, "item": it("ARROW", "<yellow>返回", L("<gray>回到机器人租赁首页")), "actions": go(MAIN)}
buy["overview"] = {"slot": 4, "item": it("ENDER_EYE", "<gold>长期挂机套餐", L(
    "<gold>空位：<yellow>%botrental_pack_pool_available%/%botrental_pack_pool_size% <dark_gray>|<gold> 已买：<yellow>%botrental_pack_active_count%/%botrental_pack_max_per_player%",
    "<gray>机器人站在你<white>当前所在的位置<gray>，一直加载周围区块。",
    "",
    "<white>•<gray> 机器人到达后才开始计时，停机时间自动顺延",
    "<white>•<gray> 7 天只收 6 天的钱，30 天只收 20 天的钱"))}
buy["to_manage"] = {"slot": 8, "item": it("CHEST", "<gold>我的套餐 <dark_gray>→", L("<gray>迁移、结束、给战斗机器人拿武器", "<yellow>点击管理")), "actions": go(MANAGE)}
buy["legend_plain"] = {"slot": 11, "item": it("LIME_STAINED_GLASS_PANE", "<green>普通套餐 <dark_gray>（左侧绿色按钮）", L("<gray>机器人站着挂机，保持区块加载。"))}
buy["legend_combat"] = {"slot": 15, "item": it("RED_STAINED_GLASS_PANE", "<red>战斗套餐 <dark_gray>（右侧红色按钮，价格 +50%）", L(
    "<gray>能拿着你给的武器，自动攻击附近的敌对怪物。", "<gray>不会攻击玩家、宠物、村民，也不会捡东西。"))}
buy["note"] = {"slot": 49, "item": it("BOOK", "<gold>购买说明", L(
    "<white>1.<gray> 站在想挂机的位置，点一个套餐",
    "<white>2.<gray> 聊天栏出现价格后，<green>点击 [点这里确认购买]<gray> 才会扣费",
    "<white>3.<gray> 已开始计时的取消，退还剩余整天的 <gold>80%<gray>",
    "<white>4.<gray> 机器人还没到达就取消，<gold>全额退还<gray>",
    "<white>5.<gray> 套餐不能充值，到期后重新购买"))}
for tier, base, mat, cn in (("small", 18, "COBBLESTONE", "小"), ("medium", 27, "IRON_BLOCK", "中"), ("large", 36, "DIAMOND_BLOCK", "大")):
    buy[f"tier_{tier}"] = {"slot": base, "item": it(mat, f"<gold>{cn}档 <dark_gray>（<white>%botrental_offer_{tier}_radius%<dark_gray>）", L(
        f"<gray>加载范围：<white>%botrental_offer_{tier}_radius%", "<gray>范围越大，能加载的农场越大"))}
    for i, (days, m1, m2) in enumerate(((1, "LIME_DYE", "IRON_SWORD"), (7, "EMERALD", "DIAMOND_SWORD"), (30, "EMERALD_BLOCK", "NETHERITE_SWORD"))):
        buy[f"{tier}_{days}"] = {"slot": base + 1 + i, "item": it(m1, f"<green>{days} 天 <dark_gray>（普通）", L(
            f"<gray>价格：<gold>%botrental_offer_{tier}_{days}_price% <gray>{COIN}", f"<gray>档位：<white>{cn} <dark_gray>|<gray> 天数：<white>{days}", "<yellow>点击购买")),
            "actions": run(f"botrent pack {tier} {days}", close=True)}
        buy[f"{tier}_{days}_combat"] = {"slot": base + 5 + i, "item": it(m2, f"<red>{days} 天 <dark_gray>（战斗）", L(
            f"<gray>价格：<gold>%botrental_offer_{tier}_{days}_combat_price% <gray>{COIN}", f"<gray>档位：<white>{cn} <dark_gray>|<gray> 天数：<white>{days}", "<yellow>点击购买")),
            "actions": run(f"botrent pack {tier} {days} combat", close=True)}

# ===================================================================================== MANAGE (54): packages are slots 6..10
manage = {}
manage["back"] = {"slot": 0, "item": it("ARROW", "<yellow>返回购买页", L("<gray>购买新的套餐")), "actions": go(BUY)}
manage["overview"] = {"slot": 4, "item": it("CHEST", "<gold>我的长期套餐", L(
    "<gold>已购买：<yellow>%botrental_pack_active_count%/%botrental_pack_max_per_player%", "<gray>每个套餐一列，在下面管理。"))}
manage["refresh"] = {"slot": 8, "item": it("SUNFLOWER", "<green>刷新", L("<gray>重新获取状态", "<yellow>点击刷新")), "actions": run("botrent status") + [{"type": "refresh inventory"}]}
for n, c in enumerate(COLS, start=6):
    manage[f"pack_{n}"] = {"slot": 9 + c, "view-requirement": req(n), "item": it("PLAYER_HEAD", f"<gold>套餐 {n - 5} <dark_gray>（<white>%botrental_slot_{n}_status%<dark_gray>）", L(
        f"<gray>类型：<white>%botrental_slot_{n}_tier%档 %botrental_slot_{n}_plan%",
        f"<gray>机器人：<white>%botrental_slot_{n}_bot%",
        f"<gray>剩余：<yellow>%botrental_slot_{n}_remaining%",
        f"<gray>战斗型：<white>%botrental_slot_{n}_combat%",
        f"<gray>位置：<white>%botrental_slot_{n}_server% %botrental_slot_{n}_location%")),
        "else": EMPTY(f"套餐 {n - 5}", "到购买页选一个套餐")}
    manage[f"pack_{n}_relocate"] = {"slot": 18 + c, "view-requirement": req(n), "item": it("COMPASS", "<yellow>迁移到这里", L("<gray>把机器人移到你现在的位置", "<yellow>点击迁移")), "actions": run(f"botrent relocate {n}")}
    manage[f"pack_{n}_gear"] = {"slot": 27 + c, "view-requirement": req(n, extra=True), "item": it("IRON_SWORD", "<gold>交给机器人手持", L("<gray>先把物品拿在<white>主手<gray>再点击", "<gray>套餐结束会退还给你", "<yellow>点击交付")), "actions": run(f"botrent gear {n}")}
    manage[f"pack_{n}_gear_take"] = {"slot": 36 + c, "view-requirement": req(n, extra=True), "item": it("ENDER_CHEST", "<gold>取回手持物品", L("<gray>把机器人拿着的物品放回背包", "<yellow>点击取回")), "actions": run(f"botrent gear {n} take")}
    manage[f"pack_{n}_cancel"] = {"slot": 45 + c, "view-requirement": req(n), "item": it("BARRIER", "<red>结束套餐", L("<gray>已计时：退还剩余整天的 <gold>80%", "<gray>机器人还没到：<gold>全额退还", "<yellow>点击结束")), "actions": run(f"botrent cancel {n}", close=True)}


def page(name, items, size=54):
    slots = [v["slot"] for v in items.values()]
    dup = sorted({s for s in slots if slots.count(s) > 1})
    assert not dup, f"{name}: duplicate slots {dup}"
    assert all(0 <= s < size for s in slots), f"{name}: slot out of range"
    return {"name": titles[name], "size": size, "enable": True, "update-interval": 1000, "fillItem": dict(FILL), "items": items}


IMPLEMENTED = [
    r"pool_available|pool_size|active_count|max_per_player|online_rate|offline_rate",
    r"pack_pool_available|pack_pool_size|pack_max_per_player|pack_active_count",
    r"slot_([1-9]|10)_(status|bot|reserve|spent|rate|minutes|world|server|location|plan|tier|combat|remaining|days)",
    r"offer_[a-z]+_(name|radius)", r"offer_[a-z]+_\d+_price", r"offer_[a-z]+_\d+_combat_price",
]
ALLOWED_TAGS = {"gold", "yellow", "gray", "dark_gray", "white", "green", "red", "strikethrough", "bold"}
problems = []
for name, items, size in ((MAIN, main, 27), (HOURLY, hourly, 54), (BUY, buy, 54), (MANAGE, manage, 54)):
    text = yaml.safe_dump(page(name, items, size), allow_unicode=True, sort_keys=False, width=1000, default_flow_style=False)
    for token in sorted(set(re.findall(r"%botrental_([a-z0-9_]+)%", text))):
        if not any(re.fullmatch(p, token) for p in IMPLEMENTED):
            problems.append((name, "placeholder", token))
    for tag in set(re.findall(r"</?([a-z_]+)>", text)):
        if tag not in ALLOWED_TAGS:
            problems.append((name, "tag", tag))
    open(OUT + name + ".yml", "w", encoding="utf-8").write("# Generated by scripts/menu_v4.py - edit the generator, not this file.\n" + text)
    yaml.safe_load(text)
    print(f"{name}: {len(items)} items")
if problems:
    sys.exit("problems: " + str(problems))
print("placeholders all implemented; only the server's own palette tags used; all pages parse")
