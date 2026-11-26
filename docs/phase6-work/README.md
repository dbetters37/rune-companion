# Phase 6 work notes (where the work stopped)

These are the planning and hand-off notes of Phase 6, copied from the build machine so that the next session can continue.

## State at the 0.6.0-preview build (versionCode 9)

- Waves 1, 2 and 3 are built and merged on `claude/ai-control-center-architecture-wjdfww`. The whole suite passed at the
  wave-3 merge (88060fa); the stub gate is final (no stub left).
- Wave 3's design, security and integration reviews found 36 problems (1 critical, 7 major, 28 minor). **None is fixed
  yet**: see `wave3-review-findings.md`. The critical one: stopping or a refused backup save deletes the existing file the
  owner chose to replace (until fixed, always save a backup under a new file name).
- Not done: the wave-3 fixes and their verification, wave 4 (adversarial review rounds, plan section 9; the Tier B
  switches are decided after its round 2), wave 5 (emulator end-to-end of the signed build, plan section 10, with the
  steps in `device-tests-wave2*.md` and `device-tests-wave3.md`), wave 6 (docs: STATUS, LIMITATIONS, ARCHITECTURE, README;
  release 0.6.0 with versionCode 10 or higher, since 9 is the preview).

## Read in this order

1. `phase6-plan.md` (the OWNER DECISIONS block at the top overrides the rest), `phase6-contracts.md`.
2. `wave3-review-findings.md` (open), `wave3-ownership.md`, `handoff-wave3b-merge.md`, `handoff-wave3a-merge.md`,
   `wave3a-minors-left.md`, `wave2a-minors-left.md`, `wave2b-minors-left.md`.
3. The per-package hand-offs (`handoff-*.md`) as needed, and `device-tests-*.md` for the device run.
