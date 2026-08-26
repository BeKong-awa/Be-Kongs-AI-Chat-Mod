# Be Kong's AI Chat Mod

> 当前版本：**1.2.0**（适配 Minecraft 26.1.2 / Fabric）· 完整版本历史见 [CHANGELOG.md](CHANGELOG.md)

## 模组简介

**Be Kong's AI Chat Mod** 是一个为 Minecraft Java 版（Fabric 26.1.2）设计的客户端模组，使用Deepseek制作。它允许玩家在游戏聊天框中直接与多种 AI 服务进行对话，并支持文生图、图床上传等高级功能。  
模组以“不打扰原版聊天体验”为设计原则，通过正则触发、定时冷却、消息过滤、玩家黑名单等机制，实现智能、可控的 AI 互动。

---

## 核心特性

- **多 AI 提供商支持**  
  内置 OpenAI、DeepSeek、Moonshot、Anthropic、Ollama、Agnes AI 以及任何 OpenAI 兼容接口。
- **触发回复与自动回复独立密钥**  
  可为触发回复和自动回复分别配置不同的 API 密钥，互不干扰。
- **上下文记忆**  
  可开关并自定义长度，AI 能记住前后对话。
- **正则触发回复**  
  当玩家消息匹配自定义正则时，AI 立即回复，并可移除触发词。
- **定时自动回复**  
  每隔一段时间读取最后一条未处理的玩家消息并回复，冷却时间独立。
- **独立冷却机制**  
  触发回复与自动回复拥有各自独立的冷却与状态锁，可同时运行而互不阻塞。
- **玩家黑名单 / 白名单模式**  
  支持配置黑名单玩家名，黑名单玩家的消息将被完全忽略；也可开启白名单模式，仅响应白名单内玩家的触发。
- **图片 URL 识别（Vision）**  
  聊天中出现图片链接（png/jpg/gif/webp 等）时，自动调用多模态视觉接口让 AI 描述图片内容。
- **特殊触发词（聊天改配置）**  
  在聊天中直接发送 `关闭触发回复`、`开启触发回复`、`清空上下文`、`重载配置` 等短语即可修改模组状态，不会触发 AI 回复，支持自定义扩展。
- **游戏内配置界面**  
  `/aichat openconfig` 打开可视化配置 UI，五个页签覆盖全部设置，改完即保存，无需手改 JSON。
- **聊天格式兼容开关**  
  `stripChatPrefix` / `filterGameEvents` 两个开关，兼容修改过聊天格式或会广播 GAME 事件的服务器。
- **自身消息过滤**  
  模组会自动忽略客户端玩家自己发送的消息（包括 AI 自动发送的消息），避免自我回复。
- **重复消息去重**  
  同一玩家在短时间内（默认 2 秒）发送完全相同的消息，仅处理第一次，防止网络重发或服务器插件重复广播导致重复回复。
- **消息内容过滤**  
  可阻止 AI 发送特定内容（如命令），支持正则。
- **回复条数与长度限制**  
  控制单次回复的聊天条数和每条消息最大字符数。
- **文生图功能**  
  使用 Agnes Image 模型生成图片，支持触发正则、独立冷却。
- **图床上传**  
  支持 Catbox、0x0.st、imgbb 及自定义图床，自动将生成的图片上传并发送链接。
- **聊天栏日志**  
  在聊天栏输出模组运行状态，可独立开关各类日志。
- **游戏内命令**  
  `/aichat` 系列命令，方便重载配置、清空上下文等。

---

## 安装要求

- **Minecraft**：26.1.2
- **模组加载器**：Fabric Loader >= 0.19.3
- **Fabric API**：0.155.2+26.1.2 或对应版本
- **Java**：25 或更高
- **AI 服务 API Key**（使用在线服务时需要）
- **图床 API Key**（如果使用 imgbb 等需要鉴权的图床）

---

## 安装步骤

1. 确认已安装 Fabric Loader 和 Fabric API。
2. 下载 `be-kongs-ai-chat-mod-1.2.0.jar`。
3. 将 jar 文件放入 `.minecraft/mods` 文件夹。
4. 启动游戏，模组会自动在 `.minecraft/config/be-kongs-ai-chat-mod.json` 生成默认配置。
5. 配置方式二选一：
   - 游戏内输入 `/aichat openconfig` 打开可视化配置界面（推荐）；或
   - 关闭游戏，手动编辑配置文件，填入你的 API 密钥、模型、图床、黑名单等。
6. 重新启动游戏，或使用 `/aichat reload` 命令热重载配置。

---

## 配置文件详解

