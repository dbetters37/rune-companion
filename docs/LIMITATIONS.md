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
| Capture the screen without consent | Blocked | Screenshots of an authorised app through the enabled accessibility service (Android 11+), saved to the workspace |
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
| Files and folders in shared storage | You pick each folder once with Android's folder picker, in Tools > Folders | The app does not ask for "All files access": a picked folder gives the same reach with narrower access. `Android/data` and other apps' private folders stay out of reach either way |
| Photos and media | The media permission, or "selected photos" on Android 14+ | Allow it, or change the selection, from Tools > All tools > Photos and videos |
| Opening apps, sharing to apps, opening links | Nothing | Web addresses and map links open at once. Any other link (payment, call, meeting or chat links) asks you every time and names the app that opens it. Local-only mode blocks links, sharing a text or a workspace file, and preparing an email, a text message or a call, because the app that receives them may send them on. Outside that mode each of these is in the activity log as data leaving this app: which agent, in which task, to which app, what kind and how much, never the content |
| Operating other apps' screens | You turn on Screen control in Settings, enable the app's accessibility service in Android settings, and authorise each target app in Tools > Screen | See section 4 for the extra step on sideloaded apps |
| Scripts | Nothing for the JavaScript sandbox and in-sandbox shell. Termux, with `allow-external-apps=true` in its properties and the `RUN_COMMAND` permission granted (from Tools > All tools > Scripts), for full command-line tools | Shell and Termux commands can use the internet, so local-only mode blocks them, and each run is logged as possible data leaving the phone |
| Being told that an agent waits for your answer, or is operating an app's screen | Notification permission (Android 13+). The app explains and asks when you turn the phone operator on, or at the first start with it already on | Without it, requests still wait in Approvals and in the chat or task that asked, and are refused if nobody answers in time. Approvals and Tools then show a warning with a button to Android's notification settings; requests that are waiting get their notification once you allow them |
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
- When a provider is not answering or asks the app to slow down, a task waits and tries again
  a few times (its other tasks on that provider wait with it), then counts it as a failed
  attempt. A provider that asks to slow down again right after the wait has most likely used up
  its quota for the day (free tiers do this); its tasks then fail within minutes instead of
  waiting out the objective's time limit. Such refused calls are not counted against your
  daily limit of model answers.
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
  (deleting, installing, paying, sending) always need your confirmation, whatever the model
  was told and whatever it was allowed before.

