# Bots4Velo Rental

Bots4Velo 的独立机器人租赁扩展。项目包含一个 Velocity Addon、一个安装在
每台开放租赁的后端（目前是 `redstone` 与 `survival`）上的 Paper 桥接插件，以及一份 zMenu 菜单。

## 当前规则

- 只允许在 Addon `bridge.servers` 中列出的服务器使用（目前为 `redstone` 与 `survival`）。
- 公共机器人池固定为 `BOT_1` 至 `BOT_10`，共 10 个，各服共用；机器人会被送到租赁时所在的服务器。
- `/botrent relocate` 可以把机器人移到另一台开放租赁的服务器，移动途中暂停计费。
- 每名玩家最多同时租赁 3 个机器人。
- 玩家在线时消耗 5 艾尔岚金币/分钟，离线时消耗 20 艾尔岚金币/分钟。
- 使用 ExcellentEconomy 的 `ellan_coin`；玩家先预存金币，结束时退回未使用的整数余额。
- 机器人真正进入 redstone 并传送到玩家指定位置后才扣除首分钟费用。
- 异常掉线期间暂停计费并尝试恢复；启动失败会全额退款。

## 组成

| 模块 | 安装位置 | 职责 |
|---|---|---|
| `velocity-addon` | `plugins/bots4velo/addons/bot-rental/` | 分配机器人、持久化租赁、分钟计费、故障恢复 |
| `paper-bridge` | 每台开放租赁的后端的 `plugins/` | ExcellentEconomy 扣款/退款、位置传送、PAPI 占位符 |
| `zmenu/机器人租赁.yml` | 每台开放租赁的后端的 `plugins/zMenu/inventories/` | 玩家菜单 |

所有后端必须连接同一个 ExcellentEconomy 数据库：租约结束时，退款由机器人当时所在服务器的桥接执行。

两个进程只监听 `127.0.0.1`，并对每个请求使用带时间戳和防重放 nonce 的
HMAC-SHA256 签名。不要把桥接端口转发到公网。

## 玩家命令

```text
/botrent
/botrent status
/botrent rent <30|60|120>
/botrent topup <1|2|3> <150|300|600>
/botrent relocate <1|2|3>
/botrent cancel <1|2|3>
```

`/botrent rent` 会记录玩家执行命令时的世界与精确坐标。所有价格、并发限制和
机器人池都会在服务端重新校验，菜单参数不能绕过限制。

## PlaceholderAPI

通用占位符：

```text
%botrental_pool_available%
%botrental_pool_size%
%botrental_active_count%
%botrental_max_per_player%
%botrental_online_rate%
%botrental_offline_rate%
```

租赁位占位符（将 `1` 换成 `2` 或 `3`）：

```text
%botrental_slot_1_status%
%botrental_slot_1_bot%
%botrental_slot_1_reserve%
%botrental_slot_1_spent%
%botrental_slot_1_rate%
%botrental_slot_1_minutes%
%botrental_slot_1_location%
%botrental_slot_1_server%
```

`server` 返回 Paper 配置 `server-display-names` 中的服务器名称（如“赤铁原”）。

## 构建

Paper 桥接直接使用 ExcellentEconomy 2.8.0 API，因此需要 Java 25。Velocity
Addon 以 Java 21 为目标。AuthMe 更新后的登录流程要求使用包含服务器切换结果
处理修复的 Bots4Velo API `3.0.8`。构建前先把固定版本的 API 发布到本机：

```bash
git clone https://github.com/EllanServer/Bots4Velo.git
cd Bots4Velo
git checkout 95f027b525fc0974c5019a501445996a84d76697
./gradlew :addon-api:publishToMavenLocal -PpluginVersion=3.0.8

cd ../Bots4VeloRental
./gradlew clean check shadowJar
```

## 安装

1. 生成至少 32 字符的随机共享密钥。
2. 将同一密钥分别写入 Addon 与 Paper 的 `config.yml`。
3. 确保 Bots4Velo 的 `BOT_1` 至 `BOT_10` 设置为 `enabled: false`。租赁 Addon 会按需
   启动它们，并切换到租赁所在的服务器。
4. 每台后端的 Paper `config.yml` 中，`server-id` 写该后端在 Velocity 中的名字，`bridge.port`
   各不相同（redstone `18766`，survival `18767`），并在 Addon 的 `bridge.servers` 中登记同样的地址。
5. 安装产物后先启动各后端，再启动 Velocity。
6. 执行 `/zmenu reload`，然后用 `/botrent` 打开菜单。

从单服版本升级时，旧的 `bridge.base-url` 与 `rental.allowed-server` 仍可读取，会被视为只开放
该服务器；`rentals.db` 会自动增加 `server` 列，已有租约归入 `redstone`。

更换 Addon JAR 或 Paper JAR 需要对应进程重启。`rentals.db` 与
`processed-refunds.properties` 是恢复和退款审计数据，不要删除。
