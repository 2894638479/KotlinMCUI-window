# 设计取舍

## 渲染基础：Swing 窗口 + Java2D 软件渲染

纯 JVM：零原生库、零 Minecraft 类、零版本相关。窗口与事件用 AWT/Swing，绘制用 Java2D，字形由 `java.awt.Font` 提供（不必自带字形表）。

这是"一个 jar 覆盖所有游戏版本与三个加载器"能成立的前提：jar 里没有任何 Minecraft 引用，也就没有需要跟着游戏版本走的东西。代价是软件渲染——每帧走 CPU，适合编辑器式界面，不适合高帧率游戏内 HUD。

## 一个 jar，三份元数据

jar 内同时带 fabric.mod.json、META-INF/mods.toml、META-INF/neoforge.mods.toml，各自只引用自己的入口类，且都不写游戏版本上限。需要用别的平台时加一份元数据与一个入口即可。

## 与无界面后端的分工

无界面后端（`kotlinmcuibackend`）负责 `install`（元数据与入口发现）；这一份用 `DslRuntime.start` 发布 `DslPlatform`（元数据、进程级工具、进程级 dispatcher），并**自己构造根** `DslRoot(input, renderer, screenFactory, utils)`——绘制、输入、页面与每帧循环都在本仓，核心不再持有 provider。无界面后端发现 `kotlinmcuiwindow` 已安装时保持沉默。

启动顺序只有三条约束：

- `install` 必须在最前（重复调用会抛）。
- `start` 必须早于任何对平台的读（`dslPlatform`、`platform.utils` 等）；它返回 `false` 只说明计划平台槽的是别人，**不早退**。
- **根由本仓自己构造**（`DslRoot(...)`），构造之后再 `DslPreview.start(root)` 并开窗：运行时不再顺带起预览，漏了不会编译失败，只会"预览不启动"。

**没能成为进程平台 ≠ 本后端不可用。** 平台槽归进程（第一个发布者），渲染归根：`start` 返回 false 时仍然构造自己的根并开窗；页面用**自己的 `WindowPlatform` 对象**构建，而 `dslPlatform` 仍是别的后端的。这样它能在 MC/web 已发布平台的进程里继续出窗口。
输入转发同理：**每个事件都要送到本窗口渲染的那个根**，不能去读任何全局根——共存进程里那会把光标与键盘派给别人的树，而且完全静默（一个对不上焦点的根本来就看起来像处理过了）。根由本仓自己构造，所以输入持有它、窗口持有它、页面持有它，**处处接参数**。

配对是**单向**的：这一份的元数据声明依赖无界面后端，反向则不是——无界面后端只在运行期用加载器的 mod 列表判断，不在元数据里引用这一份。这样无界面后端单独装也是完整可用的。

发布时机不能在构造期：`start` 内部要求元数据已 `install`，所以 Fabric 用 `ClientModInitializer`（loader 保证它在 `ModInitializer` 之后），Forge/NeoForge 用各自的客户端 setup 事件。

## AWT 的 headless 必须在最早的入口清掉

游戏进程启动时 `java.awt.headless` 是 `true`，而 AWT 会在第一次被使用时把这个值**缓存**下来：之后再改属性也无效，`JFrame` 只会在事件线程上抛 `HeadlessException`，被 Swing 吞掉后表现为"窗口不出现且什么都不报"。

所以三个入口都在自己最早的入口先把它清成 `false` 再碰任何 AWT 类。Fabric 侧的入口是一段独立的 main 入口（`ModInitializer`），清完属性还会当场取一次默认工具包把取值固定下来；Forge/NeoForge 没有比 mod 构造函数更早的入口，只能在那里清。

## 截图渲染内容，不是桌面抓屏

`capture` 把窗口自己的组件树渲染进一张内存图像，而不是抓取窗口占据的桌面区域。桌面抓屏要求窗口真的在最上面，操作系统的前台锁可能拒绝 `toFront()` 之后的状态，更重要的是会拍下那个矩形里的别的应用——一张悄悄属于别人的截图比没有截图更糟。

渲染内容也因此与前台状态无关：窗口被最小化（收起）期间同样能截到，实测结果与正常状态逐字节一致。这不是巧合，而是"渲染内容"这条路线本来就该有的性质——不要把"桌面抓屏需要窗口在前台"推广成"渲染内容也需要"。

## GUI scale 是连续值

窗口后端的 GUI scale 由窗口尺寸连续算出（`min(宽/320, 高/240)`，下限 1.0），不取整。理由与两个被否掉的备选见核心仓库的 ADR 0005：Java2D 的字形与描边都抗锯齿，取整就只剩代价。

页面自身的尺寸仍等于画布像素，`.scaled` 只作用于内容，所以改窗口大小不会溢出；布局随窗口尺寸连续变化。

## 物品图标

按 `assets/<命名空间>/textures/item/<名字>.png` 找，找不到再试 `textures/block/`。候选来源依次是版本客户端 jar（`versions/<版本>/<版本>.jar`）与 `mods/` 下的每个 jar，独立运行时退回 classpath；都没有就用一块平面替身加首字母，而不是画一个看起来不对的图标。

这一条是"不读 Minecraft 代码、只读 jar 里的 PNG"的边界：它读的只是资源文件，不涉及任何 Minecraft 类型。

## 刻意不做

- 不在游戏窗口里画任何东西——正是这一点让它与游戏版本无关。
- 不引用任何 Minecraft 类。因此 `renderButton` / `renderSlot` / `renderContainer` / `renderTooltip` 是这一份后端的平面近似，用自有调色板，而不是读原版贴图。
- 不定位输入法候选窗口：Swing 没有这个 API，候选窗由输入法自己放置。
- 不做无障碍朗读（没有到读屏软件的桥），不播放按钮音效（唯一能发的是系统蜂鸣，比静音更糟）。

## 每帧循环由后端自己写

核心不再替后端驱动帧。一帧就是三步，按这个顺序：

1. 把表面尺寸写给根与渲染器：`root.rect`（渲染 pass 会把它拷到每个已挂载的屏）与渲染器的 surface 尺寸（scale 由此派生）；
2. `context(renderer, renderParam, input.mouse) { root.component.render() }`——渲染器作为 context 实参传进去，不要让别的角色去"冒充"渲染器；
3. `renderer.flush()`。

顺序反了，屏会按上一帧的尺寸布局；第 2 步漏掉，窗口就是一片空白。预览也不再由运行时顺带启动：`DslPreview.start(root)` 要显式调。

## 构建：兄弟仓优先，且不静默回落

本仓链接 `../kotlinmcui` 的核心产物：**只要兄弟仓存在就只用它**，缺主 jar 或缺 `-sources.jar` 都直接构建失败并指出该跑哪个任务（`jar` / `sourcesJar`）。曾经的写法是"本地缺失就回落到 jitpack"，而缓存里的发布版可能已是几个月前的核心——构建绿了，编的却是另一份库。
