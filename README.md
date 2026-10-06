# FFmpeg Command Mod

> 在 Minecraft 1.21.1 (NeoForge) 里直接调用系统 FFmpeg，为 CC: Tweaked 电脑提供音频/视频转/图片换能力。

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.253-orange)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

---

## 📖 简介

**FFmpeg Command Mod** 让你能在 Minecraft 中通过聊天命令或 CC: Tweaked 电脑的 Lua 脚本调用外部 FFmpeg。支持任意 FFmpeg 参数的异步执行、并发控制、中文文件名处理，并提供了完整的进度查询和输出回显。

### 主要功能

- ✅ **聊天命令**：`/ffmpeg` 系列命令，支持任意 FFmpeg 操作
- ✅ **CC: Tweaked Lua API**：通过 `require("ffmpeg")` 加载 API
- ✅ **异步多线程**：最多同时运行 8 个 FFmpeg 任务，不阻塞游戏
- ✅ **中文文件名**：URL 编码 + UTF-8 解码 + UTF-8 模组渲染，完美支持中文/空格/特殊字符
- ✅ **任务状态查询**：任务 ID、状态、输出实时查询

---

## 🛠️ 环境要求

| 组件 | 版本要求 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Java | 21 |
| FFmpeg | 任意版本（**必须已安装并加入 PATH**，或在配置里指定路径） |
| CC: Tweaked , CC-UTF8-Compat | 可选，只能输入聊天框命令 |

### 安装 FFmpeg

FFmpeg **不在模组内包含**，需要你手动安装：

