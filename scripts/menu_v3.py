import re, sys, yaml

"""Rental menus in the same visual language as the 艾尔岚酿造志 / 配方书 menus:
gold labels, yellow values and hints, gray descriptions, dark_gray separators and brackets, white names,
green = available / success, red = warning / destructive. State is shown after the name in dark_gray brackets."""

root = "/tmp/claude-0/-root/8c35e095-9614-46ab-b2f3-b0ef170c347f/scratchpad/botdev/"
OUT = root + "rental/zmenu/"
MAIN, BUY, MANAGE = "机器人租赁", "机器人长期挂机", "机器人套餐管理"
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


titles = {MAIN: "<gold>艾尔岚挂机机器人", BUY: "<gold>长期挂机套餐", MANAGE: "<gold>我的长期套餐"}

# ===================================================================================== MAIN
main = {}
main["overview"] = {"slot": 4, "item": it("PLAYER_HEAD", "<gold>艾尔岚挂机机器人", L(
    "<gold>临时租：<yellow>按分钟计费，适合一小会儿",
    "<gold>长期套餐：<yellow>按天计费，便宜得多，适合长期挂机",
    "<gray>机器人会站在你当前所在的位置，替你加载区块。",
    "",
    "<gray>临时空位：<white>%botrental_pool_available%/%botrental_pool_size% <dark_gray>|<gray> 长期空位：<white>%botrental_pack_pool_available%/%botrental_pack_pool_size%"))}
main["refresh"] = {"slot": 8, "item": it("SUNFLOWER", "<green>刷新", L("<gray>重新获取空位与租赁状态", "<yellow>点击刷新")),
                   "actions": run("botrent status") + [{"type": "refresh inventory"}]}
main["head_hourly"] = {"slot": 10, "item": it("CLOCK", "<gold>临时租 <dark_gray>（按分钟）", L(
    f"<gray>在线费率：<gold>%botrental_online_rate% <gray>{COIN}/分钟",
    f"<gray>离线费率：<gold>%botrental_offline_rate% <gray>{COIN}/分钟",
    "<gray>先预存，用多少扣多少，剩余退还。",
    "<yellow>选择右边的时长 →"))}
for slot, minutes, coins, mat in ((11, 30, 150, "LIME_DYE"), (12, 60, 300, "EMERALD"), (13, 120, 600, "EMERALD_BLOCK")):
    main[f"rent_{minutes}"] = {"slot": slot, "item": it(mat, f"<green>租用 {minutes} 分钟", L(
        f"<gray>预存：<gold>{coins} <gray>{COIN}", "<gray>机器人放在你现在站的位置", "<yellow>点击租用")),
        "actions": run(f"botrent rent {minutes}")}
main["to_packages"] = {"slot": 15, "item": it("ENDER_EYE", "<gold>长期挂机套餐 <dark_gray>→", L(
    f"<gray>价格：<gold>%botrental_offer_small_1_price% <gray>{COIN}/天起",
    "<gray>7 天只收 6 天，30 天只收 20 天",
    "<gray>可选加载范围，也可选战斗型",
    "<yellow>点击查看并购买")), "actions": go(BUY)}
main["to_manage"] = {"slot": 16, "item": it("CHEST", "<gold>我的长期套餐 <dark_gray>→", L(
    "<gray>已购买：<white>%botrental_pack_active_count%/%botrental_pack_max_per_player%",
    "<gray>迁移位置、结束套餐、给战斗机器人拿武器",
    "<yellow>点击管理")), "actions": go(MANAGE)}
