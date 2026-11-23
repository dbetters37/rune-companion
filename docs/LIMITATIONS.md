# Limits and requirements

What the platform allows, what it does not, and what each feature needs from you. Facts about
Android were checked against the SDK itself (API 36 to 37.2), official documentation and AOSP
sources in November 2026. Items that could not be confirmed are marked **unverified**.

## 1. Where this was built, and what that means

| Finding | Consequence |
|---|---|
| Cloud Linux container, 4 vCPUs, 15 GB RAM, JDK 21. No phone attached, no GPU, no hardware virtualisation | Nothing was run on your phone. Hardware capability is measured by the app itself at runtime (Settings > Device) |
| Android SDK installed for the build: platforms 36 and 37.2, build-tools 37 | The app compiles against Android 17 APIs (required by current AndroidX libraries) and targets Android 16 behaviour (`targetSdk 36`); minimum is Android 9 (`minSdk 28`) |
| An Android 14 emulator runs under software emulation only: about 7 minutes to boot, minutes per install | Used for a small set of smoke tests (start-up, keystore, database encryption). Too slow for broad UI testing |
| Unit and UI tests run on the JVM (JUnit, Robolectric, screenshot tests) | This is where almost all verification happens |
| Existing repositories: `runecompanion` is a Compose app with memory and "brain" screens; `rune-companion` was an empty stub; `arcane-bazaar` is a Godot game | The new app lives in `rune-companion`. The existing app has no node-link memory graph and its memories are not linked to each other, so there is no existing graph format to conform to. The memory engine therefore defines a neutral export format and a bridge interface |

## 2. What Android does not allow

A normal app cannot do these. The app does not attempt them.

| Wanted | Reality | Supported route instead |
|---|---|---|
| Control an installed AI app and read its answers | No API. Share, "process text", assistant and deep-link intents only open that app's own UI with your text; its reply stays there | Register the vendor's API as a provider, or run a local model. Or, with your per-app authorisation, operate the app's screen through the accessibility service |
| Call other apps' App Functions (Android 16+ structured app actions) | Reserved for system assistants. The permission is privileged on Android 16 and 17.0; on 17.2 a system allowlist still applies | None for a sideloaded app. Whether the 17.2 allowlist ever admits ordinary apps is **unverified** |
| Use Android's built-in "computer control" for AI assistants | Reserved for pre-installed assistants | Accessibility service on the real screen |
| Read another app's private files, databases or tokens | Blocked by the app sandbox, also for all-files access | That app's own export or share feature, or what is visible on screen |
| Read password fields, secure windows (banking, DRM), or views an app marks as sensitive | Hidden from accessibility services that are not assistive tools. On Android 16+ gestures onto such views are dropped | None. The agent hands back to you |
| Grant itself permissions or special access | Only you can toggle these in system settings | Tools > All tools shows whether Android allows photo access, "Install unknown apps" and Termux commands, and opens Android's own permission dialog or the exact settings page; you decide there. The operator is forbidden from driving Settings, the permission dialog or the installer |
| Install or uninstall apps silently | Every first-time install and every uninstall shows a system confirmation | The system dialog, after the app's own confirmation |
| Complete purchases | No API | The agent can navigate to the purchase; the final confirmation is yours |
| Run downloaded or generated native programs | Blocked for apps targeting Android 10+ on Android 10 and later. On Android 9 a program saved in the workspace can still be run by a shell command you approve | An embedded JavaScript sandbox, shell scripts with system tools inside the app sandbox, or Termux through its official `RUN_COMMAND` intent |
| Read notifications, SMS, call log, contacts, calendar without permission | Each needs its own permission or special access | Could be added later as separate, individually granted capabilities |
| Capture the screen without consent | Blocked | Screenshots through the enabled accessibility service (Android 11+) |
| Unlock the phone or answer a biometric prompt | Blocked, and out of bounds | The run pauses while the phone is locked |
| Toggle Wi-Fi, Bluetooth, mobile data, or change secure settings | Blocked | Opening the relevant settings panel for you |
| Work with Advanced Protection on (Android 17) | Android disables accessibility services that are not assistive tools, and blocks sideloading | Everything except the accessibility operator keeps working; the app says so instead of sending you to Settings |

