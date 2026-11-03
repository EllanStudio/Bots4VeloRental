import re, sys, yaml

root = "/tmp/claude-0/-root/8c35e095-9614-46ab-b2f3-b0ef170c347f/scratchpad/botdev/"
OUT = root + "rental/zmenu/"

MAIN, BUY, MANAGE = "机器人租赁", "机器人长期挂机", "机器人套餐管理"
FILL = {"material": "BLACK_STAINED_GLASS_PANE", "name": " "}


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


def shown_when_not_empty(slot_no):
    """Visible only when rental slot N holds a lease (not '空闲', not still loading)."""
    return {"requirements": [
        {"type": "placeholder", "placeholder": f"%botrental_slot_{slot_no}_status%", "action": "DIFFERENT_STRING", "value": "空闲"},
        {"type": "placeholder", "placeholder": f"%botrental_slot_{slot_no}_status%", "action": "DIFFERENT_STRING", "value": "加载中"}]}


def shown_when_combat(slot_no):
    r = shown_when_not_empty(slot_no)
    r["requirements"].append({"type": "placeholder", "placeholder": f"%botrental_slot_{slot_no}_combat%", "action": "EQUALS_STRING", "value": "是"})
    return r


def page(name, items):
    slots = [v["slot"] for v in items.values()]
    dup = sorted({s for s in slots if slots.count(s) > 1})
    assert not dup, f"{name}: duplicate slots {dup}"
    assert all(0 <= s < 54 for s in slots), f"{name}: slot out of range"
    return {"name": name_of[name], "size": 54, "enable": True, "update-interval": 1000, "fillItem": dict(FILL), "items": items}


name_of = {MAIN: "<dark_green>挂机机器人", BUY: "<dark_purple>长期挂机套餐", MANAGE: "<dark_aqua>我的长期套餐"}

# =====================================================================================  MAIN
main = {}
main["overview"] = {"slot": 4, "item": it("PLAYER_HEAD", "<green><bold>挂机机器人", [
    "<gray>机器人会站在你当前的位置，替你挂机、加载区块。",
    "",
    "<white>临时租：<gray>按分钟计费，适合一小会儿。",
    "<white>长期套餐：<gray>按天计费，便宜得多，适合长期挂机。",
    "",
    "<gray>临时空位：<white>%botrental_pool_available%/%botrental_pool_size%   <gray>长期空位：<white>%botrental_pack_pool_available%/%botrental_pack_pool_size%"])}
main["refresh"] = {"slot": 8, "item": it("SUNFLOWER", "<green>刷新", ["<gray>重新获取空位与租赁状态"]),
                   "actions": run("botrent status") + [{"type": "refresh inventory"}]}

# product row
main["head_hourly"] = {"slot": 10, "item": it("CLOCK", "<aqua><bold>临时租 · 按分钟", [
    "<gray>在线 <gold>%botrental_online_rate%<gray> 币/分钟，离线 <gold>%botrental_offline_rate%<gray> 币/分钟",
    "<gray>先预存，用多少扣多少，剩下的退还。",
    "<yellow>点右边的按钮选择时长 →"])}
for slot, minutes, coins, mat in ((11, 30, 150, "LIME_DYE"), (12, 60, 300, "EMERALD"), (13, 120, 600, "EMERALD_BLOCK")):
    main[f"rent_{minutes}"] = {"slot": slot, "item": it(mat, f"<green>租 {minutes} 分钟", [
        f"<gray>预存 <gold>{coins}<gray> 币", "<gray>机器人放在你现在站的位置", "<yellow>点击租用"]),
        "actions": run(f"botrent rent {minutes}")}
main["to_packages"] = {"slot": 15, "item": it("ENDER_EYE", "<light_purple><bold>长期挂机套餐 →", [
    "<gray>按天购买：<gold>%botrental_offer_small_1_price%<gray> 币/天起",
    "<gray>1 天 / 7 天（付 6 天）/ 30 天（付 20 天）",
    "<gray>可选加载范围，也可选能打怪的战斗型。",
    "<yellow>点击查看并购买"]), "actions": go(BUY)}
main["to_manage"] = {"slot": 16, "item": it("CHEST", "<light_purple>我的长期套餐 →", [
    "<gray>已购买：<white>%botrental_pack_active_count%/%botrental_pack_max_per_player%",
    "<gray>迁移位置、取消、给战斗机器人拿武器。",
    "<yellow>点击管理"]), "actions": go(MANAGE)}

