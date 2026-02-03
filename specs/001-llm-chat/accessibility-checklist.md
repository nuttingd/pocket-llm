# TalkBack Verification Checklist

**Per-story TalkBack smoke test per constitution principle V.**

## Conversation List (Drawer)

- [ ] Swipe right/left navigates between conversation items
- [ ] Each conversation announces: title, last message snippet, date
- [ ] "New conversation" button is announced
- [ ] Search field announces placeholder text
- [ ] Delete action is announced with confirmation

## Chat Screen

- [ ] Messages read in chronological order (root to leaf)
- [ ] User messages announce "You: [content]"
- [ ] Assistant messages announce "Assistant: [content]"
- [ ] Streaming message cursor is not read aloud
- [ ] Send button announces "Send message"
- [ ] Stop button announces "Stop generating"
- [ ] Attach button announces "Attach image"
- [ ] Camera button announces "Take photo"
- [ ] ThinkingSection toggle announces "Expand thinking" / "Collapse thinking"
- [ ] Long-press context menu items (Copy, Edit, Regenerate, Delete) are reachable
- [ ] Image thumbnails announce "Attached image, tap to preview"
- [ ] Compaction indicator announces "Context compacted" and is activatable
- [ ] Server/model selector announces "Switch server or model"
- [ ] Conversation parameters button announces "Conversation parameters"
- [ ] More options button announces "More options"
- [ ] Token usage footer is focusable and announces count

## Server Configuration

- [ ] "Server: [name]" announced on each server card
- [ ] Test button state change (testing/idle) announced
- [ ] Test result text is read after connection test
- [ ] Model chips are focusable and announce model name
- [ ] Edit/Delete buttons announce "Edit [name]" / "Delete [name]"
- [ ] Form fields have labels (Server Name, Base URL, API Key)
- [ ] Save/Cancel buttons are announced

## Settings Screen

- [ ] All toggles announce label and current state
- [ ] Font size slider announces current value
- [ ] Temperature/parameter sliders announce values
- [ ] Section headers are focusable landmarks

## Conversation Settings Sheet

- [ ] Bottom sheet opening/closing announced
- [ ] System prompt field has label
- [ ] Parameter sliders announce name and value
- [ ] Preset chips are announced with name
- [ ] Tool toggles announce tool name and enabled state
- [ ] Reset to defaults button is announced

## General

- [ ] All touch targets are at least 48dp
- [ ] No information conveyed by color alone
- [ ] Focus order matches visual order
- [ ] Back gesture/button dismisses dialogs and sheets
- [ ] Error snackbars are announced
- [ ] Loading states are announced (model loading, connection testing)
