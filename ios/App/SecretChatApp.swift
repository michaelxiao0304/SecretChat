// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import UserNotifications

@main
struct SecretChatApp: App {
    @UIApplicationDelegateAdaptor(PushDelegate.self) private var push
    @StateObject private var model = DeviceModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootScreen(model: model)
                .protectedSurface()
                .task { model.open() }
                .onChange(of: phase) {
                    switch phase {
                    case .active: Diagnostics.event(.lifecycle, "scene.foreground", .ok)
                    case .background: Diagnostics.event(.lifecycle, "scene.background", .ok)
                    default: break
                    }
                }
                .task(id: phase == .active ? model.profile?.account?.deviceId : nil) {
                    guard phase == .active, model.profile?.account != nil else { return }
                    if await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .authorized {
                        UIApplication.shared.registerForRemoteNotifications()
                    }
                    await model.listen()
                }
                .onReceive(push.$token.compactMap { $0 }) { value in Task { await model.tokenChanged(value) } }
        }
    }
}

private struct RootScreen: View {
    @ObservedObject var model: DeviceModel

    var body: some View {
        NavigationStack {
            Group {
                if model.profile?.account != nil { ContactsScreen(model: model) }
                else if model.profile != nil { RegistrationScreen(model: model) }
                else if model.issue != nil {
                    ContentUnavailableView {
                        Label("无法打开本机数据", systemImage: "lock")
                    } actions: { Button("重试") { model.open() } }
                } else { ProgressView() }
            }
            .navigationTitle(model.profile?.account == nil ? "SecretChat" : "联系人")
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) {
                if let issue = model.issue {
                    Text(issue).font(.footnote).frame(maxWidth: .infinity).padding(8).background(.regularMaterial)
                }
            }
        }
        .tint(InvitationStyle.ink)
        .privacySensitive()
    }
}

private struct PrivateSurface: ViewModifier {
    @Environment(\.scenePhase) private var phase
    @Environment(\.isSceneCaptured) private var captured

    func body(content: Content) -> some View {
        content.overlay {
            if phase != .active || captured { Color(uiColor: .systemBackground).ignoresSafeArea() }
        }
    }
}

extension View {
    func protectedSurface() -> some View { modifier(PrivateSurface()) }
}