# my hourly leases: three rows (27-35, 36-44, 45-53)
main["label_mine"] = {"slot": 22, "item": it("NAME_TAG", "<white>我的临时租赁", ["<gray>共 <white>%botrental_active_count%/%botrental_max_per_player%<gray> 个"])}
for n, base in ((1, 27), (2, 36), (3, 45)):
    main[f"slot_{n}"] = {
        "slot": base,
        "view-requirement": shown_when_not_empty(n),
        "item": it("PLAYER_HEAD", f"<aqua>租赁位 {n}：%botrental_slot_{n}_status%", [
            f"<gray>机器人：<white>%botrental_slot_{n}_bot%",
            f"<gray>余额：<gold>%botrental_slot_{n}_reserve%<gray>  当前费率：<white>%botrental_slot_{n}_rate%/分钟",
            f"<gray>预计还能挂：<white>%botrental_slot_{n}_minutes% 分钟",
            f"<gray>位置：<white>%botrental_slot_{n}_server% %botrental_slot_{n}_location%"]),
        "else": {"item": it("GRAY_STAINED_GLASS_PANE", f"<gray>租赁位 {n}：空闲", ["<gray>点上方的按钮租用一个机器人"])}}
    main[f"slot_{n}_relocate"] = {"slot": base + 1, "view-requirement": shown_when_not_empty(n),
        "item": it("COMPASS", "<yellow>迁移到这里", ["<gray>把机器人移到你现在站的位置"]), "actions": run(f"botrent relocate {n}")}
    for col, coins, mat in ((2, 150, "GOLD_NUGGET"), (3, 300, "GOLD_INGOT"), (4, 600, "GOLD_BLOCK")):
        main[f"slot_{n}_topup_{coins}"] = {"slot": base + col, "view-requirement": shown_when_not_empty(n),
            "item": it(mat, f"<gold>充值 {coins} 币", ["<gray>延长这个机器人的时间"]), "actions": run(f"botrent topup {n} {coins}")}
    main[f"slot_{n}_cancel"] = {"slot": base + 8, "view-requirement": shown_when_not_empty(n),
        "item": it("BARRIER", "<red>结束这个租赁", ["<gray>没用完的余额会退还给你"]), "actions": run(f"botrent cancel {n}", close=True)}

# =====================================================================================  BUY
buy = {}
buy["back"] = {"slot": 0, "item": it("ARROW", "<yellow>← 返回", ["<gray>回到机器人租赁首页"]), "actions": go(MAIN)}
buy["overview"] = {"slot": 4, "item": it("ENDER_EYE", "<light_purple><bold>长期挂机套餐", [
    "<gray>机器人站在你<white>当前所在的位置<gray>，一直加载周围区块。",
    "<gray>长期空位：<white>%botrental_pack_pool_available%/%botrental_pack_pool_size%    <gray>你已买：<white>%botrental_pack_active_count%/%botrental_pack_max_per_player%",
    "",
    "<gray>• 机器人<white>到达后才开始计时<gray>",
    "<gray>• 服务器故障、重启的停机时间会<white>自动顺延<gray>",
    "<gray>• 7 天只收 6 天的钱，30 天只收 20 天的钱"])}
buy["to_manage"] = {"slot": 8, "item": it("CHEST", "<light_purple>我的套餐 →", ["<gray>迁移、取消、给战斗机器人拿武器", "<yellow>点击管理"]), "actions": go(MANAGE)}
buy["legend_plain"] = {"slot": 11, "item": it("LIME_STAINED_GLASS_PANE", "<green><bold>普通套餐", ["<gray>机器人站着挂机，保持区块加载。", "<gray>左边绿色按钮"])}
buy["legend_combat"] = {"slot": 15, "item": it("RED_STAINED_GLASS_PANE", "<red><bold>战斗套餐  价格 +50%", [
    "<gray>能拿着你给的武器，自动攻击附近的敌对怪物。", "<gray>不会攻击玩家、宠物、村民，也不会捡东西。", "<gray>右边红色按钮"])}
buy["note"] = {"slot": 49, "item": it("BOOK", "<white>购买说明", [
    "<gray>1. 站在你想挂机的位置，点一个套餐。",
    "<gray>2. 聊天栏会显示价格，在 <white>30 秒内再确认一次<gray> 才会扣费。",
    "<gray>3. 取消套餐：已开始计时的退剩余整天的 <white>80%<gray>，",
    "<gray>   机器人还没到达则<white>全额退还<gray>。",
    "<gray>4. 套餐不能充值，到期后重新购买即可。"])}
TIERS = [("small", 18, "COBBLESTONE", "<gray>"), ("medium", 27, "IRON_BLOCK", "<white>"), ("large", 36, "DIAMOND_BLOCK", "<aqua>")]
tier_cn = {"small": "小", "medium": "中", "large": "大"}
for tier, base, mat, color in TIERS:
    buy[f"tier_{tier}"] = {"slot": base, "item": it(mat, f"{color}<bold>{tier_cn[tier]}档", [
        f"<gray>加载范围：<white>%botrental_offer_{tier}_radius%", "<gray>范围越大，能加载的农场越大。"])}
    for i, (days, mat1, mat2) in enumerate(((1, "LIME_DYE", "IRON_SWORD"), (7, "EMERALD", "DIAMOND_SWORD"), (30, "EMERALD_BLOCK", "NETHERITE_SWORD"))):
        buy[f"{tier}_{days}"] = {"slot": base + 1 + i, "item": it(mat1, f"<green>{days} 天", [
            f"<gray>价格：<gold>%botrental_offer_{tier}_{days}_price%<gray> 币", f"<gray>{tier_cn[tier]}档 · {days} 天 · 普通", "<yellow>点击购买"]),
            "actions": run(f"botrent pack {tier} {days}", close=True)}
        buy[f"{tier}_{days}_combat"] = {"slot": base + 5 + i, "item": it(mat2, f"<red>{days} 天 战斗", [
            f"<gray>价格：<gold>%botrental_offer_{tier}_{days}_combat_price%<gray> 币", f"<gray>{tier_cn[tier]}档 · {days} 天 · 战斗", "<yellow>点击购买"]),
            "actions": run(f"botrent pack {tier} {days} combat", close=True)}

