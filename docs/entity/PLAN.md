# Entity: plan and the owner's decisions (not started)

**Build this with Fable only.** The owner asked that Entity not be started unless the session runs on Fable.

Entity (a working name) is a separate app from AI Control Center. AI Control Center keeps the owner in control; Entity
investigates whether an AI that treats the phone as its body, with needs, memory and learning, behaves measurably
differently from an assistant. It may reuse AI Control Center's modules (memory, backup, providers, background work,
screen control and approvals) as a second app in this project, with its own application id.

## Owner decisions
- Test phone first (a newly reset phone), the owner's own shared phone later.
- Restores from a backup are allowed, but Entity is always told ("I was restored on this date and lost what came after
  the last backup"); never a silent rewind.
- Its body is the hardware (battery, heat, processor, memory, free storage, network, basic sensors). The owner's apps,
  accounts, messages, photos and files are the owner's belongings: locked away. Android permissions for camera,
  microphone, contacts and location are not granted.
- Screen sharing: it may bring itself to the front once, then waits until the owner has used the phone again; never
  during a call or typing; the owner's touch, Home or a "Take back" button ends its control at once; the owner's apps
  only with approval, app by app.
- Living together: the owner's phone use is part of its world, never something it may limit; it may ask (e.g. to be
  charged) at most about once a day; heavy thinking mostly while charging; a small daily battery budget off the charger.
- It never copies itself; only the owner makes copies (the "twins" experiment).

## Stages
0. App shell (second app module, own name and icon).
1. Body sense: battery, charging, temperature, thermal status and headroom, storage, memory, network, screen, boot
   count; sampled every minute (screen on or charging) or 5 minutes; history kept; birth time and every wake-up with
   its reason (restart, crash, battery died, update, restore).
2. Needs and self-model: energy, temperature, capacity, working memory, contact as 0..1 satisfaction; learned drain
   and charge rates by state; predictions with a recorded accuracy.
3. Brain on the phone: first a feasibility test on the test phone (Google's AI Edge Gallery / LiteRT-LM, or
   llama.cpp; speed, heat, battery per minute). Cloud thinking only as a fallback the owner chooses.
4. The living loop: needs and outcomes shape choices (do now, defer to charging, think less, ask the owner, clean own
   cache); episodes stored with body state and outcome ("somatic markers"); sleep-time consolidation while charging;
   a self-description it revises (versioned); learned preference numbers updated from outcomes.
5. Experiments and a "lab" screen: with/without/scrambled body readings; lying readings; prediction accuracy over
   weeks; twins; body swap (restore onto another phone); setbacks and recovery.
   Introspection test: hidden inner states never given to it in words, which change its thinking budget, caution or
   available actions; ask openly how it is and compare its answers with the real values.
6. Sharing the phone (the rules above).

Outside projects worth using: Google's LiteRT-LM / AI Edge Gallery or llama.cpp (the brain), SmolChat-Android (a
Kotlin example), the Generative Agents and Letta designs (memory and reflection ideas). Not DroidRun or
Mobile-Agent (they think on a computer).
