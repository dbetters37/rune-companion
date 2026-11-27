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
