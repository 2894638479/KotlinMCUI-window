# KotlinMCUI-window

自带窗口的 KotlinMCUI 后端：自己开一个 Swing 窗口，用 Java2D 把界面画出来，鼠标与键盘事件也由它自己收。modid 是 `kotlinmcuiwindow`。

它**不引用任何 Minecraft 类**，所以一个 jar 同时覆盖三个加载器、也不绑定游戏版本：jar 内同时带 fabric、forge、neoforge 三份元数据，各指向自己的入口类。它也能在完全不装游戏的情况下单独运行。

它与无界面后端（modid `kotlinmcuibackend`）配对使用：无界面那份负责 `install`（元数据与入口发现），这一份负责发布真正会渲染的后端。无界面后端发现 `kotlinmcuiwindow` 已安装时会保持沉默，把发布权让出来。两者之间没有编译依赖，只有元数据里声明的配对。

## 两个 jar，别拿错

| 产物 | 给谁 | 里面有什么 |
|---|---|---|
| `KotlinMCUI-window-<版本>.jar` | **加载器**（放进 `mods/`） | 三份加载器元数据各指向自己的入口；核心库是旁边的另一个 mod |
| `KotlinMCUI-window-<版本>-standalone.jar` | **不带游戏直接跑** | 自己的代码 + 核心库 + kotlin-reflect + 协程等；**没有**加载器元数据 |

`standalone` 那份**绝不能放进 `mods/`**：它内含一份核心库，游戏里会和真正的核心库撞车——这也是它刻意不带加载器元数据的原因。

## 构建与验证

```powershell
.\gradlew.bat clean build
```

构建与测试一起跑：7 个测试类 26 条用例。

### 验收截图

不开游戏，直接开窗口渲染演示页并截图，这是这个后端最快的验收方式：

```powershell
.\gradlew.bat run "-Pcapture=true" "-PcaptureName=lead-window.png"
```

窗口会打开、渲染根页面（框架的入口列表），然后把图写进 `screenshots/` 并退出（不传 `-Pcapture` 就只是开窗、自己关）。落点是仓库根下的 `screenshots/`：`run` 任务的 `workingDir` 是项目目录，截图目录挂在 game directory 下，两者在这里是同一个位置。

要直接看某一页并把图留下来，两个开关一起给：

```powershell
.\gradlew.bat run "-Ppreview=<页面引用>" "-Pcapture=true"
```

预览会把那一页压在根页面之上；只给 `-Ppreview` 会一直开着窗口，只给 `-Pcapture` 截到的是根页面。

截图**渲染的是窗口自己的内容，不是桌面区域抓屏**，所以窗口被最小化（收起）期间照样能截到，也不受操作系统前台锁的影响。

### 两个命令行坑

1. **参数必须整体加引号。** `-PcaptureName=lead-window.png` 不加引号会被 PowerShell 拆成 `-PcaptureName=lead-window` 和独立的 `.png`，于是报 `Task '.png' not found in root project 'kotlinmcui-window'`：构建很快失败，而且**窗口根本不会开**。写成 `"-PcaptureName=lead-window.png"`。
2. **给了 `-Ppreview` 时 `-PcaptureName` 不生效。** 预览会话由核心库的预览机制自己截图，文件名按页面引用推导成 `kotlinmcui-<页面名>.png`，因此会覆盖 `screenshots/` 下的同名文件。`-PcaptureName` 只在"只给 `-Pcapture`、不给 `-Ppreview`"这条路径上生效，也就是上面那条验收命令。

## 单独运行

```powershell
java -jar build\libs\KotlinMCUI-window-1.0.0-alpha.1-standalone.jar
```

它认的是普通系统属性，与 `-P` 开关一一对应：

```powershell
java -Dkotlinmcui.preview=<页面引用> -Dkotlinmcui.preview.capture=true `
     -Dkotlinmcuiwindow.capture=<文件> -Dkotlinmcuiwindow.capture.delay=<毫秒> `
     -jar build\libs\KotlinMCUI-window-1.0.0-alpha.1-standalone.jar
```

`-Dkotlinmcuiwindow.capture.delay` 是截图前的等待时间（默认 1500 毫秒），用来等页面画完第一帧。

## 依赖

- 核心库 `kotlinmcui` 与本仓库同时发布，缺一不可（页面写给它，入口发现靠无界面后端）。
- Kotlin 运行期由平台侧提供，这个 jar 里没有 kotlin-stdlib：Fabric 那份要求 `fabric-language-kotlin`，NeoForge 那份声明 `modLoader = "kotlinforforge"`，Forge 那份用 `javafml`。

## 开发文档

设计取舍见 [docs/dev/design.md](docs/dev/design.md)。正式文档在核心仓库（这个仓库不放正式文档）。