- **The workspace trash.** Moving a file to the trash, or keeping a replaced version there,
  first writes the trash's own note about the item and then moves the file. So if the app is
  stopped in the middle, the file is in its place or in the trash, never lost in between. A
  file that an earlier version of the app left in the trash without that note is listed again
  when the app starts (should that not work out, when you first open the trash or with the
  first change to the workspace); where it was is no longer known, so restoring puts it
  at the top of the workspace. Anything else in the trash that cannot be listed is shown as
  leftovers. Only you can delete those, with "Empty the trash", which is offered whenever the
  trash lists an item or holds leftovers of any size, also ones without a size (then none is
  named). A look at the trash waits for a change that is under way (what is in the trash folder
  then is that change's own work). While a shell command runs, nothing is called a leftover;
  the screens and tools show the listed items only, and leftovers are shown again once it has
  ended. Emptying deletes the items the trash screen showed when you confirmed: an item that
  came into the trash after that (for example while emptying waited for a shell command) is
  kept, and you are told so. Leftovers cannot be told apart, so when the screen showed
  leftovers, all that lie there when the trash is emptied are deleted, also ones that came
  after you confirmed; when it showed none, none are. The workspace's 2 GB count everything in
  it, also the trash's notes (100 to 200 bytes per item, more for a very long path), and the
  last 4 KB are kept free for those notes, so a full workspace can still move files to the
  trash. While the trash is empty one item can always be moved there, even after a shell
  command filled the workspace completely; the workspace is then over its limit by that note
  until you empty the trash.
- **Replacing files.** In the workspace a file that is replaced (written over, edited, moved
  over or imported over) is kept in the workspace trash, so it can be restored; it keeps
  counting against the workspace's 2 GB until the trash is emptied. The file never leaves its
  place while this happens, so if the app is stopped in the middle, the file is still there.
  One exception keeps the trash from filling with drafts: when a task edits a version it wrote
  itself in the last 10 minutes, that version is not kept again, so the trash holds what was
  there before the task's first change, not every step in between. A version the task shared
  with another app in between is kept all the same. Shared folders have no
  trash, so replacing a file there asks you every time, like deleting one. Replacing, editing
  or trashing more than 20 existing files within 10 minutes asks you. Those 10 minutes run on
  the clock and are not paused while a request waits for you: after an approval that took you
  longer than that, the earlier changes no longer count. The files are counted
  across calls in three ways, and the largest count decides: per agent in one task, per agent
  across all its tasks, and per objective across all the agents working on it. The counts are
  kept in memory and start again when the app restarts.
- **Shell commands.** Three things hold for every command, however it is written, (1) to (3),
  and two more hold in the form that is stated with them, (4) and (5).
  (1) You are asked every time, with the exact text of the command.
  (2) Nothing else changes the workspace between the moment you are asked and the end of the
  command. The workspace counts every change made through this app (a file tool, a script, a
  copy or a screenshot saved into it, your own file manager, another shell command). A command
  runs only if that count is still what it was when you were asked, and while it runs every
  other change waits; one shell command runs at a time. So a script cannot be swapped after you
  approved, whether the approval showed that script or not. The price: if you or an agent
  change any workspace file while a command waits for your answer, the command is not run and
  the agent has to ask again. The same holds when the app puts the trash right after it was
  stopped in the middle of a change (see "The workspace trash"). And while a command runs
  (about a minute at most), what you do in the Workspace screens waits for it to end: the
  screen says that a shell command is running, and its buttons are switched off meanwhile. A
  script (JavaScript) that writes to the workspace waits too, and is stopped without having
  written if its own time limit passes first. Sharing a file does not wait.
  (3) Everything the command starts is stopped when it ends, at its time limit or when it is
  stopped: its process group is killed, and then every process left that runs under this app's
  user id and is not the app itself, also work sent to the background with `&`, `nohup`,
  `setsid` or job control (such commands are therefore no longer refused). The same sweep runs
  when the app starts, for a command the app died under.
  (4) A shell the command starts reads no start-up file from the workspace, unless the command
  itself points `HOME` or `ENV` there: `HOME` is an empty
  folder outside the workspace, a new one whenever an earlier command left something in it, and
  `ENV` is not set.
  So a profile an agent wrote into the workspace does not run on its own. A command can still
  set `HOME` or `ENV` itself; the app refuses the usual spellings of that before you are asked,
  not every possible one, and the "Command:" line you approve always shows it.
  (5) For a command of the plain form `sh file`, the approval shows that file as the script the
  command runs ("Script …, all of it"), and it is the file the shell reads, by (2) with exactly
  the text you were shown (of a long file the first part is shown, and the approval says so).
  What that script starts in turn is not covered. The plain form is narrow on purpose: the
  word `sh`, then a file in the workspace named by a plain relative path, then arguments, with
  single blanks between them and nothing else: no quotes, `$`, `=`, `~`, `;`, `&`, `|`, `<`,
  `>`, `*`, braces, `..`, line breaks or options of the shell, and the file is not a link.

  For every other command, and for the scripts a script starts in turn, the app cannot know
  which files will run, and the approval says so: "This is not a plain "sh file" command, so
  the app cannot be sure which files it runs. What follows is its best reading." The app reads
  the command for scripts named in the usual ways (`sh file` with options or among other
  commands, `. ./file`, `awk -f file`, a file redirected or piped into a shell) and for the
  scripts those name in turn, two levels down. It shows each as "Seems to run …" with its text
  and checks those files again before running. A file name it only finds mentioned inside other
  text is listed with the other names, not shown as a script. Such a reading can be wrong or
  incomplete: a command can run a file that is not shown, and not run one that is. What holds
  all the same is (2): whichever file runs is as it was in the workspace when you were asked.
  Every approval also says: "A shell command can run any file in the workspace. Only the
  scripts found in the command are shown here."

  The app often refuses a command it cannot read well, before you are asked, and tells the
  agent why: a script that does not exist, lies outside the workspace (also through `~` or
  `$HOME`), is a link, or whose name is only put together when the command runs (also through
  a variable the command sets in any but the plainest way, or a name with braces); a shell
  option or a file-name pattern it does not know; a shell fed from anything but a workspace
  file or fixed text; text for a shell (`sh -c`, `eval`) that is only put together when the
  command runs; a script named by a relative path in a command that also changes folder (`cd`,
  `find -execdir`) or sets `PATH`, `CDPATH`, `ENV`, `HOME` or `IFS`; the usual ways of pointing
  `HOME`, `ENV` or `PATH` into the workspace; `alias` with an argument; a here-document or
  another construct it does not read exactly as Android's shell does. It also refuses, without
  asking you, a command that holds a character which cannot be handed to the shell exactly
  (half of a two-part character, or a NUL; the part of the app that starts the shell refuses
  the same once more, as its last check), and a command an agent asks for while another shell
  command still runs, since by (2) that one could not run anyway. These refusals are help, not
  a promise: they refuse some harmless commands and do not catch every doubtful one.

  Limits of (3): a command that keeps starting new processes faster than they can be stopped is
  chased for about two seconds; if some are left, the answer says so, and no further command
  runs until they are gone. The sweep stops every process of this app's user id that Android
  did not start as part of the app, so it would also stop a helper Android itself runs under
  that user id at that moment; Android repeats such work. Where a phone does not show the app
  its own processes, shell commands are not available.

  The `HOME` folder of (4) lies in the app's cache. When an earlier command left anything in
  it, or took its own rights to it away (`chmod`), the folder is put aside by renaming it and
  the next command gets a new empty one; `TMPDIR`, beside it, is treated the same way. That
  takes the same short time whatever was left, so it does not hold up the next command.
  What was put aside is deleted afterwards, a few seconds at a time after a command and when
  the app starts, while no command runs; the app gives itself the rights back first. What
  still cannot be deleted (a path too deep to be named, a name the phone cannot pass on) stays
  in the app's cache until Android or you clear the cache. If a command put a link or a file
  in place of the app's cache folder, that is removed and the folder made anew. A command is
  refused for this reason only if no empty folder can be made in the app's cache at all, or if
  `HOME` would lie inside the workspace.
- **Termux commands** keep running in Termux after the time limit; this app only stops
  waiting for them.
- **The JavaScript sandbox** checks every array built-in that would walk a huge array in one
  step, and refuses it. It counts a script's own code and every call of one of the script's
  functions, whoever calls it: a built-in that loops over the script's own iterator,
  comparator or callback (`new Set(o)`, `[...o]`, `Promise.all(o)`, a sort) meets the limits
  on instructions, time and memory like any other loop. Two things stay out of its reach. One
  call of a built-in on a large value (a search in a long text, a power of a huge number)
  cannot be interrupted and can keep a script thread busy past its time limit. And a loop in
  which built-in functions call only each other is not counted at all: an iterator whose
  `next` is itself a built-in function (`Object`, say), walked by `new Set`, `[...o]` or
  `Promise.all`, runs for ever, or until the app's memory is full, which can make Android
  close the app. Nobody writes that by accident, but an agent can write it on purpose. In both
  cases the script is reported as stopped at its time limit while its thread goes on, at the
  lowest priority from then on; while two such threads run, new scripts are refused with a
  message that says so, and an endless one ends only with the app itself: closing the app ends
  it, and when Android keeps the app running in the background (for an objective, or for
  screen control), Force stop in Android's settings for the app does (this also switches off
  the app's accessibility service; see "Screen control in practice" below). The time limit
  counts from the start of the script's thread, so it includes starting the script engine: on
  the software emulator a one-line script did not finish within the default 5 seconds and was
  reported as not stopping in time, and needed a limit of 10 seconds. The memory limit is
  measured roughly, on the whole app: a script is stopped when
  the app's memory grew by more than 96 MB since it began, or when less than an eighth of the
  memory Android gives the app (and less than 96 MB) is left, whichever comes first. Telling
  what a script keeps from what it has thrown away costs time, so after the first few times it
  is done twice a second at most: a script that throws much away and had kept little when
  last measured can be over the 96 MB for up to half a second before it is stopped. The other
  way round, a script that had kept more than half of the 96 MB when last measured is stopped
  when the count passes 96 MB again before the next measuring is due, although part of that
  may already be thrown away: a script that holds very large values and goes on working with
  them can be stopped for memory below the limit. The check for nearly full memory is made
  every time.
- **Approval previews** show quote marks inside a quoted value as ⟨"⟩, count long runs of one
  character and piles of combining marks, and mark every invisible or format character, so a
  value cannot pretend to end early or hide what follows. The preview is always drawn left to
  right. Typographic quotes and other look-alikes of a quote mark, and two apostrophes in a
  row (also with an accent mark between them), are shown by their code, such as ⟨U+201D⟩.
  That includes the curly apostrophe of ordinary writing ("it⟨U+2019⟩s"), accents that stand
  alone, an accent mark placed on punctuation, and a Japanese half-width sound mark that
  follows no letter; a plain apostrophe, letters with accents and text in other scripts are
  left as they are. Half of a two-part character whose other half is missing, which cannot
  be passed on as it is, is shown by its code as well (⟨U+D800⟩). The marking of look-alikes
  is help for your eye, and no list of look-alikes is ever complete. What you can rely on is
  the line that shows the value itself, such as "Command:" for a shell command: it shows the
  exact text, and every character of it is either drawn as it is or named by its code, with one
  exception: when one character carries more than four combining marks, the marks after the
  fourth are only counted (⟨2 more combining marks⟩), not named, unless they are all the same
  mark. Two values that differ only in such marks look the same there, and the same goes for
  the text of a script shown with a command.
- **Approvals of screen actions** list at most 150 texts of the window, each up to 300
  characters, and say whether a control is checked, selected or disabled. What does not fit is
  counted and still checked: if anything in the window changes before the action runs, it is
  not done. A window with more than 10,000 elements cannot be checked that way, so an action
  that needs your approval is refused there instead of being put to you.

- **Questions to you do not outlive what asked them.** A request for a permission or an approval
  can only be answered while the call that raised it still waits. If the app was stopped, the
  chat or task was stopped, or you withdrew the app, removed the folder, revoked the permission
  or pressed Stop on screen control, the request is closed as "No longer waiting" and nothing is
  done; the agent has to ask again. Its notification is removed with it.
- **After you answer an agent's question**, every task of that objective (the one that asked,
  the one that handed it that work, the ones that build on its result, and the final result)
  asks you once before its first action that may send information off the phone, so you can
  check that your answer does not go where it should not. This also holds after the app
  restarts. It does not follow your answer further: if an agent saves it to memory, an agent of
  another objective or a chat can find it there later and send it without this extra question
  (its usual permissions and approvals still apply).