从 [ffmpeg.org](https://ffmpeg.org/download.html) 下载，解压后把 `bin` 目录加入系统 PATH。

在命令行执行 `ffmpeg -version`，如果能看到版本信息，就说明安装成功。

---

## 📦 安装

1. 从 [Releases] 页面下载。
2. 放入 Minecraft 的 `mods` 文件夹。
3. 启动游戏。

首次启动后，游戏运行目录下会自动创建 `ffmpeg` 文件夹，所有 FFmpegmod 操作都在这个文件夹里进行。

---

## 🎮 聊天命令

所有命令都需要 **OP 权限**。

### `/ffmpeg help`

显示所有命令和用法。

### `/ffmpeg version`

检查 FFmpeg 是否可用，回显版本信息。

### `/ffmpeg files`

列出 `ffmpeg` 文件夹里的所有实际文件名。**点击文件名可复制到剪贴板。**

### `/ffmpeg dfpwm "<输入>" "<输出>"`

将音频文件转换为 DFPWM 格式（单声道 48kHz）。

```
/ffmpeg dfpwm "音乐.flac" "音乐.dfpwm"
/ffmpeg dfpwm "music.mp3" "music.dfpwm"
```

**注意**：所有文件名必须用双引号包裹才能执行文件操作。

### `/ffmpeg exec <参数>`

执行任意 FFmpeg 命令。

```
/ffmpeg exec -i "音乐.flac" -c:a libmp3lame -q:a 2 "音乐.mp3"
/ffmpeg exec -i "video.mp4" -vf "fps=10,scale=320:-1" "out.gif"
/ffmpeg exec -version
```

**注意**：含空格或中文的文件名请用双引号包裹。`exec` 会自动检查 `-i` 后面的输入文件是否存在。

---

## 💻 CC: Tweaked Lua API

在 CC 电脑上通过 `require("ffmpeg")` 加载 API。

### API 方法总览

| 方法 | 参数 | 返回值 | 说明 |
|---|---|---|---|
| `listFiles()` | 无 | 表 | 列出 `ffmpeg` 文件夹里的所有文件（URL 编码） |
| `fileSize(name)` | 文件名 | 数字 | 获取文件字节数 |
| `readFileChunk(name, offset, length)` | 名, 偏移, 长度 | 字符串 | 流式读取文件的一块（最大 64 KB） |
| `convertAsync(input, output)` | 输入, 输出 | 任务 ID | 异步把音频转成 DFPWM |
| `execAsync(args)` | 参数字符串 | 任务 ID | 异步执行任意 FFmpeg 命令 |
| `getStatus(taskId)` | 任务 ID | 状态字符串 | `running` / `success` / `failed` |
| `getOutput(taskId)` | 任务 ID | 字符串表 | 获取任务的输出行 |
| `version()` | 无 | 字符串 | 获取 FFmpeg 版本信息 |

---

### 📚 API 详细用法

#### `listFiles()`

列出 `ffmpeg` 文件夹里的所有文件。返回的表里每个元素都是 **URL 编码的文件名**。

```lua
local ffmpeg = require("ffmpeg")
for i, name in ipairs(ffmpeg.listFiles()) do
    print(i, name)   -- 例如: 1  %e9%b8%a1%e4%bd%a0%e5%a4%aa%e7%be%8e.flac
end
```

### 🌏 中文文件名处理

`listFiles()` 返回的是 **URL 编码字符串**，这是为了保证中文字符在 Java 和 Lua 之间传递时不会丢失。

#### 加入URL 解码

```lua
-- URL 解码仅用于显示
local function urlDecode(s)
    if not s then return "" end
    s = s:gsub("%+", " ")
    return (s:gsub("%%(%x%x)", function(hex)
        return string.char(tonumber(hex, 16))
    end))
end

local ffmpeg = require("ffmpeg")
for i, name in ipairs(ffmpeg.listFiles()) do
    print(i, urlDecode(name))   
end
```

**调用 API 时**：必须传**原始 URL 编码字符串**，不要传解码后的。

```lua
local files = ffmpeg.listFiles()
local input = files[filesname]                             -- URL 编码
local output = input:gsub("%.[^.]+$", ".dfpwm")            -- 保持编码
ffmpeg.convertAsync(input, output)                         -- ✅ 正确
-- ffmpeg.convertAsync(urlDecode(input), ...)              -- ❌ 错误
```

**过滤特定格式**：

```lua
local function urlDecode(s) -- URL 解码仅用于显示
    if not s then return "" end
    s = s:gsub("%+", " ")
    return (s:gsub("%%(%x%x)", function(hex)
        return string.char(tonumber(hex, 16))
    end))
end

local ffmpeg = require("ffmpeg")
for _, name in ipairs(ffmpeg.listFiles()) do
    if name:lower():match("%.flac$") then --只显示 FLAC 文件
        print("FLAC 文件: " .. urlDecode(name))
    end
end
```

---

#### `fileSize(name)`

获取文件的字节数。用于计算播放进度或判断文件大小。

```lua
local function urlDecode(s) -- URL 解码仅用于显示
    if not s then return "" end
    s = s:gsub("%+", " ")
    return (s:gsub("%%(%x%x)", function(hex)
        return string.char(tonumber(hex, 16))
    end))
end

local ffmpeg = require("ffmpeg")
for _, name in ipairs(ffmpeg.listFiles()) do
    if name:lower():match("%.dfpwm$") then --只显示 DFPWM 文件
        local size = ffmpeg.fileSize(name)
        print(urlDecode(name) .. " 文件大小: " .. size .. " 字节")
        print(urlDecode(name) .. " 大约时长: " .. math.floor(size / 6000) .. " 秒") -- DFPWM 约 6KB/秒
    end
end
```

**注意**：必须是返回的原始 URL 编码字符串。

---

#### `readFileChunk(name, offset, length)`

流式读取文件的一块内容，**不占用 CC 电脑磁盘**。常用于边读边播放 `.dfpwm` 文件。

| 参数 | 类型 | 说明 |
|---|---|---|
| `name` | 字符串 | URL 编码的文件名 |
| `offset` | 数字 | 起始字节偏移（从 0 开始） |
| `length` | 数字 | 读取长度（1 ~ 65536） |

**返回值**：Lua 字符串（二进制安全）。如果 `offset` 超出文件末尾，返回空字符串。

**完整播放示例**：

```lua
local dfpwm = require("cc.audio.dfpwm")
local ffmpeg = require("ffmpeg")
local speaker = peripheral.find("speaker")

if not speaker then
    print("未找到扬声器！")
    return
end

local function urlDecode(s) -- URL 解码，仅用于显示
    if not s then return "" end
    s = s:gsub("%+", " ")
    return (s:gsub("%%(%x%x)", function(hex)
        return string.char(tonumber(hex, 16))
    end))
end

local ffmpeg = require("ffmpeg")
for _, name in ipairs(ffmpeg.listFiles()) do
    if name:lower():match("%.dfpwm$") then --只显示 DFPWM 文件
        print("DFPWM 文件: " .. urlDecode(name))
    end
end

local function play(name) -- 播放函数（循环外定义一次）
    local size = ffmpeg.fileSize(name)
    local offset = 0 --调节初始播放进度
    local CHUNK = 16 * 1024 --读取文件大小
    local decoder = dfpwm.make_decoder()

    print("正在播放: " .. urlDecode(name))

    while offset < size do
        local chunk = ffmpeg.readFileChunk(name, offset, CHUNK)
        if #chunk == 0 then break end

        local buffer = decoder(chunk)
        if buffer and #buffer > 0 then
            while not speaker.playAudio(buffer) do
                os.sleep(0.01)
            end
        end
        offset = offset + #chunk
    end

    print("播放结束: " .. urlDecode(name))
end

for _, name in ipairs(ffmpeg.listFiles()) do -- 遍历所有 .dfpwm 文件并依次播放
    if name:lower():match("%.dfpwm$") then
        play(name)
    end
end
```

---

#### `convertAsync(input, output)`

异步把音频转成 DFPWM 格式（单声道 48kHz）**返回任务 ID。**

| 参数 | 类型 | 说明 |
|---|---|---|
| `input` | 字符串 | 输入文件（URL 编码） |
| `output` | 字符串 | 输出文件（URL 编码） |

**返回值**：任务 ID（字符串）

**完整转换示例**：

```lua
local function urlDecode(s) -- URL 解码仅用于显示
    if not s then return "" end
    s = s:gsub("%+", " ")
    return (s:gsub("%%(%x%x)", function(hex)
        return string.char(tonumber(hex, 16))
    end))
end

local ffmpeg = require("ffmpeg")

local files = {}
for _, name in ipairs(ffmpeg.listFiles()) do
    if name:lower():match("%.flac$") then -- 所有 FLAC 文件
        table.insert(files, name)
    end

end

print("=== FLAC 文件列表 ===") -- 显示列表
for i, name in ipairs(files) do
    print(i .. ". " .. urlDecode(name))
end
print("==================")

io.write("请输入要转换的文件序号: ")
local input = read() -- 输入序号
local index = tonumber(input)

local inputEncoded = files[index] -- 从列表里取对应的文件（URL 编码的原名）

local outputEncoded = inputEncoded:gsub("%.[^.]+$", ".dfpwm") -- 生成输出文件名：把 .flac 替换成 .dfpwm

print("将转换: " .. urlDecode(inputEncoded))
print("输出为: " .. urlDecode(outputEncoded))

local ok, taskIdOrErr = pcall(ffmpeg.convertAsync, inputEncoded, outputEncoded) -- 启动异步转换
local taskId = taskIdOrErr
print("任务 ID: " .. taskId)
```

---

#### `execAsync(args)`

异步执行任意 FFmpeg 命令。**这是功能最强大的方法**，可以实现任意格式转换。

| 参数 | 类型 | 说明 |
|---|---|---|
| `args` | 字符串 | FFmpeg 参数字符串，支持引号 |

**返回值**：任务 ID（字符串）

**完整转换示例**

```lua
local function urlDecode(s) -- URL 解码仅用于显示
    if not s then return "" end
    s = s:gsub("%+", " ")
    return (s:gsub("%%(%x%x)", function(hex)
        return string.char(tonumber(hex, 16))
    end))
end

local ffmpeg = require("ffmpeg")

local files = {}
for _, name in ipairs(ffmpeg.listFiles()) do
    if name:lower():match("%.flac$") then -- 所有 FLAC 文件
        table.insert(files, name)
    end

end

print("=== FLAC 文件列表 ===") -- 显示列表
for i, name in ipairs(files) do
    print(i .. ". " .. urlDecode(name))
end
print("==================")

io.write("请输入要转换的文件序号: ")
local input = read() -- 输入序号
local index = tonumber(input)

local inputEncoded = files[index] -- 从列表里取对应的文件（URL 编码的原名）

local outputEncoded = inputEncoded:gsub("%.[^.]+$", ".mp3") -- 生成输出文件名：把 .flac 替换成 .mp3

print("将转换: " .. urlDecode(inputEncoded))
print("输出为: " .. urlDecode(outputEncoded))

local args = '-i "' .. inputEncoded .. '" -c:a libmp3lame -q:a 2 "' .. outputEncoded .. '"' -- 输入参数
local ok, taskIdOrErr = pcall(ffmpeg.execAsync, (args))
local taskId = taskIdOrErr
print("任务 ID: " .. taskId)
```

**常用参数**：

```lua
-- FLAC 转 MP3
local args = '-i "%E6%B1%9F%E5%8D%97.flac" -c:a libmp3lame -q:a 2 "%E6%B1%9F%E5%8D%97.mp3"'
local id = ffmpeg.execAsync(args)

-- JPG 转 PNG
local id = ffmpeg.execAsync('-i "photo.jpg" -frames:v 1 -y "photo.png"')

-- 视频转 GIF
local id = ffmpeg.execAsync('-i "video.mp4" -vf "fps=10,scale=320:-1" "out.gif"')
```

**安全限制**：`execAsync` 会自动拦截以下参数：
- 路径穿越：`..` 分量
- 绝对路径：`/etc/passwd`、`C:\Windows` 等
- UNC 路径：`\\server\share`
- 控制字符和 Windows 保留字符（`< > : " | ? *`）

---

#### `getStatus(taskId)`

查询异步任务的当前状态。

**返回值**：
- `"running"`：正在执行
- `"success"`：执行成功
- `"failed"`：执行失败
- `LuaException`：任务不存在或已过期（超过 5 分钟）

```lua
local ok, status = pcall(ffmpeg.getStatus, taskId)
if ok then
    print("状态: " .. status)
else
    print("查询失败: " .. tostring(status))
end
```

**推荐用 `pcall` 包裹**，因为任务过期会抛异常。

---

#### `getOutput(taskId)`

获取任务的 FFmpeg 输出行（最多 100 行）。

```lua
local output = ffmpeg.getOutput(taskId)
for i, line in ipairs(output) do
    print(i .. ": " .. line)
end
```

**用途**：任务失败时查看 FFmpeg 报错信息，便于排查。

---

#### `version()`

获取 FFmpeg 版本信息。

```lua
print(ffmpeg.version())
```

---

### 🔄 多线程工作流示例

```lua
local ffmpeg = require("ffmpeg")
local function urlDecode(s) -- URL 解码函数
    if not s then return "" end
    s = s:gsub("%+", " ")
    return (s:gsub("%%(%x%x)", function(hex)
        return string.char(tonumber(hex, 16))
    end))
end

local function scanFlac()
    local result = {}
    local ok, files = pcall(ffmpeg.listFiles)
    if not ok then
        print("无法列出文件: " .. urlDecode(tostring(files)))
        return result
    end
    for _, name in ipairs(files) do
        if name:lower():match("%.flac$") then
            table.insert(result, name)
        end
    end
    return result
end

local files = scanFlac()
if #files == 0 then
    print("ffmpeg 文件夹里没有 .flac 文件。")
    print("请把 FLAC 文件放入游戏目录下的 ffmpeg 文件夹。")
    return
end

print("=== 可转换的 FLAC 文件 ===")
for i, name in ipairs(files) do
    print(string.format("%d. %s", i, urlDecode(name)))
end

io.write("请输入要同时启动的转换任务数量（默认 10，上限建议 12）: ")
local input = read()
local taskCount = tonumber(input) or 10
if taskCount < 1 then taskCount = 1 end

local actualCount = math.min(taskCount, #files)
print("将启动 " .. actualCount .. " 个 FLAC→MP3 任务（并发限制为 8）...")

local tasks = {}
local errors = {}

for i = 1, actualCount do
    local inputEncoded = files[i]
    local outputEncoded = inputEncoded:gsub("%.[^.]+$", ".mp3") -- 输出文件名：把 .flac 替换成 .mp3（保留 URL 编码格式）
    local args = string.format(
        '-y -i "%s" -c:a libmp3lame -q:a 2 "%s"',
        inputEncoded, outputEncoded
    )

    local ok, idOrErr = pcall(ffmpeg.execAsync, args)
    if ok then
        table.insert(tasks, { id = idOrErr, name = inputEncoded, done = false })
        print("任务 " .. i .. " 启动成功: " .. urlDecode(inputEncoded) .. " -> " .. urlDecode(outputEncoded))
    else
        local decodedErr = urlDecode(tostring(idOrErr))
        table.insert(errors, { name = inputEncoded, error = decodedErr })
        print("任务 " .. i .. " 启动失败: " .. urlDecode(inputEncoded))
        print("    错误: " .. decodedErr)
        print("    传给 Java 的输入编码: " .. inputEncoded)
    end
end

if #errors > 0 then -- 打印被拒绝的任务
    print("\n=== 被拒绝的任务 ===")
    for _, e in ipairs(errors) do
        print(urlDecode(e.name) .. " : " .. tostring(e.error))
    end
end

print("\n=== 轮询任务状态 ===") -- 轮询所有已启动的任务
local active = #tasks
local startTime = os.clock()   -- 用于显示已运行时长
while active > 0 do
    os.sleep(1)
    active = 0
    local running, success, failed = 0, 0, 0
    for i, task in ipairs(tasks) do
        local ok, status = pcall(ffmpeg.getStatus, task.id)
        if ok then
            if status == "running" then
                active = active + 1
                running = running + 1
            elseif status == "success" then
                success = success + 1
                if not task.done then
                    task.done = true
                    print("任务 " .. i .. " (" .. urlDecode(task.name) .. ") 完成: " .. status)
                end
            elseif status:match("^failed") then
                failed = failed + 1
                if not task.done then
                    task.done = true
                    print("任务 " .. i .. " (" .. urlDecode(task.name) .. ") 失败: " .. status)
                    local ok2, output = pcall(ffmpeg.getOutput, task.id) -- 显示 FFmpeg 输出，便于排查
                    if ok2 then
                        for _, line in ipairs(output) do
                            print("    " .. line)
                        end
                    end
                end
            end
        else
            if not task.done then
                task.done = true
                print("任务 " .. i .. " (" .. urlDecode(task.name) .. ") 已过期: " .. urlDecode(tostring(status)))
            end
        end
    end
    if active > 0 then
        local elapsed = math.floor(os.clock() - startTime)
        print(string.format("运行中: %d, 成功: %d, 失败: %d（已等待 %d 秒）",
            running, success, failed, elapsed))
    end
end

print("\n所有任务处理完毕。")
print("可以在 ffmpeg 文件夹中查看生成的 .mp3 文件。")
```

---

## ⚠️ 连续空格问题（重要）

### 问题现象

如果你的文件名里有**连续空格**，例如：

```
音乐           .flac
```

（`音乐` 和 `.flac` 之间出现多个**连续空格**）

**聊天命令** `/ffmpeg dfpwm` 和 `/ffmpeg exec` **无法正确处理**这类文件名，即使加双引号也会报“输入文件不存在”。

### 根本原因

Minecraft 的命令解析器在 Brigadier 解析之前，会把连续空格**合并成单个空格**。这是上游行为，模组无法绕过。

也就是说：
- 你在聊天框输入 `"音乐           .flac"`（连续空格）
- 服务器实际收到的是 `"音乐 .flac"`（1 个空格）
- 模组按这个字符串去查找文件，自然找不到

### CC: Tweaked Lua API **不受影响**

**Lua API 完全可以处理连续空格的文件名**，因为 Lua 字符串直接传给 Java，不经过 Minecraft 的聊天系统。

```lua
-- ✅ Lua API 可以正常处理连续空格
local files = ffmpeg.listFiles()
local input = files[filesname]   -- 例如 %e9%9f%b3%e4%b9%90...%20%20%20.flac(3 个空格)
ffmpeg.convertAsync(input, output)   -- 正常工作
```

**所以如果你的操作是通过 CC 电脑执行的，完全不用担心这个问题。**

## ⚙️ 配置文件

首次启动后，在 `config/ffmpegmod-common.toml` 生成配置文件：

```toml
# FFmpeg 可执行文件路径。可以是 'ffmpeg'（如果在系统 PATH 中），
# 或完整路径，例如 'C:/ffmpeg/bin/ffmpeg.exe' 或 '/usr/bin/ffmpeg'
ffmpegPath = "ffmpeg"

# FFmpeg 执行超时时间（秒）。超时后会强制终止进程。
timeoutSeconds = 300

# 最多回显到聊天栏的输出行数，避免刷屏。
maxOutputLines = 50
```

修改后重启游戏生效。

---

## 🔒 安全说明

这个模组会让 **OP 玩家** 或 **CC 电脑** 调用外部 FFmpeg，理论上可以读写 `ffmpeg/` 文件夹内的任何文件。请遵守以下原则：

- **只在单人游戏或你完全信任的服务器上使用**。
- 如果服务器对玩家开放 CC 电脑，请只在受信任的玩家群体中启用。
- 所有文件操作限制在游戏运行目录的 `ffmpeg/` 文件夹内。
- 路径穿越（`../`）、绝对路径（`/etc/passwd`、`C:\Windows`）、UNC 路径（`\\server\share`）都会被拦截。

---

## 📋 常见问题

### Q：报错“FFmpeg 不可用”怎么办？

1. 打开命令行执行 `ffmpeg -version`，确认 FFmpeg 已安装。
2. 如果已安装但游戏里报错，打开 `config/ffmpegmod-common.toml`，把 `ffmpegPath` 改成 FFmpeg 的**完整路径**，例如 `C:/ffmpeg/bin/ffmpeg.exe`。

### Q：为什么中文文件名在 CC 电脑里显示成 `%E5%8D%B8...`？

这是 **URL 编码**，用于保证中文字符在 Java 和 Lua 之间无损传递。用 Lua 端的 `urlDecode` 函数解码显示即可（见上文）。

### Q：为什么聊天命令的文件名要加双引号？

双引号可以让命令正确解析单个空格。**所有文件名都用双引号包裹**，这是最稳妥的做法。

### Q：连续空格的文件名怎么处理？

参见上文 [连续空格问题](#-连续空格问题重要) 章节。推荐用 CC: Tweaked Lua API。

### Q：任务完成后为什么 `getStatus` 报“任务不存在或已过期”？

任务完成后保留 **5 分钟**，超时会自动清理。查询时要及时，或者用 `pcall` 包裹 `getStatus`。

### Q：为什么最多只能同时运行 8 个任务？

这是为了防止同时启动太多 FFmpeg 进程导致服务器资源耗尽。可以在 `FFmpegAPI.java` 里调整 `MAX_CONCURRENT_TASKS` 常量，但要注意 CPU 和内存限制。

### Q：FFmpeg 报错 `Unknown encoder 'libmp3lame'` 怎么办？

你的 FFmpeg 编译时没包含 LAME 支持。换成：

```
-c:a mp3      (内置 mp3 编码器)
-c:a aac      (AAC 编码)
```

### Q：`/ffmpeg exec` 里的路径穿越能被拦截吗？

能。参数按路径分量（`/` 和 `\`）拆分，任一分量为 `..` 就拒绝。同时，绝对路径（`/`、`C:\`、`\\`）也会被拒绝。

---

## 🏗️ 从源码构建

```bash
# 克隆项目
git clone https://github.com/Lain-01-G/FFmpegmod.git
cd ffmpegmod

# 构建
./gradlew clean build

# 输出在 build/libs/ffmpegmod-1.0.0.jar
```

### 开发环境

- JDK 21
- IntelliJ IDEA 或 Eclipse
- Gradle 8.x（通过 `./gradlew` 自动下载）

---

## 📄 许可证

本项目使用 **MIT License**，详见 [LICENSE](LICENSE) 文件。

---

## 🙏 致谢

- [NeoForge](https://neoforged.net/) - Minecraft 模组加载器
- [CC: Tweaked](https://tweaked.cc/) - 提供 Lua API 支持
- [CC-UTF8-Compat](https://github.com/lisichaoyun/CC-UTF8-Compat/) - 提供 UTF8 渲染
- [FFmpeg](https://ffmpeg.org/) - 音视频转换工具
- [DeepSeek](https://chat.deepseek.com/) - AI 编程
---
