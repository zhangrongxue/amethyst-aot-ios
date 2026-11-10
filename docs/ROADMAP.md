# 路线图：从「能装能玩（JRE）」到「整包 AOT（免 JIT）」

当前状态（2026-11-10）：
- 交付线：IPA 走官方 JRE 路线（需要 JIT），云端构建已跑通并落到 `dist/`；
- 研究线：MobiVM 编译器/JNI 可行性已实测通过，详见 **`docs/AOT-FEASIBILITY.md`**。

本文只保留推进「游戏本体 AOT」的工程阶段与验收标准。

## P0：可行性（已完成）

| 验证项 | 结论 |
|---|---|
| MC 类能否被 RoboVM 编译 | ✅ 1.12.2 全闭包 8,633 类编译 + 链接成功 |
| JNI 静态库能否被调用 | ✅ `JNI-RESULT 42` |
| LWJGL iOS 原生库形态 | ✅ 标准 JNI 符号（1824 + 2228 个）+ `JNI_OnLoad`，源码可重建 |
| 目标版本 | ✅ MC 1.8.9（streams/function/nio.file 零依赖）… 1.12.2（需补 API） |

## P1：MC 1.8.9 headless 启动（1-2 周）

验收：AOT 二进制在 macOS 上跑完参数解析/资源索引，直到第一次 GL 调用前；产出按启动路径排序的 phantom 类清单。

- 用 Amethyst 的 LWJGL3 + lwjglx 类路径编译 1.8.9 闭包（`spike-mc-aot.yml` 已支持 `mc_version=1.8.9`）；
- `tools/compat-scan` 已能列出 blocker/phantom；
- 需要 patch 的类（若有）用 ASM 做小范围字节码改写（去掉 AWT/stream 依赖）。

## P2：LWJGL/GL 静态化与上下文注入（2-4 周）

验收：AOT 程序在 macOS 桌面创建 GL 上下文并画出第一帧；随后交叉编译到 iOS。

- 从 `PojavLauncherTeam/lwjgl3` 构建 iOS arm64 静态库（`liblwjgl.a`、`liblwjgl_opengl.a`、`liblwjgl_glfw.a`、`libopenal.a`），`robovm.xml <libs>` 链接；
- 图形后端复用 Amethyst 的 MetalANGLE / gl4es / MobileGlues：RoboVM 侧通过 native 调用创建/绑定 EGL 上下文；
- GLFW 窗口/回调映射到 `UIView`；先 macOS 桌面验证 JNI+GL 链路，再切 iOS SDK。

## P3：嵌入 Amethyst（2-3 周）

验收：TrollStore/AltStore 安装后，不挂调试器、不授权 JIT 也能进游戏。

- RoboVM `framework` target 打出 dylib，放入 `Payload/AngelAuraAmethyst.app/Frameworks/`；
- `Natives/main.m` / `JavaLauncher.m` 用 RoboVM 运行时初始化替代 `JLI_Launch`（线程 attach、异常端口、JIT 相关代码全部绕开）；
- 保留 Obj-C 启动器 UI，游戏入口切到 AOT 的 `net.minecraft.client.main.Main`。

## P4：打磨（持续）

1.8.9 / 1.12.2 双版本回归、无模组、启动时间、内存、崩溃日志；体积（单镜像替代 4 套 JRE，通常更小）。

## 已知不可行 / 不做

- **MC 1.13+**：Java 17 字节码，RoboVM 不支持（除非 RoboVM 自身升级）；
- **模组**：运行时从 jar 加载类与 AOT 静态闭包冲突，Forge/Fabric 不支持；
- **1.12.2 的 streams/function**：需要补 OpenJDK 8 类或改写少量类（1.8.9 无此问题，推荐先做 1.8.9）。
