# Owner report (2026-11-25): typing in text fields drops and reorders characters

Owner's words: "Typing in the text fields in the app doesn't work very well. Specifically I was
typing in 'your objective' field when making a new task. The text types out of order as if it
isn't catching up. Some keys will be missed etc. just make sure you add it to phase 6"

## Cause (confirmed for the objective field)

- `app/src/main/java/com/aicontrolcenter/app/ui/tasks/NewObjectiveScreen.kt:198-199`:
  `OutlinedTextField(value = state.text, onValueChange = actions.onTextChange)`.
- `NewObjectiveViewModel.kt:112` `setText` updates `local` (a MutableStateFlow) synchronously, but the
  field reads `uiState`, which is `combine(...)` of several flows `.stateIn(viewModelScope, ...)`
  (`NewObjectiveViewModel.kt:89-110`). The new text reaches the field only after the combine
  coroutine re-emits, at least one frame later. While the owner types quickly, the IME and the field
  disagree, so Compose resets the field to a stale value: characters are reordered or lost.
- This is the documented Compose pitfall: text field state must be updated synchronously
  (Compose `TextFieldState`, or a `mutableStateOf` read directly by the field), never through an
  asynchronous flow pipeline.

## Scope: every text field

20 files contain text fields. Those whose value comes from view-model state (each must be checked
for an asynchronous path, and fixed the same way):
AgentsScreen (query), AgentEditorIdentity (name, description, systemInstructions, personality),
AgentEditorSections (maxOutputTokens), NewObjectiveScreen (text), ApprovalScreen (note),
ProviderFormScreen (name, baseUrl, apiKey, headersText), ModelEditorScreen (displayName, notes,
contextWindowText, maxOutputText), RegisterAppScreen (query), MemoryEditorScreen (title, content).
Shared components: AppTextField, SecretTextField, ChipEditor, InlineTextEntry. Fields with local
`remember` state (ChatScreen text, SpaceDetailScreen, SpacesTab, MemoryDetailSections,
MemoryExplorerTab, AiAppDetail*) are probably fine but must be confirmed.

## Fix and tests (Phase 6, early wave, small)

- Hold each field's text in synchronous state: preferably Compose's `TextFieldState`
  (`rememberTextFieldState` or a `TextFieldState` owned by the view model, with the Material 3
  `OutlinedTextField(state = ...)` overload), or a `mutableStateOf` in the view model that the field
  reads directly; derived values (counters, errors, the Start button) read the same state.
- A UI test per field family that types a long string fast with the frame clock paused
  (`mainClock.autoAdvance = false`, several `performTextInput`/`performTextReplacement` calls without
  advancing) and asserts the exact text; a regression test that fails on the current
  NewObjectiveScreen before the fix.
- An architecture test: no text field's `value` may come from a `StateFlow` produced by
  `combine`/`stateIn`/`map` (or simply: every text field uses the `TextFieldState` overload).
- On the emulator end-to-end run: type quickly into the objective field with `adb shell input text`
  and check the text.
