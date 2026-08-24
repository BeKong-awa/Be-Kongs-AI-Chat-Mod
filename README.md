# Be Kong's AI Chat Mod

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
- **玩家黑名单**  
  支持配置黑名单玩家名，黑名单玩家的消息将被完全忽略（不触发回复、不参与自动回复）。
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
2. 下载 `be-kongs-ai-chat-mod-1.1.5.jar`。
3. 将 jar 文件放入 `.minecraft/mods` 文件夹。
4. 启动游戏，模组会自动在 `.minecraft/config/be-kongs-ai-chat-mod.json` 生成默认配置。
5. 关闭游戏，编辑配置文件，填入你的 API 密钥、模型、图床、黑名单等。
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
| `triggerRegex` | string | `"(?i)^(ai\|@ai)[，, ]"` | 触发正则。匹配后立即回复。 |
| `triggerCooldownSeconds` | int | `10` | 触发回复冷却时间（秒）。 |
| `scheduleEnabled` | boolean | `true` | 是否启用定时自动回复。 |
| `scheduleIntervalSeconds` | int | `30` | 自动回复冷却时间（秒）。 |
| `restrictionEnabled` | boolean | `true` | 是否启用消息过滤。 |
| `blockedRegexPatterns` | list | `["^\\s*/", "^\\s*#"]` | 阻止 AI 发送的消息正则列表。 |
| `blockedPlayers` | list | `[]` | 玩家黑名单列表。黑名单中的玩家消息将被完全忽略。 |
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

---

## 游戏内命令

| 命令 | 功能 |
|------|------|
| `/aichat reload` | 重新加载配置文件。 |
| `/aichat toggle` | 开关整个模组。 |
| `/aichat status` | 查看当前配置状态。 |
| `/aichat clearcontext` | 清空当前上下文记忆。 |

---

## 使用教程

### 1. 触发回复

默认触发正则 `(?i)^(ai|@ai)[，, ]`，即消息以“AI”或“@AI”开头，后跟逗号、中文逗号或空格。  
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