文件位置：`.minecraft/config/be-kongs-ai-chat-mod.json`

### 聊天 AI 配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `true` | 模组总开关。 |
| `provider` | string | `"agnes"` | AI 提供商，可选 `openai`、`deepseek`、`moonshot`、`anthropic`、`ollama`、`agnes`、`custom`。 |
| `apiKey` | string | `""` | 聊天 API 密钥（通用，触发/自动回复未单独配置时使用）。 |
| `triggerApiKey` | string | `""` | 触发回复专用 API 密钥，留空则使用 `apiKey`。 |
| `scheduleApiKey` | string | `""` | 自动回复专用 API 密钥，留空则使用 `apiKey`。 |
| `baseUrl` | string | `""` | 自定义 API 基础地址，留空则使用 provider 默认地址。 |
| `model` | string | `"agnes-2.5-flash"` | 聊天模型名称。 |
| `temperature` | double | `0.7` | 采样温度（0.0~2.0）。 |
| `maxTokens` | int | `1024` | 单次请求最大 token 数。 |
| `maxReplyMessages` | int | `3` | AI 单次回复最多发送的聊天条数。 |
| `maxCharsPerMessage` | int | `100` | 每条消息最大字符数。 |
| `contextEnabled` | boolean | `true` | 是否启用上下文记忆。 |
| `contextLength` | int | `20` | 上下文保存的消息条数（0~100）。 |
| `triggerEnabled` | boolean | `true` | 是否启用正则触发回复。 |
| `triggerRegexList` | list | `["(?i)^(ai\|@ai)[，, ]"]` | 触发正则列表，任意一条匹配即触发回复。 |
| `triggerCooldownSeconds` | int | `10` | 触发回复冷却时间（秒）。 |
| `scheduleEnabled` | boolean | `true` | 是否启用定时自动回复。 |
| `scheduleIntervalSeconds` | int | `30` | 自动回复冷却时间（秒）。 |
| `restrictionEnabled` | boolean | `true` | 是否启用消息过滤。 |
| `blockedRegexPatterns` | list | `["^\\s*/", "^\\s*#"]` | 阻止 AI 发送的消息正则列表。 |
| `blockedPlayers` | list | `[]` | 玩家黑名单列表。黑名单中的玩家消息将被完全忽略。 |
| `whitelistMode` | boolean | `false` | 白名单模式。开启后仅白名单内玩家的消息会触发 AI。 |
| `whitelistedPlayers` | list | `[]` | 玩家白名单列表。仅在 `whitelistMode` 开启时生效。 |
| `stripChatPrefix` | boolean | `true` | 是否剥离聊天前缀（`<玩家名>`、`[称号]玩家名 »` 等）。服务器改过格式时可关闭。 |
| `filterGameEvents` | boolean | `true` | 是否过滤 GAME 事件（忽略非玩家消息）。服务器插件广播 GAME 消息时可关闭。 |
| `systemPrompt` | string | 略 | 系统提示词，控制 AI 行为。 |
| `timeoutSeconds` | int | `30` | 聊天 API 请求超时秒数。 |
| `retryCount` | int | `2` | 请求失败重试次数。 |
| `debugLog` | boolean | `true` | 是否输出调试日志到控制台。 |

### 聊天栏日志配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `chatLogEnabled` | boolean | `true` | 聊天栏日志总开关。 |
| `chatLogTrigger` | boolean | `true` | 触发回复时输出提示。 |
| `chatLogSchedule` | boolean | `true` | 自动回复时输出提示。 |
| `chatLogError` | boolean | `true` | 请求失败时输出错误。 |
| `chatLogBlocked` | boolean | `true` | 消息被过滤时输出警告。 |
| `chatLogReload` | boolean | `true` | 重载配置时输出提示。 |
| `chatLogClearContext` | boolean | `true` | 清空上下文时输出提示。 |
| `chatLogDebug` | boolean | `false` | 输出前缀剥离等调试信息。 |

### 文生图配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `imageGenerationEnabled` | boolean | `true` | 文生图功能总开关。 |
| `imageProvider` | string | `"agnes"` | 图像 API 提供商。 |
| `imageApiKey` | string | `""` | 图像 API 密钥，留空则使用聊天 API Key。 |
| `imageBaseUrl` | string | `""` | 图像 API 基础地址，留空则使用聊天 BaseUrl 或默认。 |
| `imageModel` | string | `"agnes-image-2.1-flash"` | 图像模型名称。 |
| `imageSize` | string | `"2K"` | 输出尺寸档位：`1K`、`2K`、`3K`、`4K`。 |
| `imageRatio` | string | `"1:1"` | 宽高比，如 `1:1`、`16:9`。 |
| `imageTimeoutSeconds` | int | `120` | 图像请求超时秒数，建议 60~360。 |
| `imageRetryCount` | int | `1` | 图像请求失败重试次数。 |
| `imageCooldownSeconds` | int | `30` | 图像生成冷却秒数。 |
| `imageTriggerRegex` | string | `"(?i)^(?:画\|生成图片\|img)[:： ]?(.*)$"` | 触发图像生成的正则，捕获组为提示词。 |

