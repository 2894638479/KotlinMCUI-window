# Changelog

发布的正文从这里取：`publishMods` 的 `changelog` 读整个文件，所以**每次发版前在这里加一节**。

## v1.0.0-alpha.1

首个可用产物：

- 一个 jar 同时带 Fabric / Forge / NeoForge 三份描述符与三套入口所需的东西，丢进 `mods/` 即可；
- 入口不再由本仓自己声明：描述符只保留身份，启动由游戏内后端的 `DslRuntime.launch()` 触发（独立运行时由 `main` 触发）；
- 独立运行：`java -jar KotlinMCUI-window-<版本>-standalone.jar`。
