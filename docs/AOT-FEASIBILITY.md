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

## P1 运行时探针记录（MC 1.8.9，AOT 二进制在 macOS 上实际执行）

| 轮次 | 观察 | 处置 |
|---|---|---|
| A | 进入 `net.minecraft.client.main.Main.main`，log4j 用 `Class.forName` 找 provider 失败（`Log4jContextFactory` 未链接） | robovm.xml 增加 `forceLinkClasses: org.apache.logging.log4j.core.**` |
| B | `java.lang.NoClassDefFoundError: java.util.function.Function`，位置 `org.lwjgl.system.Configuration.<clinit>` → `GLFW.<clinit>` → `Sys.getTime` → MC 主路径 | 从 Temurin JDK8 `rt.jar` 抽取 `java/util/function`、`java/util/stream`、`Spliterator`、`Optional` 打成 `java8-extras.jar`，追加到 `-bcp` |
| C | log4j 能实例化，但 JMX 注册触发 `java.lang.management.ManagementFactory` 缺失 → 回退 SimpleLogger；随后 `java.nio.file.Paths` 缺失，位置 `org.lwjgl.system.Library.<clinit>` → `Library.loadSystem`（动态加载原生库） | 下一步：AOT 下原生库应静态链接，需把 `Library.loadSystem` patch 成 no-op（ASM 改写），或提供最小 `java.nio.file` 桩；同时去掉/绕开 log4j-core 的 JMX |

**里程碑含义**：到 C 为止，AOT 二进制已经完整跑过 MC 的参数解析、资源目录初始化、日志框架初始化，并进入 LWJGL 的静态初始化。失败点全部是「运行时缺类/动态加载」，不是编译器或字节码问题。下一步 patch 掉动态加载后，预期会到达第一个原生的 GLFW 调用（`UnsatisfiedLinkError`），那就是 P1 的终点、P2（静态链接 LWJGL）的起点。

| D | 用 ASM 把 `org.lwjgl.system.Library.loadSystem` 三个重载改成空实现后重跑：log4j 正常回退 SimpleLogger，`java.nio.file.Paths` 不再出现在 `Library.<clinit>`，失败点前移到 `Library.loadNative`（`GLFW.<clinit>` 里加载 glfw 原生库） | 说明动态库加载已按预期绕过；剩 `loadNative` 属于 P2 的「原生库句柄/函数表」问题 |
| E | 发现 Amethyst 运行时的 `org.lwjgl.glfw.GLFW` / `Sys` 是 **JavaApp 里的自定义 Java 实现**（`JavaApp/src/lwjgl/...`，编译时覆盖到 `JavaApp/build/lwjgl.jar`），并非原始 `libs/lwjgl/*.jar` | P1 下一轮应改用 `make -C JavaApp` 构建产物 + `launcher.jar` 作为 AOT 类路径，才与真机运行一致 |

**P1 结论**：AOT 二进制可运行 MC 主路径（参数/资源/日志 → LWJGL 静态初始化 → 原生库加载点）。动态库加载可被 ASM patch 绕过；真正的下一步是把 Pojav 的 LWJGL/GLFW/OpenAL 原生实现编成静态库并由 RoboVM 提供 JNI，这正是 P2。

| F | 换成 `JavaApp/build` 真实运行类路径后：`GLFW.<clinit>` 执行 `System.load(System.getenv("BUNDLE_PATH") + "/AngelAuraAmethyst")` —— Amethyst 把 JNI 原生代码**静态链进主程序**、再加载自身可执行文件来注册符号；随后因 launcher 未设置 `glfw.windowSize` / `UIScreen.maximumFramesPerSecond` 等属性而 NPE | 这正是 AOT 想要的结构：AOT 镜像本身就是「原生库」。探针需预置 launcher 属性；P2 只要让 RoboVM 镜像导出/解析这些 JNI 符号即可 |

**架构结论**：Amethyst 的 iOS 原生方案 = 原生代码静态链进 App 主二进制 + `System.load(自身)`，而不是加载外部 dylib。这对 AOT 非常有利：RoboVM 产出的镜像天然就是单一可执行文件，JNI 符号是否可解析只取决于 RoboVM 的 `Runtime.nativeLoad`/符号导出机制（P2 验证点）。

