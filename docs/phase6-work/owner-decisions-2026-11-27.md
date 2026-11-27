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
