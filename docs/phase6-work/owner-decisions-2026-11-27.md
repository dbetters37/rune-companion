# Owner decisions, 27 November 2026 (build on Saturday)

These replace the earlier approval rules where they differ. The owner chose them explicitly.

## What still asks the owner first (one tap, showing exactly what happens)
- Sending a text, making a call, sending an email. Build direct sending: the agent shows who and what, the owner taps
  Send once in AI Control Center, and it is sent (SmsManager / call intent / email) without screen navigation.
- Buying or paying, in any app.
- Inside Android Settings, only these pages: factory reset, lock screen and passwords, removing accounts, and giving
  other apps special powers (accessibility, device admin, install unknown apps).

## What no longer asks the owner
- Android Settings is no longer a protected app: agents may operate it (except the pages above, which ask).
- Deleting files (shared folders, emptying the workspace trash): no longer asks.
- Installing or removing apps: AI Control Center no longer asks; Android still shows its own confirmation.
- Shell and Termux commands, screen taps and typing in approved apps: no per-action asking (a one-time allow per tool
  is fine). This supersedes the "Allow for this task" idea.
- Unchanged unless the owner says otherwise: password fields are never typed into, the lock screen is never operated,
  AI Control Center never operates itself.

## Also build on Saturday
- Agents are told up front what they can and cannot do and which tool fits (texts, calls, emails, contacts), so they
  do not try shell commands to find out.
- "Find a contact": contacts read access, granted by the owner in Android's permission prompt.
- Then test one real task on the owner's phone, step by step, before building anything else.

## The owner's real objectives (the yardstick for Saturday)
"I need it to be an app where I put something in the objectives and the agents do it without me having to baby them."
Each is done only when it runs on the owner's phone with no help except the send/pay taps.

| Objective | State on 27 Nov | Needed |
|---|---|---|
| Make a task list | Works: a workspace file | Optional: Google Tasks or Keep |
| Organize my files on my phone | Partly: shared folders only (Tools > Folders) | A move tool; the new no-ask rules; guide the owner to share Download, Documents, etc. |
| Find space on my phone | Partly: large files in shared folders and media | Settings access (Storage page, clearing app caches through Settings) |
| Look for jobs near me | Partly: no location, no web search; some pages readable | A web search tool; the owner's city in memory |
| Organize my email | Not built (only slow screen control in Gmail) | A Gmail connection through Google's official sign-in |
| Check my email/phone for any tasks that need done | Not built | Gmail connection, notification access (owner switches it on), calendar access |

Order: task list and files; find space; jobs; email and tasks (biggest).

## 25 more objectives (adapted from Google's AndroidWorld benchmark, github.com/google-research/android_world)
State on 27 Nov: W works, P partly, N not built.
1. Text Mom that I'm running late (P: contacts, direct send)
2. Reply to my latest text with "On my way" (N: reading messages; Android restricts SMS reading, likely via screen control in Messages)
3. Call John (P: contacts)
4. Send me the address Sarah texted me (N: reading messages)
5. Email Dave the PDF from my Downloads (P: attachment, send after one tap)
6. Add a dentist appointment Tuesday 3pm (P via screen: calendar access)
7. What's on my calendar this week? (N: calendar access)
8. Set an alarm for 6:30 tomorrow (P via screen: alarm tool, AlarmClock intent)
9. Start a 20-minute timer (P via screen: timer tool)
10. Remind me to pay rent on the 1st every month (P: routines plus a reminder notice)
11. Find and delete duplicate photos (P: shared photo folders; identical files only)
12. Delete screenshots older than 30 days (P: share Screenshots; no-ask delete)
13. Copy my important documents into a Backup folder (W: share folders first)
14. Find the document about my lease (P: text inside PDFs)
15. Turn this list of purchases into a spreadsheet (W: CSV file)
16. Add up what I spent this month from my notes (W: notes in a shared folder)
17. List my subscriptions and when they renew (N: Gmail connection)
18. Turn on dark mode / Wi-Fi / battery saver (N: Settings access)
19. Silence my phone until 7am (N: Settings access, Do Not Disturb)
20. Show apps I haven't used in months and their size (N: Settings access, usage access)
21. Find the cheapest price for an item near me (P: web search tool)
22. Summarize today's news about a topic (P: web search tool)
23. Check the weather; do I need a jacket (P: city in memory, web search)
24. Turn my notes into a task list by priority (W)
25. Every morning: weather, calendar, tasks, important emails (P: routines plus calendar, Gmail, web search)
Small additions with big payoff: alarm and timer tools; a web search tool; then the Gmail connection.

## The owner's tool list (what the app must be able to DO), 27 Nov
"I need phone access, messages access, contacts, and 25 other things." Every tool uses an official Android feature the
owner allows once. Sending, calling and paying still ask one tap. Sizes: S small, M medium, L large.

Core: Contacts (find/add/edit, S); Messages (read texts, send after one tap; SMS is a restricted permission, needs
"Allow restricted settings", M); Phone (call after one tap, call history, S).

1 Notification reader (notification access, M). 2 Notification reply (WhatsApp/Messenger quick reply after one tap, M).
3 Email over IMAP/SMTP (read, search, sort, archive, move; send after one tap; Gmail via app password, L).
4 Calendar (read/add/edit/delete, S). 5 Alarms (AlarmClock intents, S). 6 Timers (S). 7 Location (S).
8 Web search (a search API key, M). 9 All-files access (MANAGE_EXTERNAL_STORAGE switch, M).
10 Storage analyzer (biggest files, duplicates, M). 11 App usage (usage access, S).
12 PDF/document text reader (PdfBox-Android, M). 13 Text from pictures (ML Kit on-device OCR, M).
14 Image understanding (vision through the connected model, M). 15 Image generation (xAI image API, M).
16 Camera (photo, document scan, M). 17 QR/barcode scanner (S). 18 Voice in (speech to text, M).
19 Voice out (text to speech, S). 20 Do Not Disturb, ringer, volume (DND access, S).
21 Brightness and screen timeout (modify system settings, S). 22 Flashlight (S). 23 Clipboard (S).
24 Media control (play/pause/skip through notification access, S). 25 Device status (battery, storage, memory, network, S).
Reference for the shape of these: termux/termux-api on GitHub.
First batch suggested: the core 3, notification reader, calendar, alarms and timers, all-files access, web search.
