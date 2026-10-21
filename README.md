# QBotMobile — 手机端 QQ 机器人（官方开放平台）

在 Android 手机上 **24 小时运行 QQ 机器人**，同时把手机当作 **维护端**（看状态、看日志、改规则、手动发言）。

不使用任何第三方协议库（NapCat / Lagrange / Shamrock），也不需要 root、Termux 或 Xposed ——
走的是 **QQ 官方机器人开放平台**，纯 HTTPS + WebSocket，一个普通 App 的前台服务即可长期在线。

> 官方明确说明：WebSocket 模式「不依赖公网服务器部署，适合个人单机使用，需维护长连接」。
> 这正是手机端场景的正确选择。

---

## 一、为什么这样设计

| 需求 | 方案 | 理由 |
|---|---|---|
| 手机 24h 在线 | 官方 WebSocket 模式 + 前台服务 | 不需要公网 IP，也不需要 root |
| 数据存在手机里 | Room 存日志与规则，Keystore 存凭证 | 离线可用，换设备导出即走 |
| 手机当维护端 | Jetpack Compose 四页维护界面 | 状态、日志、规则、凭证一个 App 搞定 |
| 不想用 OpenClaw | 自己实现机器人逻辑 | OpenClaw 是官方提供的 AI Agent 接入服务，本项目走"原创机器人"路线 |

### 关于「第三方协议」的说明

第三方协议端（NapCat 等）功能更全，但属于非官方协议，存在封号风控风险。
本项目**刻意只支持官方开放平台**，换来的是零风控、零 root、长期稳定。这是有意取舍，不是遗漏。

---

## 二、安装（最简方式）

**仓库不带成品 APK，也不带签名密钥**（公开仓库泄露签名密钥等于把应用的身份交出去）。
两种获取方式：

**方式一：GitHub Actions 云构建（推荐，零本地环境）**

1. Fork 或 Clone 本仓库到自己的 GitHub；
2. 仓库页面 → **Actions** → 「Build APK」→ **Run workflow**；
3. 等 3-5 分钟，在运行记录底部的 **Artifacts** 里下载 `QBotMobile-release.zip`；
4. 想用自己的签名（与旧版本覆盖安装需一致）：仓库 **Settings → Secrets and variables → Actions**
   添加 `KEYSTORE_BASE64`（`base64 -w0 your.jks` 的输出）与 `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，
   下次构建会自动签名；不配则产出未签名包，需自行 `apksigner` 签名后才能安装。

**方式二：本地构建**

```bash
./gradlew assembleRelease        # 需要 JDK 17 + Android SDK 35
                                   # 产物在 app/build/outputs/apk/release/
```

要求：JDK 17、Android SDK（platforms;android-35、build-tools;34.0.0+），最低支持 **Android 8.0（API 26）**。

装完打开 → **设置** 页填入 AppID 与 AppSecret → 保存 → 回 **状态** 页点「启动机器人」。
想用 AI 就在 **设置 → AI 接入** 里填服务地址 + API Key + 模型名，点「测试连接」验证通过后保存。

### 申请机器人凭证

1. 打开 [QQ 开放平台](https://q.qq.com/#/apps) 注册并完成**主体认证**（个人开发者需实名）。
2. 创建机器人，在管理端拿到 **AppID** 和 **AppSecret**。
3. 开发设置里把「事件订阅与回调方式」切换为 **WebSocket**。
4. 服务范围：未认证时机器人仅管理员可用，可添加到**你作为群主**的群里测试。



---

## 三、项目结构

```
QBotMobile/
├── botcore/                纯 Kotlin/JVM 协议层（不依赖 Android，可独立测试）
│   └── src/main/kotlin/com/qbot/core/
│       ├── auth/           AccessToken 获取、缓存、提前刷新
│       ├── api/            OpenAPI 封装（发消息、撤回、取网关）
│       ├── gateway/        WebSocket 网关状态机 + 重连退避
│       ├── engine/         规则引擎、运行时、日志
│       └── model/          协议报文与消息模型
├── app/                    Android 壳
│   └── src/main/kotlin/com/qbot/app/
│       ├── service/        BotForegroundService（常驻前台服务）
│       ├── network/        NetworkMonitor（网络恢复监听）
│       ├── receiver/       BootReceiver（开机 / 升级后自启）
│       ├── runtime/        BotController（生命周期与依赖组装）
│       ├── data/           Room 数据库 + 加密设置仓库
│       └── ui/             Compose 维护端（状态 / 日志 / 规则 / 设置）
```

**`botcore` 与 Android 解耦**是核心设计：协议层可以脱离模拟器直接跑测试，
将来也能搬到服务器或桌面端复用同一套引擎。

---

## 四、协议实现要点

```
取凭证 → 取网关 → 建连 → Hello(10) → Identify(2)/Resume(6) → READY(0)
                                   → 心跳(1) ⇄ ACK(11) → 断线退避重连 ↺
