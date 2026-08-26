# 更新日志（Changelog）

## [1.2.0] - 26.1.2

本次版本聚焦**图片能力**、**触发系统增强**、**游戏内配置界面**与大量 **26.1.2 适配修复**。

### ✨ 新增功能

#### 图片能力
- **图片 URL 识别（Vision）**：聊天中出现图片链接（png/jpg/jpeg/gif/webp/bmp/svg）时，自动调用多模态视觉接口，让 AI 描述图片内容。
  - 新增配置 `imageExtractEnabled`（开关）、`imageExtractMergeMode`（合并模式：URL 连同正文一起发送）、`imageUrlRegex`（图片链接识别正则）。
  - OpenAI 兼容接口与 Anthropic 均已支持多模态消息（文本 + 图片 URL）。
- **图片自动上传图床**：AI 生成的图片自动上传到图床，并把可访问链接发送到聊天。
  - 新增配置 `imageUploaderType`（`none`/`catbox`/`0x0`/`imgbb`/`custom`）、`imageUploaderUrl`。

#### 触发系统
- **多正则触发列表**：`triggerRegex`（单条）升级为 `triggerRegexList`（列表），任意一条匹配即触发，支持配置多个触发词。
- **特殊触发词（聊天改配置）**：新增 `configTriggers` 列表，聊天中直接发送短语即可修改模组状态，且不触发 AI 回复。
  - 内置动作：`DISABLE_TRIGGER`/`ENABLE_TRIGGER`（关/开触发回复）、`CLEAR_CONTEXT`（清空上下文）、`RELOAD_CONFIG`（重载配置）、`TOGGLE_ENABLED`（切换总开关，兼容保留）、`DISABLE/ENABLE_ENABLED`（关/开模组）、`DISABLE/ENABLE_SCHEDULE`（关/开自动回复）、`DISABLE/ENABLE_IMAGE_GENERATION`（关/开文生图）、`DISABLE/ENABLE_IMAGE_EXTRACT`（关/开图片识别）。
  - **开关一律拆分为"开启/关闭"两条独立触发词**（不再用单一"切换"词）：`关闭/开启触发回复`、`清空上下文`、`重载配置`、`关闭/开启模组`、`关闭/开启自动回复`、`关闭/开启文生图（图片生成/画图）`、`关闭/开启图片识别（识图/看图）`，均支持自定义正则扩展。
  - 特殊触发词完善：**自己发送的触发短语同样生效**（AI 消息回声仍被忽略）；匹配前自动去除首尾空白；动作触发后在聊天栏回显**新的开/关状态**；默认触发词"按动作补齐"且旧版"切换"词在加载时自动迁移为开/关两条，不产生重复项。
  - **特殊触发词已接入配置 UI**（`触发`页签）：每条触发词可编辑描述/正则、点击循环切换动作、单条删除，底部有"添加特殊触发词"按钮。

#### 玩家过滤
- **白名单模式**：新增 `whitelistMode` 与 `whitelistedPlayers`，开启后仅白名单内玩家的消息会触发 AI。
- 保留黑名单 `blockedPlayers`，黑名单玩家消息被完全忽略。

#### 聊天格式兼容
- **`stripChatPrefix`**：是否剥离聊天前缀（`<玩家名>`、`[称号]玩家名 »` 等），服务器改过格式时可关闭。
- **`filterGameEvents`**：是否过滤 GAME 事件（忽略非玩家消息），服务器插件广播 GAME 消息时可关闭。

#### 游戏内配置界面
- **`/aichat openconfig`**：打开可视化配置 UI，五个页签（`常规`/`触发`/`图像`/`玩家`/`聊天`）覆盖全部设置。
  - 数字键 `1`~`5` 或鼠标点击切换页签；左键点击开关项立即切换并保存；点击文本/数字项进入编辑，`Enter` 确认、`ESC` 取消。
  - 列表类选项（触发正则、黑白名单、拦截正则）用**分号**分隔多个条目。
  - 滚轮滚动长列表；按 `0` 一键恢复默认配置；所有修改即时写入配置并热重载。
  - 支持中文等非 ASCII 字符输入。
  - **特殊触发词可在 UI 中管理**（`触发`页签）：编辑描述/正则、点击循环切换动作、删除单条、一键添加。
