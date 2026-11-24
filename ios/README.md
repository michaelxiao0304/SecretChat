# SecretChat iOS 开发原型

本阶段采用开发者从对应提交自行构建并签名安装的方式。在邀请其他人使用构建产物时，向接收者一并提供本提交的完整客户端源码、依赖锁和构建说明。公开分发渠道及商店条款留待发布前评估。本目录客户端代码使用 AGPL-3.0-only，许可证见 LICENSE。libsignal 由 Signal Messenger, LLC 等贡献者提供，沿用上游 AGPL-3.0-only 及其第三方声明。

协议依赖固定为 [libsignal v0.108.6](https://github.com/signalapp/libsignal/tree/v0.108.6)。SignalBinary/Package.swift 固定同版 Rust XCFramework 的官方 SHA-256。Swift 源码与二进制一起链接到应用和通知扩展。
