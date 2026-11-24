# 已选方向 A · 一张邀请

2026-11-24，用户选择原话：「使用方向A」。

已展示 [A · 一张邀请](screenshots/dir-1.png)、[B · 消息回执](screenshots/dir-2.png)、[C · 往来手记](screenshots/dir-3.png)。以 [A 的可操作原型](dir-1.html) 为首次对话的 SwiftUI 实现依据。模拟网络、填入演示资料等原型控件仅用于设计评审。

## SwiftUI 实现

[五种原生状态并排](native-a/overview.jpg) · [改版前、首轮和定稿对照](native-a/iterations.jpg)

[邀请首页](native-a/registration.jpg) · [邮箱验证](native-a/form.jpg) · [空联系人](native-a/empty.jpg) · [本机待提交](native-a/chat.jpg) · [设备已收到](native-a/received.jpg) · [身份变化暂停](native-a/paused.jpg)

邀请首页和邮箱验证截图由 XCUITest 在正式 App 上实际点击取得。其余截图采自同一机型、同一版本模拟器的原生窗口，使用合成账号和预置消息，复用客户端的 SwiftUI 页面。回执来自给定的测试状态；暂停场景实际替换联系人公钥。机型为 iPhone 18 Pro，系统为 iOS 27.2。
