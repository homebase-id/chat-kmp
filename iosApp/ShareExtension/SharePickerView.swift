import SwiftUI

/// Conversation picker UI for the share extension.
/// Displays a searchable list of recent conversations from the encrypted cache.
struct SharePickerView: View {
    let conversations: [ShareableConversationSwift]
    let updatedAt: Date?
    let onSelect: (String) -> Void
    let onCancel: () -> Void

    @State private var searchText = ""

    private var filtered: [ShareableConversationSwift] {
        if searchText.isEmpty { return conversations }
        return conversations.filter {
            $0.displayName.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                if conversations.isEmpty {
                    emptyState
                } else {
                    if let updatedAt = updatedAt {
                        HStack {
                            Spacer()
                            Text("Updated \(updatedAt, style: .relative) ago")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                                .padding(.horizontal)
                                .padding(.top, 4)
                        }
                    }

                    List(filtered) { conversation in
                        Button(action: { onSelect(conversation.id) }) {
                            ShareConversationRow(conversation: conversation)
                        }
                        .buttonStyle(.plain)
                    }
                    .listStyle(.plain)
                    .searchable(text: $searchText, prompt: "Search conversations...")
                }
            }
            .navigationTitle("Share to...")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
            }
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

    var body: some View {
        HStack(spacing: 12) {
            // Avatar circle with initials
            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.15))
                    .frame(width: 44, height: 44)
                Text(String(conversation.avatarInitials.prefix(2)))
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.accentColor)
            }

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
        }
        .contentShape(Rectangle())
        .padding(.vertical, 4)
    }
}
