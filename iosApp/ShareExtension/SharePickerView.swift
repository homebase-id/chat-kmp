import SwiftUI
import UIKit

private let recentsCount = 5

/// Mirrors ChatProtocol.ConversationWithYourselfId in KMP.
private let selfConversationId = "e4ef2382-ab3c-405d-a8b5-ad3e09e980dd"

/// Conversation picker UI for the share extension.
/// Displays conversations in categorized sections: Recents, Contacts, Groups.
/// Supports multi-select — user taps conversations to toggle selection, then taps Send.
struct SharePickerView: View {
    let conversations: [ShareableConversationSwift]
    /// Whether to offer "New Moment" as a destination (media present + Moments activated).
    var showMomentOption: Bool = false
    /// Owner identity so searching your own name/handle surfaces Note to Self (#984).
    var ownerDisplayName: String? = nil
    var ownerDomain: String = ""
    let onSelect: ([String]) -> Void
    var onSelectMoment: () -> Void = {}
    let onCancel: () -> Void

    @State private var searchText = ""
    @State private var selectedIds: Set<String> = []

    private var isSearchActive: Bool {
        !searchText.isEmpty
    }

    private var filtered: [ShareableConversationSwift] {
        conversations.filter {
            $0.displayName.localizedCaseInsensitiveContains(searchText) ||
                ($0.id == selfConversationId && matchesSelfQuery(searchText))
        }
    }

    /// Mirrors KMP SelfSearch.matchesSelfQuery: query hits the owner's name or handle.
    private func matchesSelfQuery(_ query: String) -> Bool {
        if query.isEmpty { return false }
        if let name = ownerDisplayName, name.localizedCaseInsensitiveContains(query) { return true }
        return ownerDomain.localizedCaseInsensitiveContains(query)
    }

    /// "Name (you)" label for the self row, matching the Android picker.
    private var selfLabel: String? {
        let name = ownerDisplayName.flatMap { $0.isEmpty ? nil : $0 }
            ?? (ownerDomain.isEmpty ? nil : ownerDomain)
        return name.map { "\($0) (you)" }
    }

    private func displayModel(_ conversation: ShareableConversationSwift) -> ShareableConversationSwift {
        guard conversation.id == selfConversationId, let label = selfLabel else { return conversation }
        return ShareableConversationSwift(
            id: conversation.id,
            displayName: label,
            avatarInitials: conversation.avatarInitials,
            isGroup: conversation.isGroup,
            participantCount: conversation.participantCount,
            lastMessageTimestamp: conversation.lastMessageTimestamp,
            avatarUrl: conversation.avatarUrl
        )
    }

    private var recents: [ShareableConversationSwift] {
        Array(
            conversations
                .sorted { $0.lastMessageTimestamp > $1.lastMessageTimestamp }
                .prefix(recentsCount)
        )
    }

    private var recentIds: Set<String> {
        Set(recents.map { $0.id })
    }

    private var contacts: [ShareableConversationSwift] {
        conversations
            .filter { !$0.isGroup && !recentIds.contains($0.id) }
            .sorted { $0.displayName.localizedCompare($1.displayName) == .orderedAscending }
    }

    private var groups: [ShareableConversationSwift] {
        conversations
            .filter { $0.isGroup && !recentIds.contains($0.id) }
            .sorted { $0.displayName.localizedCompare($1.displayName) == .orderedAscending }
    }

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                if showMomentOption {
                    newMomentButton
                    Divider()
                }