Declaring the app an "accessibility tool" to see sensitive views or survive Advanced
Protection would be a false declaration that defeats a security control. The app does not
do it.

## 3. What each feature needs

| Feature | Needs | Notes |
|---|---|---|
| Registry of AI apps, agent creation, dashboard, activity log | Nothing | Works offline |
| Cloud models | An API key per provider and internet access | Prompts and conversation go to that provider. Each provider and model is labelled "cloud", and every call is logged as data leaving the device |
| Models on your network (Ollama, LM Studio, llama.cpp server on a computer) | The server's address; the phone on the same network | Labelled "local network". Plain `http://` is allowed only for private and loopback addresses |
| Models on the phone through a local server (for example Ollama in Termux) | Termux and a model that fits the phone's memory | Labelled "on device". Speed depends on the chip |
| Models run inside the app from an imported file | A model file in a supported format, enough RAM and storage | Planned through Google's LiteRT-LM runtime. Device suitability is estimated in Settings > Device. Inference could not be exercised in the build environment |
| Gemini Nano through Android's AICore | A supported device with AICore | Planned as an optional provider; device support varies and is **unverified** per phone |
| The built-in simulator | Nothing | A deterministic stand-in for trying the pipeline. It is not a model and says so |
| Semantic memory search | Nothing by default | Ships with an offline text-similarity embedder. A provider's embedding model can replace it for better recall, which sends memory text to that provider |
| Director and multi-agent work | At least one usable model. Tool-calling models work best | Models without tool calling use a prompted protocol and are less reliable |
| Files in the app's own workspace | Nothing | |
| Files and folders in shared storage | You pick each folder once (Storage Access Framework), or turn on "All files access" in settings | `Android/data` and other apps' private folders stay out of reach either way |
| Photos and media | The media permission, or "selected photos" on Android 14+ | Allow it, or change the selection, from Tools > All tools > Photos and videos |
| Opening apps, sharing to apps, opening links | Nothing | Web addresses and map links open at once. Any other link (payment, call, meeting or chat links) asks you every time and names the app that opens it. Local-only mode blocks links |
| Operating other apps' screens | You enable the app's accessibility service in Android settings, and authorise each target app in the registry | See section 4 for the extra step on sideloaded apps |
| Scripts | Nothing for the JavaScript sandbox and in-sandbox shell. Termux, with `allow-external-apps=true` in its properties and the `RUN_COMMAND` permission granted (from Tools > All tools > Scripts), for full command-line tools | Shell and Termux commands can use the internet, so local-only mode blocks them, and each run is logged as possible data leaving the phone |
| Long runs while the screen is off | Notification permission (Android 13+). Exempting the app from battery optimisation helps on aggressive devices | Objectives run in a foreground service with a visible notification and a Pause action. Android only lets the app start it while the app is on screen |
| Web search by agents | A search provider's API key, or a self-hosted search endpoint | Not built into Android. Without it agents can still open and read specific URLs |

## 4. Installing and enabling

- The app is sideloaded. Builds are signed with one fixed key so an update installs over the
  previous build and keeps your data. Uninstalling deletes all agents, memories and keys.
- **Restricted settings.** For an app installed from an APK file, Android 13+ blocks turning
  on its accessibility service until you allow it:
  1. Settings > Accessibility: try to turn the service on. A "Restricted setting" notice appears.
  2. Settings > Apps > AI Control Center > menu (three dots) > **Allow restricted settings**, then confirm with your PIN or fingerprint. On some Samsung builds this is a row on the app's info page.
  3. Return to Accessibility and turn the service on.

  On Android 15+ the same step also gates "display over other apps", usage access and SMS
  permissions. An update keeps the unlock; a reinstall resets it.
- **Developer verification.** Android is rolling out a requirement that apps installed on
  certified devices come from a verified developer: live since 30 September 2026 in Brazil,
  Indonesia, Singapore and Thailand, expanding worldwide from January 2027. For a personal
  build the options are a free limited-distribution developer account (up to 50 devices),
  the one-time "advanced flow" on the phone for unregistered apps, or installing with `adb`.
  Whether `adb` stays exempt everywhere is **unverified**.