### 图床上传配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `imageUploaderType` | string | `"none"` | 图床类型：`none`、`catbox`、`0x0`、`imgbb`、`custom`。 |
| `imageUploaderUrl` | string | `""` | 自定义图床 API 地址。不同类型留空则使用默认端点。 |

**图床说明**：
- `none`：不经过图床，直接发送 Agnes 返回的原始 URL。
- `catbox`：使用 Catbox 官方 API，字段名 `fileToUpload`。
- `0x0`：使用 0x0.st 官方 API，字段名 `file`。
- `imgbb`：需要填写 `imageUploaderUrl`（包含 API Key），字段名 `image`。
- `custom`：需要填写 `imageUploaderUrl`，字段名 `file`，并自动尝试从 JSON 响应中提取 URL。

### 图片 URL 识别（Vision）配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `imageExtractEnabled` | boolean | `true` | 是否自动识别聊天中的图片 URL 并让 AI 描述图片内容。 |
| `imageExtractMergeMode` | boolean | `true` | 合并模式：`true`=把图片 URL 连同正文一起发给 AI；`false`=只发送提取到的 URL。 |
| `imageUrlRegex` | string | 见下 | 用于识别图片链接的正则（png/jpg/jpeg/gif/webp/bmp/svg）。 |

默认 `imageUrlRegex`：
```
(?i)https?://[^\s<>"']+\.(?:png|jpg|jpeg|gif|webp|bmp|svg)[^\s<>"']*$
```

> 需要所使用的 AI 提供商支持多模态（视觉）输入，否则描述功能不可用。
>
> 识图时会把**玩家的实际提问**（去掉图片链接后的文字，如"图中有什么"）连同图片一起发给 AI，而不是固定的"请描述图片"；图片消息始终作为请求的最后一条，避免被上下文里的其它消息带偏。
>
> 识图结果与普通 AI 回复走同一管线：分段发送、拦截过滤；发送前会自动剥离 `§` 分节符等非法聊天字符，避免被服务器以 "Illegal characters in chat" 踢出。

### 特殊触发词（聊天改配置）

在聊天中直接发送以下短语即可修改模组状态，**不会触发 AI 回复**。**自己发送的短语同样生效**（AI 消息回声不会被误判），匹配时自动忽略首尾空白。开关类短语触发后会在聊天栏回显**新的开/关状态**：

| 触发短语 | 动作 |
|----------|------|
| `关闭触发回复` | 关闭正则触发回复（`DISABLE_TRIGGER`） |
| `开启触发回复` | 开启正则触发回复（`ENABLE_TRIGGER`） |
| `清空上下文` | 清空上下文记忆（`CLEAR_CONTEXT`） |
| `重载配置` | 重新加载配置文件（`RELOAD_CONFIG`） |
| `关闭模组` / `开启模组` | 关闭 / 开启模组总开关（`DISABLE_ENABLED` / `ENABLE_ENABLED`） |
| `关闭自动回复` / `开启自动回复` | 关闭 / 开启定时自动回复（`DISABLE_SCHEDULE` / `ENABLE_SCHEDULE`） |
| `关闭文生图` / `开启文生图`（图片生成 / 画图） | 关闭 / 开启文生图（`DISABLE/ENABLE_IMAGE_GENERATION`） |
| `关闭图片识别` / `开启图片识别`（识图 / 看图） | 关闭 / 开启图片识别 Vision（`DISABLE/ENABLE_IMAGE_EXTRACT`） |

开关类功能一律拆成**"开启/关闭"两条独立触发词**（不使用单一"切换"词），语义更明确。

可在配置文件的 `configTriggers` 列表中自定义扩展，每条包含 `description`（描述）、`regex`（触发正则）、`action`（动作），**`regex` 完整支持正则表达式**。可用动作：`DISABLE_TRIGGER`、`ENABLE_TRIGGER`、`CLEAR_CONTEXT`、`RELOAD_CONFIG`、`TOGGLE_ENABLED`、`DISABLE_ENABLED`、`ENABLE_ENABLED`、`DISABLE_SCHEDULE`、`ENABLE_SCHEDULE`、`DISABLE_IMAGE_GENERATION`、`ENABLE_IMAGE_GENERATION`、`DISABLE_IMAGE_EXTRACT`、`ENABLE_IMAGE_EXTRACT`。（旧版 `TOGGLE_SCHEDULE`/`TOGGLE_IMAGE_GENERATION`/`TOGGLE_IMAGE_EXTRACT` 仅为兼容保留，加载时会自动迁移为对应的开/关两条触发词。）