| G | 加上 Java 8 shim（`ThreadLocal.withInitial` → `Compat.threadLocalWithInitial`）后：ThreadLocal/回调/Pointer 初始化全部推进，最终在 `MemoryUtil.<clinit>` 撞上 `UnsatisfiedLinkError: org.lwjgl.system.jni.JNINativeInterface.nNewDirectByteBuffer(JJ)` | **这是第一个真正的 JNI 原生调用**，也是 P1 的终点。该符号正是 Pojav `liblwjgl.dylib` 的导出（`Java_org_lwjgl_system_jni_*` 50 个 + `JNI.invoke*` 1605 个） |

**P1 完成定义（已达成）**：MC 1.8.9 的 AOT 镜像能在 macOS 上跑完启动器层初始化与 MC 主路径，直到第一个 JNI 原生调用点，且失败符号可定位到 Amethyst 已有原生库的导出。P2 的工作因此变得具体：把该原生库以 macOS/iOS 静态或动态形式接进 AOT 镜像，让 `JNINativeInterface`/`JNI.call*` 解析成功。

| H | 同一 JNI 冒烟测试同时构建 `.a` 与 `.dylib` 两种形式：RoboVM 链接后两者都能解析并运行（都输出 `JNI-RESULT 42`；`System.loadLibrary` 在 dylib 模式下仍报找不到，说明符号解析走的是**构建期链接 + 运行时符号表**，不依赖 loadLibrary） | **P2 捷径成立**：iOS 目标可以直接链接 Amethyst 现有的 `liblwjgl.dylib` / `liblwjgl_opengl.dylib` / `libopenal.dylib` 等 iPhoneOS arm64 产物，不必从源码重建 LWJGL |

**P2 下一步（已明确）**：用 `-os ios -arch arm64 -target ios -skipsign` 把 MC 闭包链接到这些现有 dylib 上，产出 iOS 版 AOT app，验证链接与 JNI 符号；随后处理 GLFW 窗口/GL 上下文对接，再进入 P3 嵌入。

| I | 用 `-os ios -arch arm64 -target ios -skipsign` + `-libs liblwjgl.dylib:liblwjgl_opengl.dylib` 构建 iOS 版 AOT app：**链接成功**，产出 arm64 iPhoneOS 二进制（min iOS 8.0，Size 36 MB，内置全部 jar），clang++ 链接行确实带上了两个 Amethyst dylib，随后 dsymutil/strip/bitcode_strip/ad-hoc codesign 全部完成 | P2 的链接路径打通。遗留：dylib 的 install name 是构建期相对路径 `bin/libs/native/macos/arm64/org/lwjgl/liblwjgl.dylib`，且 RoboVM 没把 dylib 拷进 app 的 Frameworks。需要：拷贝 dylib 到 `ios-out/Frameworks/` + `install_name_tool -id/-change` 改成 `@rpath/...`，再进 P3 嵌入 |

**P2 收尾清单**：① Frameworks 拷贝 + install_name 修正；② 让 AOT app 真正接管 GLFW 窗口/GL 上下文（对接 Amethyst 的 UIKit/EGL 桥）；③ 在真机（TrollStore/侧载）上验证「不挂调试器、不授权 JIT 也能启动」。

**当前结论**：免 JIT 路线的技术不确定性已基本消除——编译器、JNI、iOS 链接三段都实测通过；剩下是工程集成（install name、上下文注入、嵌入方式）。

| J | 完成 Frameworks 拷贝 + `install_name_tool` 修正（主二进制与两个 dylib 的依赖全部改成 `@rpath/...`，dylib 的 `LC_ID` 也改成 `@rpath/...`），重新 ad-hoc 签名，并打包出 **`amethyst-aot-unsigned.ipa`**（15.7 MB，`Payload/AmethystAOT.app`，含 arm64 主二进制 + 两个 Frameworks dylib + 全部 jar） | **可侧载的 iOS AOT 脚手架产物已落地**（`dist/aot-spike/`）。校验：主二进制 `LC_LOAD_DYLIB @rpath/liblwjgl*.dylib`，rpath `@executable_path/Frameworks`，两个 dylib 的 id 均为 `@rpath/...`。仍不可玩：AOT 镜像缺 App 侧 UIKit/GLFW 原生层，P3 嵌入 Amethyst 后解决 |