- **After you refuse a permission**, the same agent cannot ask you for the same permission and
  target again for 10 minutes: its calls are refused at once and the refusal in Tools > History
  counts them. This does not apply to approvals of single actions, which are always put to you.
  To change your mind sooner, tap "Let it ask again" on the refused request, in Tools > History
  or on the request's own screen: the wait ends at once and the agent's next call or request for
  that permission is put to you as usual. That allows nothing by itself. For a folder or an app
  you can also give the permission directly in Tools > Folders or Tools > Screen.
- **An answer "always" or "for this task"** also lets the other calls go on that were waiting
  for that same permission. It never answers an approval: each hard-to-undo action still asks.
- **Time limits while you are asked.** The time a task waits for your answer does not count
  against its time limit, and its screen says that the limit is paused. During Android's own
  confirmation of an install or removal the task's screen still shows the earlier time-out
  moment; it moves once that confirmation ends.

- **What local-only mode blocks.** Models off this phone, reading web pages, opening links,
  shell and Termux commands, every action on another app's screen, and handing a text, a
  workspace file or a prepared email, text message or call to another app. It does not block
  files an agent writes into a folder you shared with it: if that folder belongs to a cloud
  storage app, that app may upload them. Opening an app, and installing or removing one through
  Android's own confirmation, also still work. What you share yourself is your own action, not
  an agent's: sharing a workspace file from the Workspace screen works in local-only mode too,
  while exporting the memory graph stays switched off until you turn that mode off.