main["label_mine"] = {"slot": 22, "item": it("NAME_TAG", "<gold>我的临时租赁 <dark_gray>（<white>%botrental_active_count%/%botrental_max_per_player%<dark_gray>）")}
for n, base in ((1, 27), (2, 36), (3, 45)):
    main[f"slot_{n}"] = {
        "slot": base, "view-requirement": req(n),
        "item": it("PLAYER_HEAD", f"<gold>租赁位 {n} <dark_gray>（<white>%botrental_slot_{n}_status%<dark_gray>）", L(
            f"<gray>机器人：<white>%botrental_slot_{n}_bot%",
            f"<gray>余额：<gold>%botrental_slot_{n}_reserve% <gray>{COIN}",
            f"<gray>当前费率：<gold>%botrental_slot_{n}_rate% <gray>/分钟",
            f"<gray>预计还能挂：<yellow>%botrental_slot_{n}_minutes% 分钟",
            f"<gray>位置：<white>%botrental_slot_{n}_server% %botrental_slot_{n}_location%")),
        "else": {"item": it("GRAY_STAINED_GLASS_PANE", f"<dark_gray>租赁位 {n} <dark_gray>（<gray>空闲<dark_gray>）", L("<gray>点上方的按钮租用一个机器人"))}}
    main[f"slot_{n}_relocate"] = {"slot": base + 1, "view-requirement": req(n), "item": it("COMPASS", "<yellow>迁移到这里", L(
        "<gray>把机器人移到你现在站的位置", "<yellow>点击迁移")), "actions": run(f"botrent relocate {n}")}
    for col, coins, mat in ((2, 150, "GOLD_NUGGET"), (3, 300, "GOLD_INGOT"), (4, 600, "GOLD_BLOCK")):
        main[f"slot_{n}_topup_{coins}"] = {"slot": base + col, "view-requirement": req(n), "item": it(mat, f"<gold>充值 {coins} {COIN}", L(
            "<gray>延长这个机器人的时间", "<yellow>点击充值")), "actions": run(f"botrent topup {n} {coins}")}
    main[f"slot_{n}_cancel"] = {"slot": base + 8, "view-requirement": req(n), "item": it("BARRIER", "<red>结束租赁", L(
        "<gray>没用完的余额会退还给你", "<yellow>点击结束")), "actions": run(f"botrent cancel {n}", close=True)}