## 5. Model behaviour to expect

- Small local models follow multi-step instructions and tool protocols far less reliably than
  large cloud models. The Director role in particular needs a capable model.
- Provider features differ. Some current models reject parameters such as temperature or
  forced tool choice; adapters drop what a model does not accept and say so in the activity log.
- A provider can refuse a request or substitute a fallback model. The model that actually
  answered is recorded on every call.
- Content an agent reads (web pages, files, other apps' screens, other agents' messages) can
  contain instructions aimed at the model. The framework passes such content as data and
  never lets it raise an agent's permissions, but a model can still be misled by it. Keep
  consequential capabilities behind confirmation, which is the default and cannot be turned
  off for deletes, installs, purchases and sending messages.

## 6. Limits of the safeguards

- **"On this device" means another process on this phone.** A provider at `127.0.0.1` or
  `localhost` is whatever app is listening on that port. Text sent there does not leave the
  phone, but it does leave this app.
- **Local network includes overlay networks.** Addresses in `100.64.0.0/10` count as your own
  network because Tailscale and similar private networks use them to reach a home server. A
  carrier network also uses that range, but nobody enters a carrier's address as a provider.
- **Echoed tool calls.** For models without native tool calling, a call that repeats text from
  a tool result is refused, even if lightly reworded. This is a text comparison: it reduces
  the risk of a model following instructions planted in what it reads, but cannot rule it
  out. The protection that does not depend on the model is that consequential actions
  (deleting, installing, paying, sending) always need your confirmation. No such tools exist
  yet; they arrive with the phone operator.

- **Replacing files.** In the workspace a file that is replaced (written over, moved over or
  imported over) goes to the workspace trash first, so it can be restored; it keeps counting
  against the workspace's 2 GB until the trash is emptied. Shared folders have no trash, so
  replacing a file there asks you every time, like deleting one. Replacing, editing or trashing
  more than 20 existing files within 10 minutes asks you, counted per agent and task across
  calls; the count is kept in memory and starts again when the app restarts.
- **Shell commands.** You see the command, the text of the scripts it runs (long scripts only
  in part, with a note to read the rest in Workspace) and the workspace files it names; if any of
  them change before it runs, it does not run. Files the command reaches only through names it
  builds at run time are not checked. Commands that send work to the background (`&`, `nohup`,
  `setsid`, `disown`, job control) are refused, and each command runs in its own process group
  that is killed when it ends, at its time limit or when it is stopped. A process that
  deliberately leaves that group (for example through a disguised `setsid`, or a downloaded
  program on Android 9) could keep running until Android stops the app.
- **Termux commands** keep running in Termux after the time limit; this app only stops
  waiting for them.
- **The JavaScript sandbox** checks every array built-in that would walk a huge array in one
  step, and refuses it. A construct it does not know of could still keep a script thread busy
  past its time limit; while two such threads run, new scripts are refused.
- **Approval previews** show quote marks inside a quoted value as ⟨"⟩, count long runs of one
  character and piles of combining marks, and mark every invisible or format character, so a
  value cannot pretend to end early or hide what follows. The preview is always drawn left to
  right.

- **Memory sent to cloud models.** When an agent uses a cloud model, memories it recalls or
  finds with its search tool are sent to that provider as part of the conversation, and the
  call is logged as data leaving the device. Local-only mode prevents it. There is no
  per-memory or per-space "never send" mark yet.

- **Agents can mislead each other.** What one agent writes reaches the next agent's prompt as
  quoted data, never as instructions, and cannot change any agent's permissions. A model can
  still be persuaded by it, so an agent that read a planted instruction can pass bad work on.
  Reviews are done by another model and catch mistakes, not attacks.
- **Limits stop work, not cost already spent.** Caps on tasks, messages, model calls and time
  bound how much an objective can do. A cloud provider bills for the calls made before a limit
  was reached.

## 7. Not verifiable in the build environment

- Behaviour on your phone's exact Android version and manufacturer skin, including battery
  management killing background work.
- Real provider accounts. Adapters are tested against recorded protocol shapes on a local
  mock server, not against live services.
- The accessibility operator against real third-party apps.
- On-device model speed and memory use.
