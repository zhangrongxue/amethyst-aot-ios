# Amethyst AOT iOS —— 在 Windows 上云端构建《我的世界》Java 版 iOS 启动器

本仓库基于 [AngelAuraMC/Amethyst-iOS](https://github.com/AngelAuraMC/Amethyst-iOS)（PojavLauncher 系 iOS 启动器），
把「Windows 本地编排 + GitHub Actions macOS 云端构建 + MobiVM AOT 阶段 + 反射扫描器」串成一条流水线，
最终产出一个**无签名 IPA**（可交给 TrollStore / AltStore / SideStore 安装）。

```
Windows (你)                          GitHub Actions (macos-15)
┌───────────────────┐                 ┌────────────────────────────────────────────┐
│ scripts/all.ps1   │  git push       │ job: ipa                                   │
│  ├ publish.ps1 ───┼────────────────▶│  checkout+submodules → brew/jdk8/ldid       │
│  ├ trigger.ps1    │  dispatch       │  gmake dsym package PLATFORM=2 SLIMMED=1   │
│  ├ watch.ps1      │  poll           │  scripts/verify_ipa.py  (结构化校验)        │
│  └ download.ps1 ◀─┼─────────────────│  upload-artifact: amethyst-unsigned-ipa     │
│       │           │                 │                                            │
│       ▼           │                 │ job: aot                                   │
│  dist/*.ipa       │                 │  fetch_minecraft.py → reflect-scan         │
└───────────────────┘                 │  → gen-robovm-config → MobiVM robovm:console│
                                      │  → JVM 输出 vs AOT 输出 diff               │
                                      └────────────────────────────────────────────┘
```

## 快速开始

前置：Windows + `git` + `python3` + JDK 17（`winget install Microsoft.OpenJDK.17`），以及一个**公开** GitHub 仓库
（公开仓库的 macOS runner 免费；私有仓库 macOS 按 10 倍计分钟，免费额度只有 200 分钟/月）。

```powershell
# 1) 本地自检：编译扫描器、下载真实 Minecraft 1.12.2 jar、跑反射扫描、生成 forceLink 配置
powershell -File scripts/bootstrap.ps1

# 2) 配凭据（二选一）
#    a. 让 Git Credential Manager 走浏览器登录（push 时弹窗）
#    b. 写 token 文件（不会进 git）
New-Item -ItemType Directory -Force .secrets | Out-Null
Set-Content .secrets\gh_token "ghp_xxx"      # 需要 repo + workflow 权限

# 3) 一键：提交推送 → 触发云端构建 → 等待 → 下载 IPA 到 dist\ 并校验
powershell -File scripts\all.ps1 -Repo <你的GitHub用户名>/<仓库名>
```

产物：`dist/org.angelauramc.amethyst.slimmed-1.0-ios.ipa`（无签名；同时会产出未 slim 的完整 IPA）。

## 目录

| 路径 | 作用 |
|---|---|
| `Makefile` / `Natives/` / `JavaApp/` | 上游 Amethyst-iOS 源码；`Makefile` 只改了 JRE 下载/解包逻辑（见下） |
| `tools/reflect-scan/` | 反射扫描器（Java + ASM）：扫 jar 里的 `Class.forName`、`getDeclared*`、MethodHandles、Proxy、ServiceLoader，产出 `forceLinkClasses` + JSON/Markdown 报告 |
| `mobivm/` | MobiVM/RoboVM AOT 载荷（Maven 工程）：用 Gson 反射反序列化 MC 版本清单结构，CI 里 AOT 编译并运行，与 JVM 输出逐字比对 |
| `scripts/` | Windows 编排脚本（bootstrap / publish / trigger / watch / download / all）+ 校验与配置生成 |
| `.github/workflows/build-ios.yml` | 两个 job：`ipa`（构建无签名 IPA）、`aot`（MobiVM AOT 管线验证） |
| `dist/` | 最终 IPA 落盘位置（git-ignored） |

## 对上游 Makefile 的唯一改动

上游用 `curl ... zip; tar xf jre-N.tar.xz -C java-N-openjdk` 解包，遇到压缩包内多一层目录时会把
`java_runtimes/java-8-openjdk/jre8-.../` 装错层级，App 启动时找不到 `lib/server/libjvm.dylib` 直接闪退。
现在改成：解到临时目录 → `find -name release` 定位真实根目录 → 移正 → 强制校验
`release` 与 `lib/server/libjvm.dylib` 都存在，否则构建失败（失败早于打包，不会产出坏 IPA）。
同时把 JRE 源抽成变量 `JRE_BASE`，默认 `https://assets.angelauramc.dev/openjdk/ios-arm64`，可自行换镜像：

```bash
gmake jre JRE_BASE=https://your.mirror/openjdk/ios-arm64
```

## MobiVM AOT：做到了什么，没做到什么

**做到了（可复现、CI 每次验证）**

- MobiVM/RoboVM 2.3.23 工具链真实跑通：`mvn -f mobivm/pom.xml -Drobovm.arch=arm64 robovm:console`
  把 Java 字节码 AOT 编译成 arm64 原生程序并执行；
- 反射扫描器在**真实 Minecraft 1.12.2 客户端 + 34 个官方库**（Guava / Netty / ICU / JNA / Log4j / Realms …）上运行，
  自动发现 233 处反射点，生成 73 条 `forceLinkClasses` 与报告（`work/scan-mc/`，CI 里作为 artifact 上传）；
- 扫描结果经 `scripts/gen-robovm-config.*` 注入 `mobivm/robovm.xml`，AOT 程序启动时
  `Class.forName("dev.mcjava.aot.Probe") + getDeclaredMethod + invoke` 成功，说明反射元数据确实被保留；
- CI 对比 JVM 与 AOT 两条路径的确定性输出，不一致即失败。

**没做到（以及为什么）**

- **没有把 Minecraft 客户端本身 AOT 成原生**。RoboVM/MobiVM 只支持 Java 8 级字节码与语义，
  不支持运行时动态类加载（模组 jar）、完整 JIT 语义、以及 MC 1.13+ 需要的 Java 17 特性；
  LWJGL 的 JNI 原生库也无法在 AOT 镜像里 `dlopen`。所以现在能装的 IPA 仍然是
  「Amethyst + 4 套 iOS JRE（JIT）」的官方形态——它确实能玩，但需要 TrollStore 或调试器开 JIT。
- AOT 载荷尚未接管 App 运行路径。MobiVM 的 `framework` target 可以把 AOT 镜像打成 dylib 塞进
  `Payload/*.app/Frameworks/`，但要与 Amethyst 的 Obj-C 主程序对接，需要写桥接层并替换
  `JavaLauncher` 的启动流程，这是下一步工作（见 `docs/ROADMAP.md`）。

## 已知风险

- 上游 CI 用的是自托管 runner（`J316sAP` / `MacStadium`）；本仓库改用 GitHub 托管 `macos-15`，
  Xcode/SDK 版本与上游不同，首次跑通可能需要微调（日志会随 artifact 上传）。
- 子模块（MobileGlues / fishhook / AFNetworking / DBNumberedSlider）固定在上游某个 commit，
  若上游 force-push 导致 commit 消失，`git submodule update` 会失败——届时改 `.gitmodules` 指到可用 commit。
- 无签名 IPA 只能经 TrollStore / AltStore / SideStore 等侧载工具安装；直接装会因签名失败被拒。

## License

Amethyst-iOS 及其第三方组件许可见 `LICENSE` 与 README 的 "Third party components" 一节。
本仓库新增的 `tools/`、`mobivm/`、`scripts/` 以 MIT 发布。