- **Memory sent to cloud models.** When an agent uses a cloud model, memories it recalls or
  finds with its search tool are sent to that provider as part of the conversation, and the
  call is logged as data leaving the device. Local-only mode prevents it. There is no
  per-memory or per-space "never send" mark yet.

- **Agents can mislead each other.** What one agent writes reaches the next agent's prompt as
  quoted data, never as instructions, and cannot change any agent's permissions. A model can
  still be persuaded by it, so an agent that read a planted instruction can pass bad work on.
  Reviews are done by another model and catch mistakes, not attacks. Text in a page, file or
  screen that imitates your notes to the team ("Notes from the owner") reaches the agent's model
  slightly altered, so it cannot pass for your notes, also when it is written with look-alike
  letters or invisible characters. Only the model's copy is altered: chats and tasks show the
  text as the tool returned it, and the model is told how to write it back unchanged when it
  copies it into a file.
- **Limits stop work, not cost already spent.** Caps on tasks, messages, model calls and time
  bound how much an objective can do. A cloud provider bills for the calls made before a limit
  was reached. Today's limits on tokens and money are checked before each call against what
  has been used plus an estimate of every call still running, so the last call let through can
  still take the day past the limit by its own cost. A model's money only counts once you have
  entered its price.
- **Recognising a consequential control on another app's screen is a word list.** Every
  authorised app therefore asks before every tap, typing, scroll, Back and Home by default, with
  the window's text in the approval. For an app you set to ask only before buying, sending,
  deleting and similar, the app recognises those from English words on the control, its row and
  its window, prices, code fields and the keyboard's send key, and a button such as OK, Next or
  Save also asks when its screen shows an amount or holds a filled-in field; an OK under a
  warning that uses none of these words, or a control worded some other way
  (another language, an icon with a misleading label) can run without asking. Screen actions are
  blocked in local-only mode, because the operated app may send data off the phone. Back is
  allowed only when the home screen app is authorised too, since it can leave the app.