**P3 入口**：把 AOT 镜像以 framework/dylib 形式嵌进真正的 Amethyst 主程序（保留其 Obj-C/C 原生层与 UIKit），让自定义 GLFW 的 `System.load(自身)` 能解析到 App 侧 JNI 符号，再由 Objective-C 侧初始化 RoboVM 运行时并启动 `McMain`。

| K | 用 `-target framework -plist Info-framework.plist` 构建嵌入原型：**成功**产出 `McAotFramework.framework`（arm64，iOS 平台 platform=2，min iOS 14.0，dylib 36 MB + bundle 资源）。导出符号正是嵌入所需：`JNI_CreateJavaVM` / `JNI_GetCreatedJavaVMs` / `JNI_GetDefaultJavaVMInitArgs` / `rvmInitializeFrameworkWithJVM` / `rvmInstantiateFramework`。framework 的构造器会自动创建 RoboVM VM | **P3 机制确认**：宿主 App 链接/加载该 framework 即可获得 JavaVM，再用 JNI `FindClass("dev/mcjava/spike/McMain")` 调 `main`。遗留：framework 的两个 `LC_LOAD_DYLIB` 仍是构建期路径，需同前 `install_name_tool` 改成 `@rpath/...` |

**P3 集成步骤（已可执行）**：① framework 内 dylib 依赖改 `@rpath`；② 把 `McAotFramework.framework` 放进 Amethyst `Payload/*.app/Frameworks/` 并在 CMake 里链接（或运行时 dlopen）；③ `main.m`/`JavaLauncher.m` 用 `JNI_GetCreatedJavaVMs` 拿 JavaVM，调 `McMain.main(gameDir/assets/…)`；④ 宿主 App 已导出 UIKit/GLFW 的 JNI 符号（自定义 GLFW 的 `nglfw*`），RoboVM 运行时可直接解析；⑤ 真机侧载验证。

| L | P3 第一次真实集成：新增 `Natives/AOTBridge.m`（constructor 延迟 6s 后用 `JNI_GetCreatedJavaVMs` 拿 JavaVM、`FindClass("dev/mcjava/spike/McMain")` 调 `main`）、CMake 条件链接 `McAotFramework.framework`（`AOT_FRAMEWORK_DIR`）、Makefile `AOT=1` 时把 framework 拷进 app 的 Frameworks；新增 `build-ios-aot.yml` 一次构建「AOT framework + 真实 Amethyst」。**构建成功**：完整 IPA 208 MB / 瘦身 50 MB，`Payload/AngelAuraAmethyst.app/Frameworks/McAotFramework.framework` 已包含，`verify_ipa.py` 两个 IPA 均 PASS | **P3 的工程接线打通**。下一步：真机侧载跑一次，看 AOTBridge 的 `[AOT]` 日志与首个失败点；预计会卡在 GLFW 窗口/GL 上下文（宿主 UIKit 桥尚未对接） |

**待验证（真机）**：安装该 IPA 后看 Console 日志 `[AOT] will enter AOT image...` / `[AOT] invoking McMain.main` / 第一个异常。这条日志链会告诉我们 RoboVM VM 是否在 Amethyst 进程里成功启动、JNI 符号是否解析。

| M | P3 产物校验：完整 IPA 的 `Frameworks/` 里已有 `McAotFramework.framework`（36 MB），主二进制 `LC_LOAD_DYLIB @rpath/McAotFramework`、rpath `@executable_path/Frameworks`。启动顺序因此是：dyld 加载框架 → frameworksupport 构造器 `JNI_CreateJavaVM` → AOTBridge 6 秒后 `JNI_GetCreatedJavaVMs` + `FindClass("dev/mcjava/spike/McMain")` + `main`。产物已存 `dist/aot-spike/prototype/`（含 SHA-256、安装与日志抓取说明） | 只剩**真机运行时验证**：装包后抓 `[AOT]` 日志链，确认 VM 创建与 JNI 解析，再定位首个 GL/窗口失败点。GL 桥接是最后一块 |

**当前状态**：从「可行性 spike」到「可安装的集成原型」已全部完成；剩余工作是真机日志驱动的一轮修复（GLFW/GL 上下文对接宿主 UIKit）。

