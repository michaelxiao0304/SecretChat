# SecretChat iOS 开发原型

本阶段采用开发者从对应提交自行构建并签名安装的方式。在邀请其他人使用构建产物时，向接收者一并提供本提交的完整客户端源码、依赖锁和构建说明。公开分发渠道及商店条款留待发布前评估。本目录客户端代码使用 AGPL-3.0-only，许可证见 LICENSE。libsignal 由 Signal Messenger, LLC 等贡献者提供，沿用上游 AGPL-3.0-only 及其第三方声明。

协议依赖固定为 [libsignal v0.108.6](https://github.com/signalapp/libsignal/tree/v0.108.6)。SignalBinary/Package.swift 固定同版 Rust XCFramework 的官方 SHA-256。Swift 源码与二进制一起链接到应用和通知扩展。

## 构建与测试

直接打开 `SecretChat.xcodeproj`，选择 `SecretChat` scheme 和 iPhone 模拟器。首次构建从 GitHub 和 Signal 官方产物服务器下载依赖。最低部署目标是 iOS 17，本次使用 Xcode 27.2（27B52）。项目文件已提交，修改 target 或源文件清单时可以在本目录运行 `xcodegen generate` 重新生成。

```sh
xcodebuild -project SecretChat.xcodeproj -scheme SecretChat -showdestinations
xcodebuild -project SecretChat.xcodeproj -scheme SecretChat \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro,OS=27.2' \
  -parallel-testing-enabled NO -resultBundlePath /tmp/SecretChatTests.xcresult test
xcrun xcresulttool get test-results summary --path /tmp/SecretChatTests.xcresult
xcrun xccov view --report /tmp/SecretChatTests.xcresult
```

重复运行时选择新的结果包路径。测试使用两套独立生成的 libsignal 身份和真实的 Swift/FFI 绑定。通过可注入的 HTTP 交换与独立临时加密库复现离线、断点和错误响应。

## 两台设备联调

1. 提供实现目录和信箱接口的开发服务。客户端对齐 `codex/service-console-foundation` 的 `db8eec1`。输入证书受设备信任的 HTTPS 服务根地址，保留部署前缀，例如 `https://<开发域名>/secretchat`。同一地址的 `/ws` 需要支持 WSS。
2. 在 Xcode 给主应用和通知扩展选择开发团队，配置团队拥有的两个 bundle ID、同一个 App Group（项目设置 `SECRETCHAT_APP_GROUP`），并为主应用启用开发环境 Push Notifications。服务端的 APNs topic 应与主应用 bundle ID 一致。
3. 在服务控制台领取首账号邀请码，在第一台 iPhone 填写邮箱并完成注册。只有服务已开启免验证码联调模式时才打开相应开关。第一台设备生成单次邀请，第二台用它注册。双方回到前台或下拉刷新即可打开对话。
4. 两台各发送一条文字，观察发送状态。接收端离线时再发送，重新打开后核对消息和状态。设备首次解锁后，允许通知并测试锁屏、后台、强制退出以及拒绝通知权限。记录实际推送延迟和失败时的固定文案。

## 端侧状态与协议

- 每个账号有一个活跃设备。服务传输层使用小写 UUID 设备标识，协议地址使用小写账号 UUID 和设备槽 1。本地分别固定身份公钥和服务设备 ID，任一变化将暂停后续发送及重试。
- 目录字段 `identityKey` 是序列化的身份公钥。`signedPrekey` 是包含 `id/key/signature` 的 JSON 字节，`protocolPrekeys` 是包含 `registrationId/kyber` 的 JSON 字节。`kyber` 同样包含 `id/key/signature`。所有字节字段按服务约定使用 Base64。一次性预密钥的 `material` 是序列化的 EC 公钥。设备余量低于 4 时补 16 个，先落盘私钥与待上传批次。回包丢失后重用同一批次。
- 服务信封的 `ciphertext` 首字节为 libsignal 消息类型，其余为序列化密文。加密载荷包含消息 ID、发送及接收账号与文字。进入会话前会比对内外标识和目录身份。离线信封默认 TTL 为 6 天。
- Keychain 用 `AfterFirstUnlockThisDeviceOnly` 保护 256 位本机密钥。开发原型把设备令牌、协议记录、历史、待发密文和待 ACK 引用保存在排除备份、受文件保护的 AES-GCM 快照中，以原子替换提交。应用是唯一写入方。通知扩展从共享容器读取快照，在内存副本上解密，默认仅显示发件人短号。网络、解密或时限失败时显示固定文案。
- P0 使用单份快照，每次事务重写文件，适合小规模开发会话。后续持久化数据库与旧会话升级需要独立验收。已接收、已过期等投递状态取自服务端证据。状态记录不可查时显示“投递结果未确认”。

## 定位问题

应用与通知扩展使用统一日志子系统 `chat.secretchat.ios`。真机可在 macOS「控制台」中选中连接的 iPhone，启用信息级消息，再按子系统筛选。模拟器可在终端运行以下命令，其中设备 ID 可从 `xcrun simctl list devices booted` 取得。

```sh
xcrun simctl spawn <设备ID> log stream --level info --predicate 'subsystem == "chat.secretchat.ios"'
xcrun simctl spawn <设备ID> log show --last 10m --info --predicate 'subsystem == "chat.secretchat.ios"'
```

按复现时间和 `trace` 还原一次操作，先找 `failed`、`retry`、`skip` 或 `fallback`，再向前查看阶段。主应用与扩展分别生成随机追踪号。`elapsed_ms` 来自单调时钟；`code` 在网络阶段表示 HTTP 状态或 URL 错误码，在 Keychain 阶段表示 OSStatus。信息级记录的保留时间由系统管理，联调时可提前开启实时采集。

| 症状 | 先查的分类和事件 |
|---|---|
| 打不开本机数据 | `lifecycle/app.open`、`storage/keychain.read`、`keychain.create`、`vault.commit` |
| 注册或首次建会话失败 | `network` 中的邮箱挑战、注册或预密钥请求，随后看 `protocolState/prekeys.replenished`、`lifecycle/device.operation` 的失败原因 |
| 发送停在待提交 | `mailbox/outbox.committed` 后的 `outbox.paused`、`outbox.conflict` 或提交请求的状态码 |
| 重连后缺消息或无法解密 | `network/socket.listen`、`socket.reconnect`、`mailbox/inbox.page`、`inbox.committed`、`inbox.discard`、`protocolState/inbox.decrypt` 和 ACK 请求 |
| 锁屏只出现固定文案 | `push/apns.permission`、`apns.registration`、令牌绑定请求，以及扩展的 `extension.receive`、`preview.policy`、`preview.render`、`extension.deadline` |

日志字段限制为固定阶段名、随机追踪号、枚举、数量、状态码和耗时。请求地址、动态路径、账号、邮箱、邀请码、验证码、消息 ID、推送引用、令牌、密钥、网络报文和私聊正文均不进入应用日志。系统日志仅保存在设备的统一日志设施中。

## 本轮验收范围

已实现注册与邀请新设备、联系人列表、一对一加密文字、设备变化暂停、前台唤醒和补拉、按设备确认、通知预览及固定文案回退。安全码重新核验、换机找回、撤回、阅后销毁、举报和注销按根 README 后续阶段推进。后台推送的真实到达率、锁屏共享 Keychain 权限和两台真机的联调结果须在已签名的设备与配置好的 APNs 服务上记录。

## 开发记录 · 2026-11-24

1. 核对远端默认分支，以 `main@08e22e3` 创建 `codex/ios-client` worktree。服务端协议以同仓服务分支文档和控制器的状态码为准。
2. 固定 Swift 源码提交及二进制校验和，记录原型分发与源码交付方式。先写建会话、乱序、重投与重启测试，观察红灯，再接入 iOS 应用和通知扩展。
3. 以模拟器验证两套设备身份的双向收发，再接入加密快照、持久待发和 ACK 队列、分页边界与身份检查。加入网络回包丢失、磁盘提交失败和一次性预密钥消费的回归。
4. 加入跨阶段追踪日志，用系统日志实际还原一次注入的断网故障。提交、同步、设备操作三个失败事件带有同一个追踪号，网络层记录 `URLError -1005`。用例同时检查成功、失败和回退路径的脱敏。
5. 使用 XCTest 和 Xcode 行覆盖报告进行验证。`ub-mode` 列出的三个工具当前支持的语言未包含 Swift。变异检查使用临时源码副本，在相同模拟器上运行对应的 XCTest。[Crapper 支持范围](https://github.com/unclebob/crapper) · [Dryer 支持范围](https://github.com/unclebob/dryer) · [Mutator 支持范围](https://github.com/unclebob/mutator)
