# 免 JIT 路线可行性报告（MobiVM 整包 AOT）

日期：2026-11-10 · 目标：让 Amethyst 的《我的世界》Java 版在**不依赖 JIT** 的情况下运行。

## 一句话结论

**编译器层和 JNI 层已经实测可行**，真正的缺口收敛到三件事：RoboVM 运行时缺的 Java 8 API、LWJGL/GL 原生层的静态化与上下文注入、以及 RoboVM 运行时嵌入 Amethyst 主程序。目标版本应选 **MC 1.8.9**（对 streams/function/nio.file 零依赖）。

## 实测证据

| # | 实验 | 结果 |
|---|---|---|
| 1 | `tools/compat-scan` 扫 MC 1.12.2 client.jar | 3,310 类全部 v52（Java 8）；MC 自身 **0 个 native 方法**；15 个类用 invokedynamic；27 个类引用 `java.awt`/`java.lang.management` |
| 2 | `spike-mc-aot`（RoboVM 2.3.23）编译 MC 1.12.2 全闭包 | **编译 8,633 个类成功，链接成功**，产出 macOS arm64 console 二进制；157 个 phantom 类（`java.util.stream`/`function`/`java.nio.file`/`java.awt`/`javax.imageio`） |
| 3 | `spike-jni`：Java native 方法 + C 静态库（`libspikejni.a`） | **运行时输出 `JNI-RESULT 42`** —— RoboVM 能解析并调用静态链接的 JNI 方法 |
| 4 | MC 1.8.9 vs 1.12.2 的 Java 8 API 依赖对比 | 1.8.9：`java/util/stream` **0**、`java/util/function` **0**、`java/nio/file` **0**；1.12.2 分别为 26/74/34 |
| 4b | `spike-mc-aot` 编译 MC **1.8.9**（Amethyst 的 LWJGL3+lwjglx 类路径） | **7,253 个类编译 + 链接成功**；phantom 降到 **102 个**（javax.sound.midi 16、java.awt 22、java.nio.file 8、javax.naming 13、javassist 6） |
| 5 | Amethyst 自带 `liblwjgl*.dylib` 的导出符号（Mach-O export trie） | `liblwjgl.dylib` 1,824 个导出、`liblwjgl_opengl.dylib` 2,228 个导出，均为标准 `Java_org_lwjgl_*` JNI 符号，且含 `JNI_OnLoad` |
| 6 | LWJGL iOS 源码可得性 | `PojavLauncherTeam/lwjgl3`、`AngelAuraMC/lwjgl3` 均可访问；Amethyst-iOS-MyRemastered 仓库内 vendored 了完整 LWJGL 源码树与 `ci_build_ios.bash` |

## 这意味着什么

1. **字节码不是问题**。MC 的类（含混淆类）RoboVM 全都能读、能编译、能链接。
2. **JNI 不是死路**。之前担心「AOT 镜像无法 dlopen JNI 库」，但 RoboVM 支持把 JNI 实现**静态链接进镜像**并按符号解析；Amethyst 的 LWJGL 原生库本来就是标准 JNI 符号，只要用 Pojav 的源码重编成 `arm64` 静态库（`.a`）即可。
3. **运行时 API 缺口可量化**。
   - MC 1.8.9 对 `java.util.stream` / `java.util.function` / `java.nio.file` **零引用** → 不会踩 RoboVM rt 的缺口；
   - 仍有 116 处 `java.awt` 引用，集中在 Realms / 桌面相关代码（打开链接、系统托盘、剪贴板等）。需要确认启动路径是否触达；若不触达，phantom 类不会在运行时被加载。
4. **目标版本锁定**：MC **1.8.9 ~ 1.12.2**（Java 8 字节码上限）。1.13+ 是 Java 17，RoboVM 不支持；带模组也不支持（运行时加载 jar 与 AOT 冲突）。

## 剩余工程（按依赖顺序）

### P1. MC 1.8.9 headless 启动（1-2 周）

- 用 Amethyst 的 LWJGL3 + lwjglx 类路径 AOT 编译 1.8.9 闭包；
- 在 macOS 上跑 AOT 二进制，目标：走完 `Main.main` 的参数解析、资源索引、直到第一次 GL 调用前；
- 产物：已确认的 phantom 类清单（按启动路径排序）+ 需要 patch 的点。

### P2. LWJGL/GL 静态化与上下文注入（2-4 周）

- 从 `PojavLauncherTeam/lwjgl3` 构建 iOS arm64 静态库：`liblwjgl.a`、`liblwjgl_opengl.a`、`liblwjgl_glfw.a`、`libopenal.a`，并让 `robovm.xml` 的 `<libs>` 链接它们；
- 图形后端复用 Amethyst 现有方案（MetalANGLE / gl4es / MobileGlues）：RoboVM 侧通过 native 调用创建/绑定 EGL 上下文，把 `GLFW` 的窗口/回调映射到 `UIView`；
- 先在 macOS 桌面（OpenGL）验证整条 JNI + GL 调用链，再切 iOS SDK 交叉编译。

### P3. 嵌入 Amethyst（2-3 周）

- 用 RoboVM `framework` target 把 AOT 镜像打成 dylib，放进 `Payload/AngelAuraAmethyst.app/Frameworks/`；
- 在 `Natives/main.m` / `JavaLauncher.m` 里初始化 RoboVM 运行时（替代 `JLI_Launch`），线程 attach、异常端口、JIT 相关代码全部绕开；
- 保留现有启动器 UI（Objective-C），游戏入口切到 AOT 的 `net.minecraft.client.main.Main`。

### P4. 打磨与兼容（持续）

- 1.8.9 / 1.12.2 双版本回归；无模组；TrollStore / AltStore 安装验证；内存与启动时间；崩溃日志。

## 风险清单

| 风险 | 说明 | 缓解 |
|---|---|---|
| `java.awt` 启动路径 | 若启动早期加载 AWT 类，会 `NoClassDefFoundError` | 先在 P1 用 headless 跑定位；必要时改写对应 MC 类（数量少） |
| GLFW/JNI 回调 | LWJGL 的 callback 依赖 `AttachCurrentThread` 与 Java 回调 | RoboVM 有 JNI 回调支持，需专门 spike |
| RoboVM 对新 iOS SDK | 工具链 2.3.23 面向 Xcode 14/16 时代 | 固定 Xcode/ios-cmake 版本；必要时打补丁 |
| 体积与启动 | AOT 镜像 + 4 套 JRE 的现状会变成单镜像 | 只保留一套运行时，体积反而下降 |
| 工期 | 上述为单人全职乐观估计，且存在未知 | 每阶段设 go/no-go：P1 不通则回到 JRE 路线 |

## 复现方式

```bash
# 兼容性扫描
java -jar tools/reflect-scan/build/reflect-scan.jar --jars work/mc --out work/scan
java -cp tools/compat-scan/build/classes:... dev.mcjava.compatscan.CompatScan work/mc/client.jar work/compat

# MC 闭包 AOT（GitHub Actions）
gh workflow run spike-mc-aot.yml -f mc_version=1.8.9

# JNI 静态库验证
gh workflow run spike-jni.yml
```

CI 里两个 workflow 的日志 artifact 保留完整证据（`spike-mc-aot-log`、`spike-jni-log`）。
