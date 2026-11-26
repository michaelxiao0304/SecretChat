// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import UserNotifications

struct ContactsScreen: View {
    @ObservedObject var model: DeviceModel
    @State private var invite: String?
    @State private var invitePresented = false
    @State private var settingsPresented = false
    @State private var notificationNote: String?

    var body: some View {
        List {
            if let contacts = model.profile?.contacts, !contacts.isEmpty {
                Section("我的联系人") {
                    ForEach(contacts) { peer in
                        NavigationLink {
                            ConversationScreen(model: model, id: peer.id)
                        } label: {
                            Label {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(String(peer.id.wire.prefix(8))).font(.headline.monospaced())
                                    Text((model.profile?.access(to: peer) ?? .missing).title)
                                        .font(.subheadline).foregroundStyle(.secondary)
                                }
                            } icon: { Image(systemName: "person.crop.rectangle").foregroundStyle(InvitationStyle.ink) }
                        }
                    }
                }
                Section { inviteButton }
            } else {
                Section {
                    VStack(alignment: .leading, spacing: 14) {
                        Image(systemName: "person.crop.rectangle.badge.plus")
                            .font(.largeTitle).foregroundStyle(InvitationStyle.ink).accessibilityHidden(true)
                        Text("找到第一位联系人").font(.title.bold()).accessibilityAddTraits(.isHeader)
                        Text("如果刚用朋友的邀请码注册，刷新后就能查看邀请人。")
                        Button { Task { await model.refresh() } } label: {
                            Label("刷新联系人", systemImage: "arrow.clockwise")
                        }.buttonStyle(InvitationAction()).disabled(model.busy)
                        Divider()
                        Text("想邀请朋友？生成单次邀请码，分享给对方。对方注册后再刷新。")
                            .foregroundStyle(.secondary)
                        inviteButton.buttonStyle(.borderless)
                    }.padding(.vertical, 10)
                }
            }
            Section {
                DisclosureGroup("通知设置") { notificationControls }
            }
        }
        .scrollContentBackground(.hidden).background(InvitationStyle.paper)
        .refreshable { await model.refresh() }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("开发联调", systemImage: "slider.horizontal.3") { settingsPresented = true }
            }
        }
        .sheet(isPresented: $invitePresented) {
            if let invite {
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 20) {
                            Image(systemName: "envelope.open").font(.largeTitle).foregroundStyle(InvitationStyle.ink)
                            Text("把邀请交给对方").font(.title.bold())
                            Text("请发给准备注册的朋友。这个邀请码只能使用一次。")
                            Divider()
                            Text(invite).font(.title3.monospaced().weight(.semibold))
                                .textSelection(.enabled).privacySensitive()
                            Divider()
                            if let server = model.profile?.server {
                                ShareLink(item: SharedInvitation(code: invite, server: server).text) {
                                    Label("分享邀请", systemImage: "square.and.arrow.up")
                                }.buttonStyle(InvitationAction())
                                Text("邀请包含以下服务地址。朋友可在 SecretChat 首页粘贴整张邀请。")
                                    .font(.footnote).foregroundStyle(.secondary)
                                Text(server.absoluteString).font(.callout.monospaced()).textSelection(.enabled)
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading).padding(24)
                    }
                    .navigationTitle("单次邀请").navigationBarTitleDisplayMode(.inline)
                    .toolbar { Button("完成") { invitePresented = false } }
                }.protectedSurface()
            }
        }
        .sheet(isPresented: $settingsPresented) {
            NavigationStack {
                Form { Section("当前账号绑定的服务") {
                    Text(model.profile?.server?.absoluteString ?? "未配置").textSelection(.enabled)
                } }
                .navigationTitle("开发联调").navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("完成") { settingsPresented = false } }
            }.protectedSurface()
        }
    }

    @ViewBuilder private var inviteButton: some View {
        if invite != nil {
            Button { invitePresented = true } label: { Label("查看并分享邀请码", systemImage: "envelope") }
        }
        Button {
            Task { await model.perform {
                invite = try await model.issueInvite()
                invitePresented = true
            } }
        } label: {
            Label(invite == nil ? "生成单次邀请码" : "签发新邀请码", systemImage: "envelope.badge")
        }.disabled(model.busy)
    }

    @ViewBuilder private var notificationControls: some View {
        Button("启用新消息通知") {
            Task { await model.perform {
                let allowed = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
                Diagnostics.event(.push, "apns.permission", allowed ? .ok : .skip)
                if allowed { UIApplication.shared.registerForRemoteNotifications() }
                notificationNote = allowed ? "已允许通知" : "通知权限已关闭，可在系统设置中更改。"
            } }
        }
        if let notificationNote { Text(notificationNote).font(.caption) }
        Toggle("在通知中显示正文", isOn: Binding(get: { model.profile?.settings.showBody == true },
            set: { value in Task { await model.setPreview(value) } })).disabled(model.busy)
    }
}