默认触发词按"动作补齐"：重载配置时，只要某个内置动作还没有对应触发词，就会自动补上一条默认短语（已有自定义的不会重复添加）。

特殊触发词也可以在**配置 UI 的`触发`页签**里直接管理：编辑描述/正则、点击动作循环切换、删除单条、底部"＋ 添加特殊触发词"。

```json
"configTriggers": [
  { "description": "关闭触发回复", "regex": "(?i)^[（(]?关闭触发回复[）)]?$", "action": "DISABLE_TRIGGER" },
  { "description": "开启文生图", "regex": "(?i)^[（(]?开启(?:文生图|图片生成|画图)[）)]?$", "action": "ENABLE_IMAGE_GENERATION" },
  { "description": "关闭文生图", "regex": "(?i)^[（(]?关闭(?:文生图|图片生成|画图)[）)]?$", "action": "DISABLE_IMAGE_GENERATION" }
]
```

---

## 游戏内命令

| 命令 | 功能 |
|------|------|
| `/aichat openconfig` | 打开游戏内可视化配置界面。 |
| `/aichat reload` | 重新加载配置文件。 |
| `/aichat toggle` | 开关整个模组。 |
| `/aichat status` | 查看当前配置状态。 |
| `/aichat clearcontext` | 清空当前上下文记忆。 |

### 配置界面热键

除了命令，还可以用**热键**直接打开/关闭配置界面：默认按 **`C`** 键打开，已打开时再按一次关闭。  
热键可在配置 UI `常规`页签的"配置界面热键"或配置文件 `uiHotkey` 中修改（填写按键名，如 `key.keyboard.c`、`key.keyboard.keypad.0` 等）。旧版默认键 `key.keyboard.m` 会在加载时自动迁移为 `key.keyboard.c`。

---

## 使用教程

### 1. 触发回复

默认触发正则列表 `triggerRegexList` 含一条 `(?i)^(ai|@ai)[，, ]`，即消息以“AI”或“@AI”开头，后跟逗号、中文逗号或空格。你可以在列表中加入多条正则，**任意一条匹配**即触发。  
示例：
- `AI, 你好`
- `@AI 这是什么`
- `ai，帮我查一下`

当消息匹配时，AI 立即回复（若冷却已过）。触发词会被自动移除，AI 看到的是去除触发词后的内容。

### 2. 自动回复

若开启 `scheduleEnabled`，模组会每 `scheduleIntervalSeconds` 秒检查是否有未处理的新玩家消息，若有则回复最后一条。  
注意：已触发过回复或图像生成的消息不会被自动回复再次处理。

### 3. 独立 API 密钥

若希望触发回复和自动回复使用不同的 AI 服务或密钥，可分别配置：
```json
"triggerApiKey": "sk-触发密钥",
"scheduleApiKey": "sk-自动密钥"
```

### 4. 游戏内配置界面

输入 `/aichat openconfig` 打开配置 UI：

- 顶部五个页签：`1.常规`、`2.触发`、`3.图像`、`4.玩家`、`5.聊天`，按数字键 `1`~`5` 或鼠标点击切换。
- 左键点击开关项（ON/OFF）立即切换并保存；点击文本/数字项进入编辑，输入后按 `Enter` 确认，`ESC` 取消。
- 列表类选项（触发正则、黑名单、白名单、拦截正则）用**分号**分隔多个条目。
- 滚轮滚动长列表；按 `0` 一键恢复默认配置；`ESC` 关闭界面。
- 所有修改即时写入配置文件并热重载，无需 `/aichat reload`。

### 5. 图片 URL 识别

开启 `imageExtractEnabled` 后，聊天消息中出现图片链接（如 `https://example.com/cat.png`）时，模组会自动把图片交给支持视觉的 AI 模型识别，并把描述内容发到聊天。可与正文合并发送（`imageExtractMergeMode`）。

### 6. 特殊触发词

直接在聊天里发送 `关闭触发回复` / `开启触发回复` / `清空上下文` / `重载配置`，即可快速修改模组状态，无需打开菜单或改配置文件。匹配这些短语时不会触发 AI 回复。