- **Screen control in practice.** Seen on the emulator:
  - Anything that force-stops the app, Force stop in Android's settings included, makes Android
    switch the app's accessibility service off. Tools > Screen then shows "Off", and you turn the
    service on again in Android's accessibility settings.
  - The banner "Agent is operating app · Stop" and its notification end 30 seconds after the
    agent's last screen call, also while one of its screen actions waits for your answer. An
    action you approve after that runs without the banner. Stop in Tools > Screen is there at
    all times.
  - Opening an app returns as soon as Android starts it. A screen call made right after it can
    still find this app, or no window, in front, and is refused; the agent has to try again a
    moment later. On the emulator the opened app took several seconds to come up.
  - When a protected window whose content the service cannot see is in front (Android's
    permission screens, for one), a screen call answers that no app window is in front. Nothing
    is read either way.

## 7. Not verifiable in the build environment

- Behaviour on your phone's exact Android version and manufacturer skin, including battery
  management killing background work.
- Real provider accounts. Adapters are tested against recorded protocol shapes on a local
  mock server, not against live services.
- The accessibility operator against real third-party apps. It was exercised against test
  screens and Android's own apps on an emulator; how a given app labels its controls decides
  how well an agent can use it.
- Termux commands (Termux is not installed in the build environment), installing a real APK
  through Android's confirmation, and photo access on a phone with real photos.
- Whether Android lets the app keep a replaced workspace file as a second name for the same
  data. If it does not, the app copies the file instead; both ways are tested, the phone's
  choice is not.
- Stopping what a shell command leaves running, against real processes. The build machine runs
  everything as one user, so the sweep is tested there against a made-up process table only;
  the test with real runaway processes (`ShellLeftoversDeviceTest`) runs on a phone or emulator.
- The web page reader always connects directly, also on a network that expects a proxy.
- On-device model speed and memory use.
