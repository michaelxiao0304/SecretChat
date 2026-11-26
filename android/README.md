# SecretChat Android

Android 9 及以上的一对一加密文字聊天客户端。基线为 `main@ecd594f`，邀请格式、公钥目录、应用层信封和投递回执对齐 `codex/ios-client@d538837`。接口与 `codex/service-console-foundation@ac94497` 的目录和信箱契约一致。客户端使用 Kotlin、Android 原生视图和 libsignal 0.108.6。

## 构建与验收

设置 `JAVA_HOME` 指向 JDK 21，`ANDROID_HOME` 指向 Android SDK，安装平台 36、Build Tools 36.0.0 和 NDK 28.2.13676358。NDK 用于裁剪 APK 中的原生库调试符号。仓库提供带 SHA-256 校验的 Gradle 9.6.0 Wrapper。首次构建需要访问 Google Maven、Maven Central、Gradle 分发站和 Signal 构建产物库。

在本目录运行以下命令。

```sh
./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

| 产物 | 相对于本目录的路径 |
|---|---|
| 开发签名 APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 未签名 Release APK | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| 单元测试报告 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint 报告 | `app/build/reports/lint-results-debug.html` |

APK 包含 arm64-v8a、armeabi-v7a、x86 和 x86_64。Release 的分发签名由发布方持有。单元测试使用主机 JVM 和 Signal 官方 JNI，可在上游支持的 macOS、Windows 或 Linux 主机上运行。

## 使用开发服务

1. 打开「凭邀请加入」，粘贴 iOS 或 Android 分享的完整邀请，或输入大小写敏感的单次邀请码，再填写邮箱。邀请指定的服务和本机配置必须一致。Debug 默认连接 `https://101.132.60.65/secretchat`，可在「服务设置」修改。只有 Debug 可开启与测试服务匹配的免验证码模式。
2. Release 默认使用邮箱验证码。服务地址可取自完整邀请，也可以在「服务设置」输入受系统信任的 HTTPS 地址。Debug 仅对指定联调 IP 信任仓库根 README 列出的公开证书，证书 SHA-256 为 `57e5c25f1572f0074d5399dcc2d054fd2a72ba6ab01683194b9c142376aa3b00`。登录后的服务地址随设备凭据存入加密快照。
3. 注册后，在「联系人」签发并分享邀请，或兑换已有用户的邀请。进入联系人对话后可发送文字。已建立会话时可以离线排队。每次提交或重试前刷新目录，对方被拉黑、被移除、换设备或换身份时暂停投递。
4. 启动、回前台和收到 WebSocket 提示时同步，前台定时补拉可恢复断线期间错过的提示。「服务端已接收」和「对方设备已收到」分别来自服务入队和设备确认。现有服务提供 APNs；Android 后台推送需要新增对应的服务端通道。

## 协议、存储与测试

- 目录注册时上传身份公钥、带签名的 EC 预密钥和 Kyber 预密钥。一次性预密钥低于 4 个时补 16 个。上传前持久化同一批次的私钥和公钥，回包丢失可安全重试。
- 一台活跃设备对应协议地址的设备槽 1。身份公钥和服务设备 UUID 一起绑定到会话。服务密文的首字节是 libsignal 消息类型，认证载荷含消息 ID、双方账号及文字。收件时比对内外层标识。
- 使用 Android Keystore 中的 AES-256-GCM 密钥和免备份目录中的原子快照。每次加密使用新的 IV。棘轮记录、待发密文和绝对 TTL 一起提交。同一轮分页固定队尾，保存新会话状态、消息、游标与待 ACK 引用之后才确认。前台消息窗口启用 `FLAG_SECURE`。
- JVM 用例覆盖真实协议双向收发、乱序、无一次性预密钥的回退、篡改、身份切换、跨页中断、ACK 丢失、写盘失败、重启、密钥补给、HTTP 契约和存储完整性。测试资源 `ios-v0.108.6.json` 由参考分支的 `ClientState.prepare` 和随机测试身份生成，含接收端测试私钥，可离线重复解密。
- 本次另在 iOS 模拟器运行参考分支的 Swift 协议实现，向 Android JVM 发送该样本，再由同一 Swift 会话解密 Android 生成的回复。真机安装、真实账号及公网端到端联调留待设备验收。
- 功能范围对应 iOS 当前文字聊天原型。根 README 后续阶段的安全码复核、邮箱找回、撤回、阅后销毁、举报和注销尚待实现。当前持久化面向小规模会话，每次事务重写整份快照。

## 许可与依赖

本目录自有客户端源码使用 AGPL-3.0-only，完整条款见 [LICENSE](LICENSE)。原型按提交自行构建。向他人提供 APK 时一并提供对应提交的完整客户端源码、依赖版本和构建说明。

libsignal 由 Signal Messenger, LLC 及其贡献者提供，适用 AGPL-3.0-only。Gradle 按固定 SHA-256 验证官方 Android AAR 和 JVM JAR，APK 随上游 AAR 包含第三方致谢。AndroidX、Kotlin、OkHttp、Okio 与 Gson 使用 Apache-2.0。Android 核心库脱糖组件使用 GPL-2.0 with Classpath Exception。[Signal 上游源码及许可](https://github.com/signalapp/libsignal/tree/v0.108.6) · [二进制校验值](gradle/verification-metadata.xml)
