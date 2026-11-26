# Hand-off from WP0 (text input, the owner's typing bug #42)

Branch `p6/wp0` (worktree /home/user/p6-wp0), based on 9c739d5.

## 1. A file outside WP0's named list was changed (please confirm at merge)

- **app/src/main/java/com/aicontrolcenter/app/ui/chat/ChatViewModel.kt.** The plan lists ChatScreen.kt as
  "confirm only", but the chat composer had the same fault in a smaller form (a local copy of the text that was
  reset from `state.draft` whenever the view model's value changed, so a late value could overwrite newer keys),
  and `TextFieldRuleTest` requires the `state =` overload. The draft now lives in `ChatViewModel.draft`
  (a `TextFieldState`); `ChatUiState.draft` follows it; `send()` sends exactly what the field holds and empties it;
  `ChatUiState.canSend(text)` was added (the old `canSend` property stays). No other wave-1 package touches
  app/ui/**, so there is nothing to merge against in wave 1. The section-5 table gives WP0 "app/ui/**
  (text-field files only)" for wave 1, which this file is.

## 2. Nothing else outside WP0's files was changed

MemoryScreen.kt and MemoryExplorerViewModel.kt are untouched: the explorer's search keeps its `TextFieldState`
in MemoryExplorerTab.kt (which WP0 owns) and reports each change through the existing `onQueryChange`.

## 3. For the integrator and every later package that adds a text field (WP3, WP8, WP10, WP11a/b, WP16)

- Every text field must use the `state =` overload. `TextFieldRuleTest` (app/src/test/.../ui/components/)
  fails the build on any `...TextField(` call without `state =`, on `TextFieldValue`, and on a `TextFieldState(`
  created outside a view model file or a `remember`.
- Use `AppTextField(state = …)` and `SecretTextField(state = …)` (ui/components). A form's text lives in a
  `TextFieldState` created once in its view model; what the view model derives from it (draft, validation,
  filter) follows through `TextFieldState.textChanges()`; text from code (a loaded record, emptying after a send)
  goes in with `TextFieldState.putText(value)`, never while the owner types. Number fields use
  `inputTransformation = digitsOnly(n)`; length caps use `atMostChars(n)`.
- A view model's `edit` should start from what the fields hold now (they can be a key ahead of the view-model
  state) and push into a field only a text that the edit itself changed; see `AgentEditorViewModel.edit`,
  `ProviderFormViewModel.edit`, `ModelEditorViewModel.edit`, `MemoryEditorViewModel.edit`.
- Save/Start/Send read the fields directly at the moment of the tap.
- `SecretTextField` is now Material 3's `OutlinedSecureTextField`: every character hidden (not even the last one
  typed is shown), password keyboard (no suggestions, no learning), no copy or cut. WP3's passphrase and WP10's
  "Start over" word should use it. `FLAG_SECURE` on those screens (rule 27) is theirs: those screens do not exist
  yet.
- Rule 27 asks for a paused-clock typing test per new field family: copy the `typeFast`/`typed` helpers and one
  test from `FastTypingTest`.

## 4. Docs (docs owner, wave 6)

- docs/STATUS.md: owner report #42 fixed in 0.6.0 (every text field holds its text in a `TextFieldState`;
  FastTypingTest, TextFieldRuleTest).
- §10 step 3 (adb `input text` of 200 characters into "Your objective") is still to be run on the emulator in
  wave 5.
