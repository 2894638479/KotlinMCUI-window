# Swing 指针输入

AWT MouseEvent 转换为统一 PointerEvent，窗口内固定 PointerId(0)、kind=MOUSE、canHover=true。所有鼠标按键共用一个身份和独占者，按钮集合由事件流维护；无原生压力时按住 0.5、空按钮 0。

Down、Move 与 Up 都使用该事件的实际位置，经最后测得的 Graphics2D 逐轴比例换算为输出像素。特别是 Up 不沿用最后 Move 的位置。渲染只传 renderer 与 renderParam，不传单一 mouse 上下文。

mouseExited 提交 Leave，保留活动拖拽；AWT 的拖拽派发负责窗口外移动/松开，失去焦点使用 Cancel 并清空本窗口按键缓存。隐藏/换页由核心取消旧物化节点，物理按键残余序列由核心排空。

WindowPointerTest 通过无可见窗口的实际窗口边界验证最终位置、压感回退、多按钮、Leave 与失焦取消。WindowOutputTest 保留 HiDPI/非等比/resize/像素回归。

当前 AWT 来源没有原生触点身份或笔压，不把系统合成的 mouse 事件标成 TOUCH/PEN。核心支持多点不代表此 Swing 来源已提供真实多点；新增原生 Windows/平板桥接需要另行实现和设备验收。可选 core demo jar 随 standalone 包含，预览可使用 TestMultiPointerPage。
