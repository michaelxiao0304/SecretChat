// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

struct ConversationScreen: View {
    @ObservedObject var model: DeviceModel
    let id: UUID
    @State private var draft = ""
    @State private var showIdentity = false

    private var peer: Contact? { model.profile?.contacts.first { $0.id == id } }
    private var access: ContactAccess { model.profile?.access(to: peer) ?? .missing }
    private var messages: [ChatMessage] { model.profile?.messages.filter { $0.peer == id } ?? [] }
    private var shortID: String { String(id.wire.prefix(8)) }

    var body: some View {
        VStack(spacing: 0) {
            history
            if access == .available, let peer {
                composer(for: peer)
            } else {
                Button { showIdentity = true } label: {
                    VStack(alignment: .leading, spacing: 5) {
                        Label(access.title, systemImage: "exclamationmark.shield").font(.headline)
                        if access == .identityChanged {
                            Text("目录中的设备身份与本机记录不一致。").font(.subheadline)
                        }
                        Text("新消息和待发重试已暂停。查看详情").font(.subheadline)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding()
                }.buttonStyle(.plain).background(.regularMaterial)
            }
        }
        .background(InvitationStyle.paper)
        .navigationTitle(shortID).navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("联系详情", systemImage: "info.circle") { showIdentity = true }
            }
        }
        .onChange(of: access, initial: true) {
            if access != .available { Diagnostics.event(.mailbox, "conversation.paused", .skip, reason: access.diagnosticReason) }
        }
        .sheet(isPresented: $showIdentity) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 16) {
                    Image(systemName: "person.crop.rectangle").font(.largeTitle).foregroundStyle(InvitationStyle.ink)
                    Text(shortID).font(.title.monospaced().bold())
                    Text(access.title).font(.headline)
                    Text(access.explanation)
                    Spacer()
                }.frame(maxWidth: .infinity, alignment: .leading).padding(24)
                .navigationTitle("联系详情").navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("完成") { showIdentity = false } }
            }.protectedSurface().presentationDetents([.medium, .large])
        }
    }

    private var history: some View {
        let thread = messages
        return ScrollViewReader { scroll in
            ScrollView {
                LazyVStack(spacing: 16) {
                    if thread.isEmpty {
                        Text("向对方说第一句话").font(.headline)
                        Text("首次建立会话需要联网。").font(.subheadline).foregroundStyle(.secondary)
                    }
                    ForEach(thread, id: \.rowID) { message in
                        MessageBubble(message: message, access: access, latest: message.rowID == thread.last?.rowID)
                    }
                }.padding()
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: thread.last?.rowID, initial: true) {
                if let last = thread.last { scroll.scrollTo(last.rowID, anchor: .bottom) }
            }
        }
    }

    private func composer(for peer: Contact) -> some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("文字消息", text: $draft, axis: .vertical)
                .lineLimit(1...6).padding(.horizontal, 14).padding(.vertical, 11)
                .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20))
            Button {
                let text = draft
                Task { await model.perform {
                    try await model.send(text, to: peer)
                    if draft == text { draft = "" }
                } }
            } label: {
                Image(systemName: "arrow.up.circle.fill").font(.system(size: 34)).frame(width: 44, height: 44)
            }.accessibilityLabel("发送")
                .disabled(model.busy || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }.padding(.horizontal).padding(.vertical, 8)
    }
}

private extension ChatMessage {
    var rowID: String { (outgoing ? "out-" : "in-") + id.wire }
}

private struct MessageBubble: View {
    let message: ChatMessage
    let access: ContactAccess
    let latest: Bool
    @State private var expanded: Bool?

    var body: some View {
        let outgoing = message.outgoing
        let pending = outgoing && message.status == .pending
        let showExplanation = expanded ?? (latest && pending)
        VStack(alignment: outgoing ? .trailing : .leading, spacing: 5) {
            Text(message.text ?? "无法解密")
                .foregroundStyle(outgoing && !pending ? Color.white : Color.primary)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(fill, in: RoundedRectangle(cornerRadius: 15))
                .overlay {
                    if pending {
                        RoundedRectangle(cornerRadius: 15).strokeBorder(InvitationStyle.ink, style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    }
                }
            if outgoing {
                Button { expanded = !showExplanation } label: {
                    Label(message.status.label, systemImage: message.status.symbol)
                        .font(.footnote.weight(.medium)).padding(.vertical, 5)
                }.buttonStyle(.plain).foregroundStyle(InvitationStyle.ink)
                    .accessibilityHint("展开或收起投递说明")
                if showExplanation {
                    Text(message.status.explanation(access: access)).font(.footnote)
                        .multilineTextAlignment(.trailing).foregroundStyle(.secondary)
                }
            }
        }
        .padding(outgoing ? .leading : .trailing, 40)
        .frame(maxWidth: .infinity, alignment: outgoing ? .trailing : .leading)
    }

    private var fill: Color {
        guard message.outgoing else { return Color(uiColor: .secondarySystemBackground) }
        return message.status == .pending ? InvitationStyle.ink.opacity(0.08) : InvitationStyle.blue
    }
}