# =====================================================================================  MANAGE
manage = {}
manage["back"] = {"slot": 0, "item": it("ARROW", "<yellow>← 返回购买页", ["<gray>买新的套餐"]), "actions": go(BUY)}
manage["overview"] = {"slot": 4, "item": it("CHEST", "<light_purple><bold>我的长期套餐", [
    "<gray>已购买：<white>%botrental_pack_active_count%/%botrental_pack_max_per_player%", "<gray>每个套餐一栏，在下面管理。"])}
manage["refresh"] = {"slot": 8, "item": it("SUNFLOWER", "<green>刷新", ["<gray>重新获取状态"]), "actions": run("botrent status") + [{"type": "refresh inventory"}]}
for i, n in enumerate((4, 5, 6)):
    c0 = i * 3
    manage[f"pack_{n}"] = {
        "slot": 19 + c0, "view-requirement": shown_when_not_empty(n),
        "item": it("PLAYER_HEAD", f"<aqua>套餐位 {n}：%botrental_slot_{n}_status%", [
            f"<gray>类型：<white>%botrental_slot_{n}_tier%档 %botrental_slot_{n}_plan%",
            f"<gray>机器人：<white>%botrental_slot_{n}_bot%",
            f"<gray>剩余：<gold>%botrental_slot_{n}_remaining%",
            f"<gray>战斗型：<white>%botrental_slot_{n}_combat%",
            f"<gray>位置：<white>%botrental_slot_{n}_server% %botrental_slot_{n}_location%"]),
        "else": {"item": it("GRAY_STAINED_GLASS_PANE", f"<gray>套餐位 {n}：空闲", ["<gray>到购买页选一个套餐"])}}
    manage[f"pack_{n}_relocate"] = {"slot": 28 + c0, "view-requirement": shown_when_not_empty(n),
        "item": it("COMPASS", "<yellow>迁移到这里", ["<gray>把机器人移到你现在站的位置"]), "actions": run(f"botrent relocate {n}")}
    manage[f"pack_{n}_cancel"] = {"slot": 37 + c0, "view-requirement": shown_when_not_empty(n),
        "item": it("BARRIER", "<red>结束这个套餐", ["<gray>已开始计时：退还剩余整天的 80%", "<gray>机器人还没到：全额退还"]), "actions": run(f"botrent cancel {n}", close=True)}
    manage[f"pack_{n}_gear"] = {"slot": 29 + c0, "view-requirement": shown_when_combat(n),
        "item": it("IRON_SWORD", "<gold>交给机器人手持", ["<gray>先把物品拿在<white>主手<gray>再点击", "<gray>套餐结束会退还给你"]), "actions": run(f"botrent gear {n}")}
    manage[f"pack_{n}_gear_take"] = {"slot": 38 + c0, "view-requirement": shown_when_combat(n),
        "item": it("ENDER_CHEST", "<gold>取回手持物品", ["<gray>把机器人拿着的物品放回你的背包"]), "actions": run(f"botrent gear {n} take")}

# =====================================================================================  write + validate
pages = {MAIN: main, BUY: buy, MANAGE: manage}
IMPLEMENTED = [
    r"pool_available|pool_size|active_count|max_per_player|online_rate|offline_rate",
    r"pack_pool_available|pack_pool_size|pack_max_per_player|pack_active_count",
    r"slot_[1-9]_(status|bot|reserve|spent|rate|minutes|world|server|location|plan|tier|combat|remaining|days)",
    r"offer_[a-z]+_(name|radius)", r"offer_[a-z]+_\d+_price", r"offer_[a-z]+_\d+_combat_price",
]
problems = []
for name, items in pages.items():
    text = yaml.safe_dump(page(name, items), allow_unicode=True, sort_keys=False, width=1000, default_flow_style=False)
    for token in sorted(set(re.findall(r"%botrental_([a-z0-9_]+)%", text))):
        if not any(re.fullmatch(p, token) for p in IMPLEMENTED):
            problems.append((name, token))
    open(OUT + name + ".yml", "w", encoding="utf-8").write(f"# Generated by menu_v2.py - do not hand-edit; see docs/releases/1.4.0.md\n" + text)
    yaml.safe_load(text)
    print(f"{name}: {len(items)} items")
if problems:
    sys.exit("unknown placeholders: " + str(problems))
print("all placeholders exist in the plugin; all pages parse")
