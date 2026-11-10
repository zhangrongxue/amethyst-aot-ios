# 路线图：从「能装能玩（JRE）」到「整包 AOT」

当前状态：IPA 走官方 JRE 路线（需要 JIT）；MobiVM AOT 管线独立跑通并在 CI 中验证。
下面是把它推进到「游戏本体也 AOT」的可行路径，按依赖顺序排列。

## 阶段 1：AOT 产物进包（1-2 周）

1. 用 MobiVM 的 `framework` target（`robovm.xml` 里 `<target>framework</target>`，或 `robovm:archive`
   指定打包类型）把 AOT 载荷打成 `mcjava_aot.framework`（内部是 dylib）。
2. `Makefile` 的 `payload` 目标里把该 framework 复制进 `Payload/AngelAuraAmethyst.app/Frameworks/`，
   并在 `Natives/CMakeLists.txt` 加链接项。
3. 在 `Natives/main.m`/`JavaLauncher.m` 里调用 RoboVM 运行时的初始化入口（`rvmInitialize`/线程附着），
   先只跑「读版本清单 + 校验资源」这种无 UI 的 preflight，验证嵌入可行。
4. CI 增加「framework 产物存在 + `otool -L` 依赖闭包正确」的检查。

## 阶段 2：启动器 Java 层 AOT（2-4 周）

- 把 `JavaApp/src/launcher/net/kdt/pojavlaunch` 里不依赖 JVM 动态特性的部分（版本解析、下载、
  资源校验、账户）抽成 AOT 模块，通过 JNI/Bridge 暴露给 Obj-C。
- 保留 JRE 仅用于启动游戏进程（游戏本身仍需完整 JVM）。
- 扫描器扩展：解析 Forge/Fabric 的 `mods.toml`/`fabric.mod.json`、mixin 配置里的类名，
  合并进 forceLink。

## 阶段 3：游戏本体 AOT（研究性，不建议作为交付承诺）

必须先解决四个硬约束：

1. **字节码版本**：RoboVM 只到 Java 8 语义；MC 1.13+ 是 Java 17 classfile（v61），需要 RoboVM 的
   ASM/代码生成全面升级到 v61，并支持 records/sealed/indy 等。
2. **动态类加载**：MC 启动器和模组加载器（Forge/Fabric）在运行时从 jar 加载类，AOT 镜像必须
   把整个闭包预先链接——等价于「把所有可能的模组组合都编译进去」，不现实。
3. **JNI/原生库**：LWJGL2/3 通过 `System.loadLibrary` 加载 `.dylib`，RoboVM 只能在构建期静态链接
   原生库并改 JNI 桥接；Amethyst 现有的 `liblwjgl*.dylib` 需要重写成 RoboVM 可链接的静态库。
4. **AWT/图形栈**：MC 的窗口/输入/GL 上下文与 Caciocavallo、MetalANGLE/gl4es 的对接要全部改成
   RoboVM 的 `@Bridge` 形式。

结论：阶段 3 是「重写一个 MC 运行时」级别的工程，不是本仓库短期目标。现实路线是
「AOT 启动器 + JRE 跑游戏」，或等 RoboVM 社区把 Java 17 支持补齐。
