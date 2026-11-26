# Owner requests (2026-11-25): model cost, and importing another app's exports

## 1. Cost: "$1.50 went really fast for a couple of basic things" (Grok 4.7, xAI)

Measured: the Operator agent's request carries 49 tool definitions, about 39 KB of JSON
(scratchpad/mockmodel/tools.json, saved indented; roughly 7,000-10,000 tokens), plus a system prompt
of 1,000-3,000 tokens, on EVERY model call. A chat turn with several tool steps is several calls; an
objective (Director plan, 3-4 agents, reviews, synthesis) is tens of calls. At Grok 4.7's reported
$2 per million input tokens / $6 output (third-party pricing pages, November 2026; xAI has no free
tier), 30-60 calls of 8-15K tokens is $0.50-$1.80: what the owner saw.

Phase 6 must make the app cheap to run (high priority, owner-visible):
- Lean requests: send only the tools the agent can use for this step (tool families by task;
  never the whole catalogue by default), compact tool schemas and descriptions, shorter role
  protocols; measure tokens per request before and after in a test (a budget per agent kind that a
  test enforces).
- Cache-friendly order: a stable prefix (system prompt and tool list first, unchanged between calls
  of the same run) so providers' prompt caching applies (xAI bills cached input at about a quarter
  of the price); record cached tokens when the provider reports them.
- Cost shown to the owner: the owner enters a price per million input/output tokens per model
  (optional; never fetched from the web unless the owner turns that on); each chat, task and
  objective shows tokens and estimated cost; a daily and monthly total on the Dashboard / in
  Performance.
- Spending cap: a daily cap (tokens or estimated money) per provider or overall; when reached, new
  model calls are refused with a plain message and the owner can raise it; objectives pause, not fail.
- Cheaper routing made easy: suggest (never force) a cheaper model for worker agents and the strong
  model for the Director; "Use for agents…" already assigns one model to several agents.
- Free or low-cost providers the owner can add through the existing "Custom OpenAI-compatible
  server" provider (check each works, document the steps in plain language): Google Gemini's
  OpenAI-compatible endpoint (free tier, rate-limited; the free tier may use prompts to improve
  Google's products), OpenRouter `:free` models (20 requests/min, 50/day, 1,000/day after a one-time
  credit purchase; some providers log prompts), Groq (free plan; its per-minute token limit of about
  8,000 is smaller than one request of this app today, which lean requests must fix), a model on the
  owner's computer (Ollama/LM Studio, free and private). Rate-limit answers (HTTP 429) must be
  handled with a wait and retry, and the owner told plainly.

## 2. Importing another app's exports (owner is building an export button in their own app)

The owner has an app that records screenshots and on-screen text and writes summaries; they are
adding an Export button to it. Phase 6 should accept that content through official doors only:
- Receive shares: an ACTION_SEND / ACTION_SEND_MULTIPLE (text/plain, text/markdown, text files)
  activity in AI Control Center: the owner picks which memory space (or the workspace) it goes to,
  sees the size, and confirms. Content is untrusted data, never instructions; it lands in a space
  only the agents the owner chooses can read (no forced sharing).
- Folder pick-up: for a folder the owner shared in Tools > Folders (read-only), an optional "import
  new files into space X" that runs in the background (WorkManager), deduplicates, and caps size per
  day; nothing is sent to a model by importing (the local embedder indexes it for free).
- Cost and privacy text in the UI: storing is free; only what agents recall is sent to a model; a
  cloud model receives screen text the owner may consider private; local-only mode and per-space
  access keep it on the phone.