| N | **单 VM 路线探针**：用 `spikes/mc-aot/robovm-launcher.xml` + `-cp` 真实运行类路径 AOT 编译 `net.kdt.pojavlaunch.PojavLauncher`（Amethyst 启动器 Java 层） | **编译+链接成功**，phantom 仅 6 个：`com.apple.eawt.Application`、`java.beans.Beans`、`sun.font.FontUtilities`、`org.robovm.objc.ObjCObject`、两个 errorprone 注解——都是非关键路径。说明「启动器 Java 层也 AOT 进同一个 RoboVM VM」是可行的，这是解决双 VM 架构的正路 |
| O | GL 函数解析链路修复后重建 Amethyst+AOT（run `42791738550`）成功：framework 内加入 `libcompat-ios.a`（arm64 iPhoneOS，Mach-O symtab 符号解析），`build-ios-aot.yml` 产物仍 PASS | 待下载产物做符号级校验；真机日志仍是最终判据 |

**架构决策倾向**：走 **单 VM**——把 Amethyst 的启动器 Java 层与 MC 一起 AOT，宿主 Obj-C UI 通过 JNI 调 RoboVM VM；这样 GLFW 窗口/输入/回调都在同一个 VM 里，才能「可以玩」。双 VM 方案只适合做演示，不适合可玩版本。

| P | **单 VM 落地（构建级）**：新增 `spikes/mc-aot/src/dev/mcjava/spike/AotMain.java`（读 `GAME_DIR`/版本 id → `Tools.getVersionInfo` → `Tools.getMinecraftArgs` → 直接调 `net.minecraft.client.main.Main.main`，绕开 `Tools.launchMinecraft` 的 classloader）、`Natives/AOTBridge.m` 改为 `AOTBridgeLaunchGame(user, version)` 并把 `runtimeJavaVMPtr` 指向 RoboVM VM、`JavaLauncher.m` 在 `AOT_ALWAYS` 下跳过 `JLI_Launch`；`MinecraftAccount.<clinit>` 用 AotPatch 清空（去 `System.loadLibrary`）。`build-ios-aot.yml` 产物：完整 208 MB / 瘦身 50 MB，verify 均 PASS | **单一 VM 的 AOT 启动链路已闭环并可构建**。剩余：宿主 App 的 JNI 函数（`CallbackBridge_*`、`UIKit_showError` 等，靠 `JNI_OnLoad`+`RegisterNatives` 注册）在 RoboVM 下不会自动注册，需要做「注册或 Compat 转发」；以及真机 GL 验证 |

**下一步（按优先级）**：①把 App 侧 JNI 注册搬到 RoboVM（或 Java 层经 `Compat.addressOf` 转发到 `CallbackBridge_*`）→ ②真机装包看 `[AOT] invoking AotMain.main` 日志 → ③GL 上下文跑起来后逐项修 MC 崩溃点。

| Q | **宿主 JNI 注册桥**：`Natives/AOTBridge.m` 增加 Mach-O symtab 符号解析（`host_symbol`）与 `RegisterNatives` 表——注册 `CallbackBridge.nativeClipboard/nativeSetGrabbing`、`UIKit.showError`、GLFW 的 `nglfwSet*Callback`/`nglfwSetShowingWindow`/`nglfwGetCursorPos(A)`/`glfwSetCursorPos`；设置 `runtimeJavaVMPtr/runtimeJNIEnvPtr` 后调用宿主 `JNI_OnLoadGLFW` 填充 GLFW 全局（`vmGlfwClass`、输入缓冲区）。构建成功：完整 IPA 210.3 MB / 瘦身 52.2 MB，verify PASS | 内置 native 解析不再依赖 RoboVM 的 dlsym 路径。下一步：真机日志验证（装包 → 按 Play → 看 `[AOT] registered N/M host natives` 与后续 MC 启动日志），再逐项修 GL/崩溃 |

**到这里 AOT 侧的主体工程已完成**：AOT 编译真实 MC、JNI/GL 原生函数解析、framework 嵌入、单 VM 启动、宿主 JNI 注册。剩下的都是**运行时联调**，必须依赖真机日志驱动。