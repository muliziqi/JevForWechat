# JevForWechat A1

目标环境：Android 15 + 微信 Android 8.0.78 + 无 Root + LSPatch 1.2 / Vector runtime。

## 这一版做什么

A1 = A0 的「长按菜单探针」跑通之后的正式功能版：

1. 只注入 `com.tencent.mm`。
2. **自动分析**：Hook 微信聊天列表（ListView / RecyclerView，不写死混淆类名），
   在列表底部最近 3 条「对方消息」气泡下方，自动插入灰色 **Jev 分析卡片**：

   ```text
   Jev:
   她真的在问"你记不记得"吗？
   - 是: 7%
   - 不是: 93%
   当前真实意图
   - 想确认你在不在乎她: 72%
   - 单纯考验记忆力: 8%
   - 生气想吵架: 20%
   危险等级: 9/10
   最佳动作
   - 搜索聊天记录: 91%
   - 硬猜: 4%
   - 转移话题: 1%
   建议动作
   先翻聊天记录再回她
   DeepSeek 建议回复
   我记得，你说周末想吃火锅对吧
   （分析由 Jev 出，回复由 DeepSeek 出）
   ```

   - 点按卡片：折叠 / 展开详情；
   - 长按卡片：重新分析；
   - 分析结果按消息内容缓存，滚动回看不重复请求。

3. **手动分析**：长按任意消息 → 菜单里点「Jev分析」，弹窗显示分析结果（对旧消息也有用）。
4. **设置**：长按任意消息 → 菜单里点「Jev设置」，粘贴 API 配置。

## 双模型链路：Jev 分析 + DeepSeek 回复

- **Jev 决策分析**：OpenRouter 的 `typesafe/jev-router`（TypeSafe 的 Jev 模型），输出意图概率 / 危险等级 / 最佳动作；
- **DeepSeek 建议回复**：DeepSeek 官方接口（或 OpenRouter 上的 `deepseek/deepseek-chat`），单独起草一条可直接发送的回复，追加在卡片底部（分析先显示，回复稍后追加）。

在微信里长按任意消息 → **Jev设置**，粘贴：

```json
{
  "analysis": {
    "apiUrl": "https://openrouter.ai/api/v1/chat/completions",
    "apiKey": "sk-or-你的OpenRouter Key",
    "model": "typesafe/jev-router"
  },
  "reply": {
    "apiUrl": "https://api.deepseek.com/chat/completions",
    "apiKey": "sk-你的DeepSeek Key",
    "model": "deepseek-chat"
  }
}
```

说明：

- 不需要回复功能 → 整段删掉 `reply`，卡片只显示 Jev 分析。
- 只想用一个 Key → 把 `reply` 的 `apiUrl` 也填 OpenRouter，`model` 填 `deepseek/deepseek-chat`，`apiKey` 与 `analysis` 相同。
- 也支持平铺格式 `{"apiUrl":..., "apiKey":..., "model":...}`（两个环节共用同一接口）。
- 所有接口均为 OpenAI Chat Completions 兼容协议；聊天内容只发送到你自己配置的接口。

## 防误伤设计

- 注入只在「同一个列表里见过右侧消息（你自己发过言）」后才开始——通讯录、设置等
  全部文字在左的列表不会被打扰。
- 时间戳、居中系统消息（撤回提示等）自动跳过。
- 只处理列表底部 3 条，不会滚动历史时批量触发 API。

## 构建

- JDK 17 / Gradle 9.6.0 / AGP 9.4.0 / compileSdk 36
- 本地：Android Studio 打开工程直接 Build。
- 手机党：推送到 GitHub 后，Actions 自动构建，进 run 记录下载 Artifact `JevForWechat-A1-debug`，
  解压得到 `app-debug.apk`。

## 无 Root 加载（LSPatch）

1. 构建本模块 APK 并安装。
2. 用 LSPatch Patch 官方微信 8.0.78（与本机架构匹配的 APK），把本模块作为 embedded/integrated module 加入。
3. 安装 patched 微信，登录。
4. 长按一条文字消息：菜单出现「Jev分析」「Jev设置」→ 先去「Jev设置」填 API。
5. 回到聊天，让对面发一条消息 → 气泡下方应出现「⚡ Jev 分析中…」随后变成分析卡片。

> 提示：LSPatch 会修改并重新签名微信 APK，建议用小号/测试号。微信升级后需要重新 Patch。

## 排错

如果卡片不出现，按顺序检查 logcat / LSPatch 日志里 `JevForWechat` 的输出：

- `hooked setAdapter on androidx.recyclerview.widget.RecyclerView` — RecyclerView Hook 是否挂上；
- `hooked onBindViewHolder on <adapter类名>` — 聊天 adapter 是否被钩住（没有则说明该版本聊天页既不是
  ListView 也不是标准 RecyclerView，A2 需要补探针）；
- `no vertical container found for item` — 卡片没有找到可插入的容器（布局结构变化）；
- `inserted Jev menu item` — A0 长按菜单是否可用（兜底功能）。

## 版本历史

- **A0 (0.1.0)**：长按菜单注入「Jev分析」，点击 Toast 显示捕获文字。纯诊断，不联网。
- **A1 (0.2.0)**：聊天页消息下方自动插入 Jev 分析卡片；接入 OpenAI 兼容接口；
  「Jev设置」图形化配置；「Jev分析」升级为完整分析弹窗。
- **A1.1 (0.2.1)**：双模型链路——Jev（OpenRouter `typesafe/jev-router`）负责决策分析，
  DeepSeek 负责起草建议回复；兼容平铺/嵌套两种配置；Jev 原生文本输出兜底解析。