```

| 环节 | 接口 |
|---|---|
| 取凭证 | `POST https://bots.qq.com/app/getAppAccessToken` |
| 鉴权头 | `Authorization: QQBot {access_token}` |
| 取网关 | `GET https://api.sgroup.qq.com/gateway/bot` |
| 心跳周期 | Hello 下发，默认 45s |
| 断线续传 | `op6 Resume {session_id, seq}`，平台补发遗漏事件 |

### 针对 7x24 挂机做的加固

1. **心跳僵死检测**
   移动网络下 TCP 会进入"半开"状态——既不报错也收不到数据（Wi-Fi 切 4G、进电梯、NAT 老化）。
   如果只看心跳是否发出，机器人会"看起来在线"实则掉线数小时。
   因此额外监控 ACK 是否逾期，超过 `心跳周期 × 2.5` 即判定僵死并主动重连。

2. **带抖动的指数退避**
   `delay = min(1s × 2^n, 30s) × (1 ± 20%)`。抖动用于打散重连时刻，避免重连风暴。

3. **错误码分流**
   `4009` → Resume 保留会话；`4914`（已下架）/ `4915`（已封禁）→ 直接停机；
   其余 → 重新 Identify。**封禁时不再无意义地反复重连耗光电量。**

4. **Intents 三级降级**
   Identify 被服务端拒绝（关闭码 4013/4014 或 op9 Invalid Session）时，
   按 `完整订阅 → 仅消息事件 → 基础事件` 逐级降级重连，尽量保住「群@/单聊」能力；
   全部被拒才停机并提示去平台申请权限，绝不无脑重连。

5. **连接纪元（epoch）**
   每次新建连接递增，所有异步回调先校验纪元再处理，避免旧连接的迟到回调污染新连接状态。

6. **凭证提前刷新**
   凭证寿命 7200 秒，提前 5 分钟换新；OpenAPI 返回 401 时立即作废并重试一次。

7. **网关地址缓存 + 限频冷静期**
   `GET /gateway/bot` 限频严格，取到地址即缓存复用；万一触发限频，
   重连间隔自动拉长到分钟级（60-120s），不再秒级撞墙。

---

## 五、保活：这是最现实的一关

技术上机器人能跑，但**国产 ROM 会杀后台**。本项目做了三层保险：

| 层 | 机制 |
|---|---|
| 前台服务 | 常驻通知（用户可一键停止），`START_STICKY` 让进程被回收后系统尝试重建 |
| 网络监听 | `ConnectivityManager.NetworkCallback` 动态监听，恢复后延迟 1.5s 重连 |
| 看门狗 | 每 60s 校验，发现控制器停止且用户未主动停止则重新拉起 |

> 注：Android 7.0 起静态注册的 `CONNECTIVITY_CHANGE` 广播不再回调，
> 因此**没有**在 Manifest 里声明网络广播接收器，改由服务内动态完成。