# ===================================================================================== BUY
buy = {}
buy["back"] = {"slot": 0, "item": it("ARROW", "<yellow>返回", L("<gray>回到机器人租赁首页")), "actions": go(MAIN)}
buy["overview"] = {"slot": 4, "item": it("ENDER_EYE", "<gold>长期挂机套餐", L(
    "<gold>长期空位：<yellow>%botrental_pack_pool_available%/%botrental_pack_pool_size% <dark_gray>|<gold> 你已买：<yellow>%botrental_pack_active_count%/%botrental_pack_max_per_player%",
    "<gray>机器人站在你<white>当前所在的位置<gray>，一直加载周围区块。",
    "",
    "<white>•<gray> 机器人到达后才开始计时",
    "<white>•<gray> 故障、重启的停机时间自动顺延",
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
TIERS = [("small", 18, "COBBLESTONE", "小"), ("medium", 27, "IRON_BLOCK", "中"), ("large", 36, "DIAMOND_BLOCK", "大")]
for tier, base, mat, cn in TIERS:
    buy[f"tier_{tier}"] = {"slot": base, "item": it(mat, f"<gold>{cn}档 <dark_gray>（<white>%botrental_offer_{tier}_radius%<dark_gray>）", L(
        f"<gray>加载范围：<white>%botrental_offer_{tier}_radius%", "<gray>范围越大，能加载的农场越大"))}
    for i, (days, m1, m2) in enumerate(((1, "LIME_DYE", "IRON_SWORD"), (7, "EMERALD", "DIAMOND_SWORD"), (30, "EMERALD_BLOCK", "NETHERITE_SWORD"))):
        buy[f"{tier}_{days}"] = {"slot": base + 1 + i, "item": it(m1, f"<green>{days} 天 <dark_gray>（普通）", L(
            f"<gray>价格：<gold>%botrental_offer_{tier}_{days}_price% <gray>{COIN}", f"<gray>档位：<white>{cn} <dark_gray>|<gray> 天数：<white>{days}", "<yellow>点击购买")),
            "actions": run(f"botrent pack {tier} {days}", close=True)}
        buy[f"{tier}_{days}_combat"] = {"slot": base + 5 + i, "item": it(m2, f"<red>{days} 天 <dark_gray>（战斗）", L(
            f"<gray>价格：<gold>%botrental_offer_{tier}_{days}_combat_price% <gray>{COIN}", f"<gray>档位：<white>{cn} <dark_gray>|<gray> 天数：<white>{days}", "<yellow>点击购买")),
            "actions": run(f"botrent pack {tier} {days} combat", close=True)}

# ===================================================================================== MANAGE
manage = {}
manage["back"] = {"slot": 0, "item": it("ARROW", "<yellow>返回购买页", L("<gray>购买新的套餐")), "actions": go(BUY)}
manage["overview"] = {"slot": 4, "item": it("CHEST", "<gold>我的长期套餐", L(
    "<gold>已购买：<yellow>%botrental_pack_active_count%/%botrental_pack_max_per_player%", "<gray>每个套餐一栏，在下面管理。"))}
manage["refresh"] = {"slot": 8, "item": it("SUNFLOWER", "<green>刷新", L("<gray>重新获取状态", "<yellow>点击刷新")), "actions": run("botrent status") + [{"type": "refresh inventory"}]}
for i, n in enumerate((4, 5, 6)):
    c0 = i * 3
    manage[f"pack_{n}"] = {
        "slot": 19 + c0, "view-requirement": req(n),
        "item": it("PLAYER_HEAD", f"<gold>套餐位 {n} <dark_gray>（<white>%botrental_slot_{n}_status%<dark_gray>）", L(
            f"<gray>类型：<white>%botrental_slot_{n}_tier%档 %botrental_slot_{n}_plan%",
            f"<gray>机器人：<white>%botrental_slot_{n}_bot%",
            f"<gray>剩余：<yellow>%botrental_slot_{n}_remaining%",
            f"<gray>战斗型：<white>%botrental_slot_{n}_combat%",
            f"<gray>位置：<white>%botrental_slot_{n}_server% %botrental_slot_{n}_location%")),
        "else": {"item": it("GRAY_STAINED_GLASS_PANE", f"<dark_gray>套餐位 {n} <dark_gray>（<gray>空闲<dark_gray>）", L("<gray>到购买页选一个套餐"))}}
    manage[f"pack_{n}_relocate"] = {"slot": 28 + c0, "view-requirement": req(n), "item": it("COMPASS", "<yellow>迁移到这里", L(
        "<gray>把机器人移到你现在站的位置", "<yellow>点击迁移")), "actions": run(f"botrent relocate {n}")}
    manage[f"pack_{n}_cancel"] = {"slot": 37 + c0, "view-requirement": req(n), "item": it("BARRIER", "<red>结束套餐", L(
        "<gray>已开始计时：退还剩余整天的 <gold>80%", "<gray>机器人还没到：<gold>全额退还", "<yellow>点击结束")), "actions": run(f"botrent cancel {n}", close=True)}
    manage[f"pack_{n}_gear"] = {"slot": 29 + c0, "view-requirement": req(n, extra=True), "item": it("IRON_SWORD", "<gold>交给机器人手持", L(
        "<gray>先把物品拿在<white>主手<gray>再点击", "<gray>套餐结束会退还给你", "<yellow>点击交付")), "actions": run(f"botrent gear {n}")}
    manage[f"pack_{n}_gear_take"] = {"slot": 38 + c0, "view-requirement": req(n, extra=True), "item": it("ENDER_CHEST", "<gold>取回手持物品", L(
        "<gray>把机器人拿着的物品放回你的背包", "<yellow>点击取回")), "actions": run(f"botrent gear {n} take")}


# ===================================================================================== write + validate
def page(name, items):
    slots = [v["slot"] for v in items.values()]
    dup = sorted({s for s in slots if slots.count(s) > 1})
    assert not dup, f"{name}: duplicate slots {dup}"
    assert all(0 <= s < 54 for s in slots)
    return {"name": titles[name], "size": 54, "enable": True, "update-interval": 1000, "fillItem": dict(FILL), "items": items}


IMPLEMENTED = [
    r"pool_available|pool_size|active_count|max_per_player|online_rate|offline_rate",
    r"pack_pool_available|pack_pool_size|pack_max_per_player|pack_active_count",
    r"slot_[1-9]_(status|bot|reserve|spent|rate|minutes|world|server|location|plan|tier|combat|remaining|days)",
    r"offer_[a-z]+_(name|radius)", r"offer_[a-z]+_\d+_price", r"offer_[a-z]+_\d+_combat_price",
]
# only these legacy-free MiniMessage tags may appear
ALLOWED_TAGS = {"gold", "yellow", "gray", "dark_gray", "white", "green", "red", "strikethrough", "bold"}
problems = []
for name, items in {MAIN: main, BUY: buy, MANAGE: manage}.items():
    text = yaml.safe_dump(page(name, items), allow_unicode=True, sort_keys=False, width=1000, default_flow_style=False)
    for token in sorted(set(re.findall(r"%botrental_([a-z0-9_]+)%", text))):
        if not any(re.fullmatch(p, token) for p in IMPLEMENTED):
            problems.append((name, "placeholder", token))
    for tag in set(re.findall(r"</?([a-z_]+)>", text)):
        if tag not in ALLOWED_TAGS:
            problems.append((name, "tag", tag))
    # every opened colour tag sequence should not leave stray text-less names
    open(OUT + name + ".yml", "w", encoding="utf-8").write("# Generated by scripts/menu_v3.py - edit the generator, not this file.\n" + text)
    yaml.safe_load(text)
    print(f"{name}: {len(items)} items")
if problems:
    sys.exit("problems: " + str(problems))
print("placeholders all implemented; only the server's own palette tags used; all pages parse")