                conversationContent
            }
            .navigationTitle("Share to...")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(action: { onSelect(Array(selectedIds)) }) {
                        if selectedIds.count > 1 {
                            Text("Send (\(selectedIds.count))")
                        } else {
                            Text("Send")
                        }
                    }
                    .fontWeight(.semibold)
                    .disabled(selectedIds.isEmpty)
                }
            }
            .searchable(text: $searchText, prompt: "Search conversations...")
        }
    }

    /// "New Moment" destination row, shown above the conversation list when the
    /// share carries media and Moments is activated. Mirrors the Android picker.
    private var newMomentButton: some View {
        Button(action: onSelectMoment) {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(Color.accentColor.opacity(0.15))
                    Image(systemName: "sparkles")
                        .foregroundColor(.accentColor)
                        .imageScale(.large)
                }
                .frame(width: 40, height: 40)

                VStack(alignment: .leading, spacing: 2) {
                    Text("New Moment")
                        .font(.body)
                        .fontWeight(.medium)
                    Text("Share to your Moments")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .foregroundColor(Color(UIColor.tertiaryLabel))
                    .imageScale(.small)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var conversationContent: some View {
            Group {
                if conversations.isEmpty {
                    emptyState
                } else {
                    VStack(spacing: 0) {
                        if isSearchActive {
                            List(filtered) { conversation in
                                conversationButton(conversation)
                            }
                            .listStyle(.plain)
                        } else {
                            List {
                                if !recents.isEmpty {
                                    Section {
                                        ForEach(recents) { conversation in
                                            conversationButton(conversation)
                                        }
                                    } header: {
                                        Text("Recents")
                                    }
                                }

                                if !contacts.isEmpty {
                                    Section {
                                        ForEach(contacts) { conversation in
                                            conversationButton(conversation)
                                        }
                                    } header: {
                                        Text("Contacts")
                                    }
                                }

                                if !groups.isEmpty {
                                    Section {
                                        ForEach(groups) { conversation in
                                            conversationButton(conversation)
                                        }
                                    } header: {
                                        Text("Groups")
                                    }
                                }
                            }
                            .listStyle(.insetGrouped)
                        }
                    }
                }
            }
    }

    private func conversationButton(_ conversation: ShareableConversationSwift) -> some View {
        Button(action: { toggleSelection(conversation.id) }) {
            ShareConversationRow(
                conversation: displayModel(conversation),
                isSelected: selectedIds.contains(conversation.id)
            )
        }
        .buttonStyle(.plain)
    }

    private func toggleSelection(_ id: String) {
        if selectedIds.contains(id) {
            selectedIds.remove(id)
        } else {
            selectedIds.insert(id)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 48))
                .foregroundColor(.secondary)
            Text("No conversations available")
                .font(.headline)
            Text("Open Homebase Chat to load your conversations.")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
        }
    }
}

/// A single conversation row in the share picker.
struct ShareConversationRow: View {
    let conversation: ShareableConversationSwift
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 12) {
            ShareAvatarView(
                conversationId: conversation.id,
                avatarUrl: conversation.avatarUrl,
                initials: String(conversation.avatarInitials.prefix(2)),
                isGroup: conversation.isGroup
            )
            .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 2) {
                Text(conversation.displayName)
                    .font(.body)
                    .lineLimit(1)

                if conversation.isGroup {
                    Text("\(conversation.participantCount) members")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            Spacer()

            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(.accentColor)
                    .imageScale(.large)
            } else {
                Image(systemName: "circle")
                    .foregroundColor(Color(UIColor.tertiaryLabel))
                    .imageScale(.large)
            }
        }
        .contentShape(Rectangle())
    }
}

/// Avatar view that loads images with this priority:
/// 1. Cached group avatar from App Group (pre-decrypted by main app)
/// 2. Public avatar URL (for 1:1 contacts)
/// 3. Initials fallback
struct ShareAvatarView: View {
    let conversationId: String
    let avatarUrl: String?
    let initials: String
    let isGroup: Bool

    private static let appGroupId = Bundle.main.infoDictionary?["AppGroupIdentifier"] as? String ?? "group.id.homebase.feed"

    @State private var loadedImage: UIImage?
    @State private var loadFailed = false

    var body: some View {
        ZStack {
            if let image = loadedImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .clipShape(Circle())
            } else {
                Circle()
                    .fill(Color(UIColor.tertiarySystemFill))
                Text(initials)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(.secondary)
            }
        }
        .task {
            await loadAvatar()
        }
    }

    private func loadAvatar() async {
        guard !loadFailed else { return }

        // Try cached group avatar from App Group first
        if isGroup, let image = loadCachedGroupAvatar() {
            await MainActor.run { loadedImage = image }
            return
        }

        // Try public URL for 1:1 contacts
        guard let urlString = avatarUrl,
              let url = URL(string: urlString)
        else { return }

        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200,
                  let image = UIImage(data: data)
            else {
                loadFailed = true
                return
            }
            await MainActor.run { loadedImage = image }
        } catch {
            loadFailed = true
        }
    }

    private func loadCachedGroupAvatar() -> UIImage? {
        guard let containerURL = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: Self.appGroupId)
        else { return nil }

        let path = containerURL.appendingPathComponent("avatars/\(conversationId).png")
        guard let data = try? Data(contentsOf: path) else { return nil }
        return UIImage(data: data)
    }
}
