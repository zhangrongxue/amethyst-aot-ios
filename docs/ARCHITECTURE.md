# 架构说明

## 1. 为什么是「Windows 编排 + macOS 云端」

Amethyst-iOS 的 `native` / `jre` / `payload` / `package` 目标全部依赖 Apple 工具链
（`xcrun actool`、`vtool`、`ldid`、`dsymutil`、iPhoneOS.sdk），Windows 上无法编译 iOS 应用。
所以本机只做两件事：

1. **编排**：提交、触发 workflow、轮询状态、把 artifact 拉回 `dist/`；
2. **可离线验证的部分**：反射扫描器（纯 Java）、Minecraft jar 获取、配置生成。

真正的编译在 GitHub Actions 的 `macos-15` runner 上完成。

## 2. 构建 job（ipa）的数据流

```
checkout (with submodules)
   ├─ Natives/external/{MobileGlues, fishhook, AFNetworking, DBNumberedSlider}
   └─ Natives/external/UnzipKit（仓库内已跟踪头文件）
brew install make ldid ; brew install --cask temurin@8
   └─ BOOTJDK=/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home/bin
gmake -j dsym package PLATFORM=2 SLIMMED=1
   ├─ dep_mg   : cmake 构建 MobileGlues -> libmobileglues.dylib
   ├─ native   : cmake 构建主程序、awt_xawt、tinygl4angle、AFNetworking…
   ├─ java     : JDK8 编译 JavaApp -> launcher.jar / lwjgl.jar / patchjna_agent.jar
   ├─ jre      : curl 4 个 iOS JRE -> 解包 -> depends/java-{8,17,21,25}-openjdk
   ├─ assets   : xcrun actool 编译 Assets.xcassets
   ├─ payload  : 组装 Payload/AngelAuraAmethyst.app（复制 dylib/jar/资源/java_runtimes，ldid -S 临时签名）
   ├─ package  : zip --symlinks -> artifacts/*.ipa
   └─ dsym     : dsymutil 产出 dSYM
scripts/verify_ipa.py
   ├─ Info.plist 存在且 CFBundleIdentifier/CFBundleExecutable 齐全
   ├─ 主二进制是 Mach-O arm64
   ├─ java_runtimes/java-{8,17,21,25}/lib/server/libjvm.dylib 四个都在
   ├─ Frameworks/ 关键 dylib 在（liblwjgl/libopenal/libMoltenVK）
   └─ 无 _CodeSignature（确认无签名）
```

`SLIMMED=1` 会额外产出一个去掉 `java_runtimes/` 的瘦身 IPA（体积从几百 MB 降到几十 MB），
运行时由用户在 App 内选择/下载运行时。

## 3. AOT job（aot）的数据流

```
scripts/fetch_minecraft.py --version 1.12.2
   └─ piston-meta 版本清单 -> client.jar + 34 个库（规则过滤 osx/arm64）-> work/mc
mvn -f tools/reflect-scan package
   └─ tools/reflect-scan/target/reflect-scan.jar（shade 后的独立 jar）
reflect-scan --jars work/mc --out work/scan-mc
   ├─ force-link-classes.txt   RoboVM 需要的类型级保留清单
   ├─ reflection-report.json   机器可读
   └─ reflection-report.md     人读摘要
mvn -f mobivm package + 扫描 mobivm 自身 classpath -> work/scan-payload
scripts/gen-robovm-config.sh -> mobivm/robovm.xml 的 GENERATED 块
mvn -f mobivm exec:java            -> work/jvm-expected.txt （JVM 参考输出）
mvn -f mobivm -Drobovm.arch=arm64 robovm:console -> work/aot-actual.txt（MobiVM AOT 输出）
diff 两者，不一致 -> 失败
```

## 4. 反射扫描器的判定规则

| 字节码特征 | 归类 | 产出 |
|---|---|---|
| `Class.forName("X")` / `ClassLoader.loadClass("X")`，参数为 LDC 字符串 | `class-load` | 若 X 在扫描集的类索引里 → 精确 forceLink；否则进 `unresolved`（JDK 前缀的归入 `jdkRuntime`，由 RoboVM rt 提供） |
| `Class.getDeclaredMethod/getMethod(...)` / `getDeclaredField/getField` / 构造器 | `method` / `field` / `ctor` | owner 取同方法内最近的类字面量（`Foo.class`）或最近的 `Class.forName` 目标；owner 已知 → forceLink |
| `MethodHandles.Lookup.findVirtual/findStatic/...` | `method-handle` | 同上 |
| `Proxy.newProxyInstance(..., Class<?>[] ifaces, ...)` | `proxy` | 接口类 forceLink |
| `ServiceLoader.load(X.class)` + `META-INF/services/*` | `service` | 服务接口与实现类都 forceLink |
| 反射方法体内任意"像全限定类名"的字符串常量 | `class-name` | 命中已索引类 → forceLink（防止名字经局部变量中转） |

同一包里被反射命中的类 ≥ `--min-package-hits`（默认 3）时，额外输出 `pkg.**` 通配，
避免 RoboVM 因个别漏项在运行期 `ClassNotFoundException`。

## 5. 为什么 `forceLinkClasses` 是 AOT 的关键

RoboVM 在编译期做可达性分析，只保留从入口静态可达的类；`Class.forName` 这类调用在编译期看不见目标，
如果不显式 forceLink，运行期就会 `ClassNotFoundException`——这正是反射扫描器存在的理由，
也是「自动扫描反射」在这条流水线里的位置。