**你需要手动做的两件事**（App 设置页有引导入口）：

1. 把本应用加入**电池优化白名单**（系统不允许应用自行豁免，必须用户在系统页确认）
2. 在系统设置里允许**自启动 / 后台运行**（MIUI、ColorOS、EMUI 位置各不相同）

做完这两步，常规机型即可稳定在线数天。

---

## 六、规则引擎

内置轻量自动回复，规则存本地数据库，**修改后无需重启机器人**（下一次消息即生效）。

- 匹配方式：`EXACT` 全等 / `CONTAINS` 包含 / `PREFIX` 前缀 / `REGEX` 正则
- 作用范围：`ALL` / `GROUP_ONLY` / `PRIVATE_ONLY`
- 优先级：数值越大越优先，命中后即停止匹配
- 回复变量：`{content}` 用户原话、`{sender}` 发送者、`{target}` 群/用户、`{time}` 时间、`{nl}` 换行

正则非法时会被安全跳过（不会导致崩溃）。

---

## 六·补、AI 接入

在「设置 → AI 接入」里填三项即可对话，走 **OpenAI 兼容协议**，一套配置通吃：

| 服务 | 地址 | 模型示例 |
|---|---|---|
| DeepSeek | `https://api.deepseek.com` | `deepseek-chat` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode` | `qwen-plus` |
| Kimi | `https://api.moonshot.cn` | `moonshot-v1-8k` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| OpenAI | `https://api.openai.com` | `gpt-4o-mini` |
| 本地 Ollama | `http://电脑IP:11434` | `qwen2.5` |

- **地址容错**：填 `https://api.deepseek.com`、带 `/v1` 或完整路径都能识别，不会因为差一个 `/v1` 就 404
- **API Key 加密存储**（Android Keystore），与机器人凭证同一套加密方案
- **三种触发方式**：
  - `规则优先`（默认）：本地规则未命中才问 AI，关键词与 AI 混用
  - `AI 优先`：所有消息都走 AI，本地规则暂时失效
  - `仅 /ai`：只有 `/ai 开头`的消息才触发，其余按规则处理
- **多轮上下文**：按群/人隔离记忆，默认保留最近 6 轮，重启不丢失（Room 落库）
- **失败兜底**：AI 出错时按配置的话术回复（如"AI 暂时无法回答"），留空则不回复
- **清空上下文**：发送 `/ai 清空`（或 `新对话`）重置当前会话记忆
- **保存即生效**，无需重启机器人；「测试连接」按钮可在群里来消息前先验证 Key 是否正确

常见错误码已翻译成中文：401 → Key 无效、404 → 地址或模型名不存在、429 → 频率超限或额度不足。

### 响应速度与成本控制（1.2.2 起）

| 功能 | 说明 |
|---|---|
| **思考模式开关** | DeepSeek **默认开启思考**（先输出思维链再作答），这是"感觉慢"的主因。设置页默认**关闭**，关闭后请求体带 `{"thinking":{"type":"disabled"}}` 直接出答案；该参数仅对 DeepSeek 端点发送（OpenAI 等严格服务商遇未知字段会 400） |
| **提示词 token 可见** | 系统提示词输入框下方实时显示估算 token 数，超 1500 变红警示——提示词每轮全量发送，越长越贵越慢 |
| **上下文 token 预算** | 滑块控制「提示词 + 历史 + 本轮问题」总量上限（默认 3000）。超限时从最早的对话**成对丢弃**；服务端仍报超限（400 context length）会自动砍半重试一次 |
| **缓存友好组装** | system 提示词固定最前、内容逐字节稳定、历史只追加不重写。DeepSeek 前缀缓存要求「从第 0 个 token 起完全相同」，命中部分价格仅 1/10，首字延迟大幅降低（128K 场景实测 13s → 500ms） |
| **用量与命中率** | 仪表盘 AI 卡片显示最近一次请求的 token 总数、缓存命中率、回复 token；命中率长期为 0 说明提示词被动态内容污染，值得排查 |

