# 输出坐标与尺度（窗口后端）

核心契约见核心仓库的 `docs/dev/render-scale.md`：`root.rect`、`PointerEvent.position` 与没有额外变换的绘制坐标是同一套输出目标像素；`root.scale` 由后端在每帧显式设置，没有隐式 1.0。这里只记录 Swing 后端如何满足它。

## 窗口边界的唯一一次换算

Swing 的 `JPanel` 尺寸是逻辑像素，`Graphics2D` 的默认变换才是逻辑范围到实际目标的映射。`WindowFrame.paintFrame(graphics, width, height)` 收到的是这两样：

1. 按 X、Y 分别取 `actual = ceil(logical * scale)`，其中 `scale` 来自 graphics 变换的两条轴（`hypot` 处理可能的切变）。这就是 framebuffer / canvas backing 的真实像素尺寸，不是标称 DPR，也不是面板宽高。
2. 每帧在渲染前写 `root.rect = 0..actual` 与 `root.scale = guiScaleFor(actualWidth, actualHeight)`；缩放策略仍是 `min(宽/320, 高/240)`、下限 1.0，只是输入从窗口逻辑尺寸换成了输出像素。
3. 通过 `graphics.create()` 得到一个派生 `Graphics2D`，把平台缩放从变换里去掉（只保留面板原点平移），并把原裁剪换算到同一输出空间。核心随后拿到的用户空间就是输出像素；若保留平台缩放再按输出像素绘制，DPI 会被应用两次。
4. 输入、裁剪、截图与绘制共享同一套映射：鼠标事件是逻辑坐标，`WindowInput` 按 graphics 变换的实际逐轴 `scale` 换算；`WindowFrame.outputSize` 用同一个未取整的 `scale` 向上取整得到截图位图尺寸。

这里的 `scale` 是「实际目标像素 / 逻辑像素」的小数比例，不是把 `actual` 位图宽再除以逻辑宽得到的四舍五入比值。例如宽 101、比例 1.25 时，位图是 127 像素，但逻辑 80 必须映射到输出 100，而不是 `127 / 101 × 80 = 100.594`；实际绘制的 `100..100.25` 细条正在指针下，不能被四舍五入的比值错过。截图同样保留这个小数比例，再按新的逻辑尺寸向上取整，避免反复从已取整的位图反推比例而累积误差。每个 `WindowFrame` 保存自己的比例与根，两个窗口不会共享尺度或光标映射。

## root.scale 的生命周期

根可以在首次绘制前创建、挂载页面，但不能构建。`paintFrame` 在 `root.component.render()` 之前同时写 `root.rect` 与 `root.scale`，包括改变窗口大小后的第一帧。`root.scale` 的 getter 在未初始化时抛出，setter 拒绝非有限、非正以及帧内写入——后端不提供任何回退的 1.0，也不会在帧外悄悄改写它。

尺寸为零或窗口最小化时按空输出处理：`root.rect` 为 0，`root.scale` 仍是 1.0（有限且合法），绘制落在零面积视口外。这里没有把 `scale = 0` 当成暂停信号，也没有忽略未初始化的根。

## 输入法与截图

Swing 没有输入法候选窗定位 API，`WindowInput.setPreeditArea` 仍是空操作；它收到的矩形已经是输出像素，如果将来接入一个按逻辑坐标定位的 API，需要按同一逐轴比值换回去。

截图不抓桌面，而是把面板组件树渲染进内存位图。位图按 `WindowFrame.outputSize` 的实际输出像素建立，并在打印前设置最后一次绘制测得的小数逻辑→输出比例，让面板走和屏幕帧相同的 `paintFrame` 路径；否则 HiDPI 下只会填满位图的左上角。零尺寸的目标没有可截图的像素，返回 `null`。

## 验收范围

离线测试不需要可见窗口，使用离屏 `BufferedImage` 作为真实目标：

- `WindowOutputTest`：分数/非等比目标（1.5 x 1.6）下 `root.rect`、`root.scale`、绘制与 `withScissor` 的像素对齐、输入分数坐标、resize、零尺寸目标、两个独立窗口根，以及截图位图尺寸；另有奇数逻辑宽 101、1.25 x 1.5、非零原点的真实绘制回归，验证指针、平台裁剪与截图都使用未取整的实际比例。
- `WindowIsolationTest`：两个窗口各自的输出尺寸与根尺度互不影响。
- `WindowCoexistenceTest`：另一个后端占用进程槽时，本窗口仍通过 `paintFrame` 渲染自己的根。
- `WindowScaleTest`：连续 scale 策略与逐轴输出尺寸换算。

身份 graphics 下的既有 headless 用例仍然有意义：目标与逻辑范围相等、比例为 1，`paintFrame` 写出的 `root.rect` 就是面板尺寸。