- **配置界面热键**：新增按键直接打开/关闭配置 UI（默认 `C` 键，游戏中再按一次关闭）。按键名可在`常规`页签的"配置界面热键"或配置文件 `uiHotkey` 中修改（如 `key.keyboard.c`）；旧默认键 `key.keyboard.m` 加载时自动迁移为 `key.keyboard.c`。

### 🐛 Bug 修复

- **修复识图答非所问（回答别的消息）**：`buildContextForImage` 原先只在上下文为空时才把"图片消息"加入请求，上下文非空时图片与提问被整个丢弃，AI 只会回答上下文里最后一条无关消息（如刷屏的问答/quiz）。现已确保图片多模态消息**始终作为请求最后一条**发出。
- **识图改用玩家实际提问**：不再固定发送"请描述这张图片的内容。"，而是提取去掉 URL 后的原文（如"图中有什么"）作为提问一并传给 AI；识图完成后把该提问以纯文本记入上下文（不重复携带图片），保持对话连贯。
- **修复 `/aichat openconfig` 只弹提示、界面不显示**：聊天界面提交命令后会关闭自己（把当前界面置空），同步打开的配置界面被立即覆盖。现改为延迟一个客户端任务后再打开配置界面。
- **修复 AI 识图回复导致被服务器踢出**：AI 回复可能包含 `§` 分节符或控制字符，Java 版聊天视其为非法字符（踢出提示 "Illegal characters in chat"，服务端报 `disconnect.endOfStream`）。现在所有发送到服务器聊天的消息统一净化非法字符（`§`、控制字符、DEL；换行/制表符转空格）。
- **AI 识图回复接入统一回复管线**：识图结果现在与普通 AI 回复一致——分段发送、拦截过滤、非法字符净化、上下文记录（原先是整段裸发送）。
- **修复配置界面不显示**：26.1.2 将渲染回调从 `render(GuiGraphics,…)` 重构为 `extractRenderState(GuiGraphicsExtractor,…)`，旧覆写静默失效导致 `/aichat openconfig` 只弹提示、界面空白。现已实现完整渲染（背景、页签、可滚动选项列表、编辑高亮、操作提示）。
- **修复 26.1.2 输入事件编译错误**：输入事件改为 record 风格访问器——`KeyEvent.key()`、`CharacterEvent.codepointAsString()`/`isAllowedChatCharacter()`、`MouseButtonEvent.button()/x()/y()`；`mouseClicked(MouseButtonEvent, boolean doubleClick)` 签名适配。
- **修复配置界面修改不生效**：各选项 `setValue` 未调用 setter、列表选项持有配置的脱钩副本，导致 UI 改动无法落盘。现已为 `Toggle/Text/Int/Double` 选项接通 setter，并为列表选项新增 `commit()` 回写。
- **修复中文输入**：`charTyped` 原仅接受 ASCII，现改用 codepoint API，支持中文等字符；退格按码点删除，避免破坏代理对。
- **修复恢复默认不完整**：`copyDefaults` 补全全部配置字段（含图像、图片识别、特殊触发词、白名单、兼容开关等）。
- **清理无用代码**：删除 8 个无引用的空 UI stub 类，减小产物体积。
- **修复 README 末尾未闭合的代码块**。
- **修复重复的括号前缀损坏文件**导致编译失败的问题（构建脚本排除 + 清理）。

### 🔧 其他 / 开发
- 新增 `dumpClassApi` Gradle 任务：`gradlew dumpClassApi -Pclasses=net.minecraft.…` 通过反射导出 26.1.2 任意类的方法/字段签名，便于核对 API。
- 目标环境：Minecraft 26.1.2、Fabric Loader ≥ 0.19.3、Fabric API 0.155.2+26.1.2、Java 25。

## [1.1.7] - 基线

首个可用版本：多 AI 提供商、触发/自动回复独立密钥、上下文记忆、正则触发、定时回复、独立冷却、玩家黑名单、自身消息过滤、去重、消息过滤、回复条数/长度限制、文生图、聊天栏日志、`/aichat` 命令。