---

## 七、测试

协议层为纯 JVM 模块，无需模拟器即可验证：

```bash
# 只构建协议层（无需 Android SDK）
./gradlew -c settings-botcore.gradle.kts :botcore:test
```

共 **106 个用例**，覆盖：

- 退避算法的增长、截断、抖动边界
- Identify / 心跳 / Resume 报文结构
- 凭证缓存、提前刷新、过期换新、错误码映射
- 消息解析（群聊 / 单聊 / 艾特剥离 / 秒级与毫秒级时间戳）
- 规则匹配（四种匹配方式、优先级、作用域、非法正则容错）
- AI 客户端（地址归一化、请求结构、错误码翻译、限频容错、thinking 参数、usage 与缓存命中解析、上下文超限分类）
- AI 应答器（三种触发策略、多轮上下文、失败不写历史、会话隔离、超长截断）
- **缓存友好组装**：固定顺序（system → 历史 → 本轮）、超预算成对丢弃、砍半重试
- **网关集成测试**：用 MockWebServer 模拟平台，跑通 `建连 → Hello → Identify → READY → 事件分发`，
  并验证 `op7` 会触发自动重连、`op9`（Identify 被拒）会触发三级降级而非死循环
- **限频回归测试**：`/gateway/bot` 返回「接口调用超过频率限制」时进入冷静期，
  重连不再重复请求该接口（地址缓存复用）
- **Intents 降级测试**：完整订阅被拒 → 仅消息 → 基础事件逐级降级，全拒才停机并给平台指引

> 关于「连接中 / 鉴权中反复横跳」：真实故障的根因是 **Intents 位定义与官方不一致**
> （携带了服务端未定义的位），Identify 被拒后旧实现无脑重连形成死循环。
> 现已按官方 SDK（botpy）定义重写全部位，并增加三级降级；服务端拒绝的信号是
> **op9 消息**而非关闭码，两者都会触发降级。

> 关于「重连中 / HTTP 400 接口调用超过频率限制」：官方对 `GET /gateway/bot`
> 限频严格，早期版本每次重连都重新取地址，几十秒内就会打满频控并锁死在重连循环。
> 现已改为**取到地址即缓存复用**，只有握手被拒（401/403/404/410）才重新获取；
> 万一仍触发限频，会自动把重连间隔拉长到分钟级等待恢复。

---

## 八、已知限制

1. **机器人只能在其已被添加的群里工作**；单聊需用户主动发起（平台规则）。
2. **未认证机器人仅管理员可用**，且只能加入你作为群主的群。个人认证后上限 500 个群。
3. **主动消息有配额**，规则自动回复走"被动回复"通道不占配额，手动发送会占用。
4. **凭证不含群管理类高级能力**（如主动踢人、改群公告），如需请自行扩展 `OpenApi`。
5. **AI 调用按 token 计费**，由你填写的服务商收取；群聊里 `AI 优先` 模式下每条消息都会请求，
   建议日常用「规则优先」或「仅 /ai」，需要时再切换。
6. `specialUse` 类型的前台服务在 Android 14+ 需要在 Manifest 声明用途（已配置），
   上架 Google Play 需要额外说明，自用无影响。

---

## 九、技术栈

| 类别 | 选型 |
|---|---|
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3 |
| 网络 | OkHttp 4.12（WebSocket + REST） |
| 序列化 | kotlinx.serialization |
| 数据库 | Room 2.6 |
| 配置 | DataStore + EncryptedSharedPreferences（Keystore 加密） |
| 异步 | Coroutines + StateFlow |
| 构建 | AGP 8.7 + Gradle 8.13 |

---

## 十、许可与合规

机器人行为须遵守 [QQ 机器人运营规范](https://bot.qq.com/wiki/business/)。
请合理控制消息频率，避免触发平台风控。
