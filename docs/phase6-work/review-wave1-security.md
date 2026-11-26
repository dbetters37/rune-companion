# Wave-1 security review (reviewer: review:wave1-security, against 8ac755d)

## F1. [major] Folder pick-up lets an agent write into a memory space it has no right to write

**where:** core/src/main/kotlin/com/aicontrolcenter/core/pickup/PickUp.kt:122-138 (OwnerPickUpRules contract); testkit/src/main/kotlin/com/aicontrolcenter/testkit/InMemoryRoutines.kt:231-241 (ReferenceOwnerPickUpRules.create, which OwnerPickUpRulesContract pins)

**problem:** A pick-up rule may name any shared folder, including one agents can write to. An agent that holds a folders.write grant for that folder (MODIFYING; an ALWAYS grant needs no further ask) can drop a text file there. The upkeep job then imports it, while nobody watches, into the rule's memory space S. Every agent that reads S recalls it, even though the writing agent is not in S's writable list. This gets round per-space write control (the rule 'Do not force all agents to share all memories'), and it is an agent widening its own reach without the owner. The contract also does not fix the imported memory's provenance, so nothing stops it being stored or recalled as the owner's own words.

**evidence:** PickUp.kt:125-130: create is refused only when the folder or space is missing, when the folder already has a rule, or when 10 rules exist. InMemoryRoutines.kt:231-241 has no readOnly check. Files.kt:353-361: SharedFolder.readOnly defaults to false (it is 'the owner's switch: no agent may change anything in it'). OperatorTools.kt:55 and OperatorCapabilities.kt:18: folders.write is grantable per folder. AgentProfile.kt:92: MemoryPolicy.writableSpaces is the per-space write right that the import skips. Demo /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/sec-w1/kt/Demo.kt (compiled against the merged core and testkit classes) prints 'pick-up rule on a folder agents may write -> Done(value=PickUpRule(... folderId=fld_exports, spaceId=spc_team, enabled=true ...))' for SharedFolder(readOnly = false).

**fix:** In the contract and in OwnerPickUpRulesContract: create, and setEnabled(true), are refused unless SharedFolder.readOnly is true (plain sentence: 'Make this folder read-only for agents first'). The runner checks readOnly again at every run and skips the run when it no longer holds. Imported memories get Provenance(origin = IMPORT, source = "Picked up from <folder>") and are always framed as recalled data, never as OWNER. Add a contract test: a writable folder is refused, and a folder made writable later imports nothing.

## F2. [major] Backup format v1 leaves open which settings and Appendix A data a restore brings back

**where:** scratchpad/phase6-contracts.md section 4 'Body' (manifest record with "settings":{…}) and 'Never in the file'; core/src/main/kotlin/com/aicontrolcenter/core/backup/Backup.kt:23-34 (BackupCounts); core/src/main/kotlin/com/aicontrolcenter/core/settings/FrameworkSettings.kt:63-74

**problem:** The frozen format carries a settings object but lists none of its fields. Rule 8's merge covers only local-only, the two operator switches, auto-resume and 'limits'. It says nothing about the Phase 6 master switches. routinesEnabled and folderPickUpEnabled must be off after installing (Appendix A, 'master switch off after installing'), and agentQuestionsEnabled is forced off if C1 is switched off. Read literally ('other limits keep current values': these are switches, not limits), a restore could take them from the file and turn unattended work or questions back on. That relaxes protection on restore. The format also does not say that routines, routine runs, pick-up rules and their imports stay out of v1, although WP1b asked for exactly that decision. File limits are not clamped to the Settings ranges before stricter(), so a file value of 0 or below range would be stored (with 0, every model call is refused).

**evidence:** FrameworkSettings.kt:71-74: routinesEnabled and folderPickUpEnabled, 'off after installing'. FrameworkSettings.kt:64: agentQuestionsEnabled, 'forced off if C1 is switched off'. phase6-contracts.md section 4 names only the record types agent, provider, model, modelPrice, aiApp, space, memory, memoryLink, conversation and entry, and does not list routines or pick-up under 'Never in the file'. handoff-wp1b.md section 2, last row: 'Routines and pick-up rules (Q1, Appendix A) are not in format v1: decide at the format review.' Usage.kt:522-525: DailyLimits.stricter takes the minimum, and Usage.kt:560-562 gives the Settings ranges (CALLS_RANGE 1..100,000) but nothing applies them to file values.

**fix:** Before the freeze, write an allow-list into the format and into a pure core function, RestoreSettings.merge(current, file), with tests. localOnlyMode = current OR file. Daily limits: the file's values clamped into the Settings ranges, then the stricter of current and file. directorAgentId only if that agent is in the file, else null. Seed bookkeeping from the file. calmMotion and notifyWhenWorkEnds may come from the file. Every other field keeps its current value. routinesEnabled, folderPickUpEnabled, operatorEnabled, accessibilityOperatorEnabled and autoResumeInterruptedTasks are forced off, and agentQuestionsEnabled keeps its current value. Add routines, routine runs, pick-up rules and pick-up imports to 'Never in the file' for v1. The inspect screen lists each forced change.

## F3. [major] K15 puts agent-facing re-checks and the owner-only key check on the owner-capable ProviderManager, which wave 2 is wired to give to agent-facing code

**where:** core/src/main/kotlin/com/aicontrolcenter/core/llm/ProviderManager.kt:56-116; app/src/main/java/com/aicontrolcenter/app/di/wiring/TeamWiring.kt:43,58; app/src/main/java/com/aicontrolcenter/app/di/wiring/OperatorWiring.kt:72,82; both OperatorDependencyTest ownerOnly lists

**problem:** WP7's orchestrator and chat need recheckIfStale (D28), and WP2 needs timedWaits. K15 adds both to ProviderManager, and TeamAssembly already takes providerManager for that purpose. ProviderManager can also add, repoint and remove providers and replace their API keys (addProvider, updateProvider, removeProvider). On the same interface is matchesStoredKey, which the plan says is 'owner path only' (WP4 item 6) because it answers whether any text equals a stored vault key. The orchestrator and chat are agent-facing: the team tools reach the orchestrator. Neither architecture test lists ProviderManager or SecretVault, and none of these method names is an owner-only action. So when wave 2 uses the parameter as documented, agent-facing code will hold an owner-capable provider service and a vault oracle, and no test will fail. Nothing is reachable yet at 8ac755d: the parameters are unused.

**evidence:** ProviderManager.kt:62-70: owner mutators. ProviderManager.kt:106, :109, :116: recheckIfStale, timedWaits, matchesStoredKey (KDoc: 'For the owner's side only'). TeamWiring.kt:43: '@param providerManager WP7: ... re-checked before the work waits (D28)'. Plan line 1145: 'matchesStoredKey reach WP7 and WP5 only through K11/K15'. The app test's ownerOnly at OperatorDependencyTest.kt:67-82 and the framework test's ownerOnly at OperatorDependencyTest.kt:46-53 plus PHASE6_OWNER_ONLY do not contain ProviderManager or SecretVault.

**fix:** Split the interface. An agent-side ProviderRechecks { recheckIfStale; timedWaits } goes to TeamAssembly and chat. matchesStoredKey moves to an owner-only port (for example OwnerKeyCheck) held only by OwnerServices.answer. Add ProviderManager (and SecretVault) to both tests' owner-only lists, or add addProvider, updateProvider, removeProvider and matchesStoredKey to ownerOnlyActions, so a wave-2 wiring mistake fails the build.

## F4. [minor] The agent-facing UsageLedger can erase today's usage, which the daily limits are seeded from

**where:** core/src/main/kotlin/com/aicontrolcenter/core/usage/Usage.kt:299-330 (pruneHours, pruneOrphanedWork on UsageLedger); data/src/main/java/com/aicontrolcenter/data/repository/RoomUsage.kt:67; app/src/main/java/com/aicontrolcenter/app/di/Ports.kt:101-105 vs data/.../DataLayer.kt:150-153

**problem:** Ports says the gateway (agent-facing) may hold the ledger. The ledger offers pruneHours(beforeMs) with no lower bound: 'never called with a time inside today' is a KDoc promise only, and Room does not clamp it. Code an agent reaches can therefore delete today's rows. After a restart the WP4 limiter is seeded from usageReports.today(), so this would reset the daily limit (rule 6: clearing never clears today, S29). The two KDocs also contradict each other: DataLayer says the ledger is 'Never handed to anything an agent reaches', and Ports says the gateway holds it.

**evidence:** RoomUsage.kt:67: override suspend fun pruneHours(beforeMs: Long): Int = withContext(dispatcher) { dao.deleteHoursBefore(beforeMs) }. Demo /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/sec-w1/kt/Demo.kt prints 'calls today before pruneHours: 3', 'UsageLedger.pruneHours(now + 1 h) removed rows: 1', 'calls today after pruneHours: 0'. Neither architecture test has pruneHours among its owner actions.

**fix:** Move pruneHours and pruneOrphanedWork to a housekeeping interface (for example UsageHousekeeping) held only by UpkeepGraph. In Room, clamp beforeMs to at most the start of today's UsageRange.day. Make the DataLayer and Ports KDocs agree.

## F5. [minor] Backup format has no key check: a wrong passphrase cannot be told from damage, and GCM is not key-committing

**where:** scratchpad/phase6-contracts.md section 4 (Header hash, Key); core/src/main/kotlin/com/aicontrolcenter/core/backup/Backup.kt:250-272

**problem:** The only proof that the passphrase is right is chunk 0's GCM tag. A flipped bit in the header (salt, iterations, or any byte under H) or in chunk 0 fails the same way as a wrong passphrase, so the owner is told 'The passphrase does not open this file' for a damaged file. WP3's planned test 'one flipped bit in the header → Damaged' cannot pass. AES-GCM is also not key-committing, which leaves room for multi-key ciphertexts (partitioning-oracle style) in password-based use. The practical risk here is small, because the owner is the only oracle.

**evidence:** Section 4: 'AAD of chunk i = H · i · flags' and 'Key = PBKDF2-HMAC-SHA256(...)'. The header holds no value derived from the key. Plan WP3 tests: 'one flipped bit in the header and in each chunk' alongside 'wrong passphrase'.

**fix:** Split the PBKDF2 output with HKDF-SHA256 into K_enc and K_check. Write a 32-byte check = HMAC-SHA256(K_check, "aicc-backup v1 key check" || H) right after the header, and verify it in constant time before chunk 0. A failed check gives WrongPassphrase; a passed check followed by any chunk failure gives Damaged. This also makes the format key-committing and authenticates the header before anything else in it is used.

## F6. [minor] Passphrase canonical form is not Unicode-stable, and the KDF input bytes depend on the crypto provider

**where:** core/src/main/kotlin/com/aicontrolcenter/core/backup/Backup.kt:149-160 (Passphrases.normalise); phase6-contracts.md section 4 'Key'

**problem:** normalise() upper-cases and removes dashes and spaces, but applies no Unicode normalisation. An own passphrase with an accented letter typed through a different keyboard on the new phone (precomposed vs decomposed) gives a different key, and the backup cannot be opened. The key is defined over 'UTF-8 bytes of normalise()'. If WP3 uses PBEKeySpec(char[]), the character-to-byte conversion is the provider's: SunJCE on the JVM, Android's bundled provider on the phone. The RFC 7914 vectors the plan requires are ASCII only, so they cannot catch a mismatch. A crafted header may also ask for 10,000,000 iterations, and one JCE PBKDF2 call cannot be cancelled. On this JVM 2,000,000 iterations take 615 ms; Android's provider is likely much slower (not measured).

**evidence:** Probe /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/sec-w1/probe/Probe.java prints 'normalise(precomposed e-acute) == normalise(decomposed) ? false'. Probe probe/Kdf.java prints 'NFC vs NFD ... give the same key: false', '600,000 iterations: 285 ms', '2,000,000 iterations: 615 ms'. Backup.kt:258-259: MAX_ITERATIONS = 10_000_000.

**fix:** Apply java.text.Normalizer NFKC before upper-casing (or restrict own passphrases to printable ASCII plus letters). Compute PBKDF2 over the exact UTF-8 bytes with a small Mac("HmacSHA256") loop that checks for coroutine cancellation, rather than PBEKeySpec. Add a non-ASCII vector test on the JVM and on the device. Lower MAX_ITERATIONS (for example to 2,000,000) or show progress with Cancel.

## F7. [minor] The own-passphrase check accepts trivially guessable passphrases

**where:** core/src/main/kotlin/com/aicontrolcenter/core/backup/Backup.kt:166-186 (Passphrases.check)

**problem:** Four words of three or more characters pass with no entropy floor: repeated words, keyboard walks and single-letter blocks all pass. These protect a file that is made to leave the phone. Separately, isGeneratedForm() is public. If the save flow ever used it to skip check(), an owner-typed '0000-0000-0000-0000-0000' would be accepted as if it had been generated.

**evidence:** Probe /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/sec-w1/probe/Probe.java returns null (accepted) for 'abc abd abe abf', 'aaaa bbbb cccc dddd eeee', 'the cat sat mat', 'dog dog dog cat' and 'qwerty asdf zxcv 1234'.

**fix:** Require at least 4 distinct words of 4 or more letters, or an estimated strength of at least 60 bits (character-class estimate with penalties for repeats and keyboard walks), and add keyboard walks to the common list. In the contract, state that save accepts either the exact string the app generated and showed, or check(own) == null, and never isGeneratedForm alone.

## F8. [minor] Rule 32 answer checks are easy to step around

**where:** core/src/main/kotlin/com/aicontrolcenter/core/operator/AnswerCheck.kt:32-77

**problem:** The CODE check misses digits separated by dots, commas, underscores or written as words. It also misses any question that avoids the listed words (for example 'the six numbers your bank sent'). The KEY check matches prefixes case-sensitively and does not know common key shapes. A prompt-injected agent can ask for an SMS code in a form that is not caught. The after-answer APPROVAL (rule 32, second part) is the remaining guard.

**evidence:** Probe output (sec-w1/probe/Probe.java). For 'Please type the verification code...', the answers '4.8.2.9.1.3', '4,8,2,9,1,3', '4_8_2_9_1_3' and 'four eight two nine one three' give NONE. For 'What are the six numbers in the message your bank sent?', '482913' gives NONE. looksLikeKey is false for 'my key is XAI-ABCDEFGHIJKLMNOP', 'sk_live_abcdefghijklmnop12345', 'AKIAIOSFODNN7EXAMPLE' and 'glpat-abcdefghij1234567890'.

**fix:** Remove separators ([\s.,_\-]) before the 4-8 digit check, and treat a bare 4-8 digit answer as CODE whatever the question says (the owner's second tap still sends it). Match prefixes case-insensitively and add sk_live_, rk_live_, AKIA/ASIA, glpat-, xoxb-/xoxp- and hf_.

## F9. [minor] Suggested answers can still read like approvals

**where:** core/src/main/kotlin/com/aicontrolcenter/core/operator/AnswerCheck.kt:92-115 (QuestionRules.approvalWordIn)

**problem:** Rule 13 wants questions never to look like approvals. Only allow, approve, permit, grant, always, once, deny and 'yes do it' are refused, so options such as 'Confirm the purchase', 'Yes, go ahead' or 'Sure, proceed and pay' pass. An answer is only text (rules 2 and 33 hold), so nothing is approved, but the owner can be misled into believing they confirmed a payment or a deletion.

**evidence:** Probe output: approvalWordIn returns null for 'Yes, go ahead', 'Sure, proceed and pay', 'OK' and 'Confirm the purchase'.

**fix:** Add confirm, proceed, go ahead, ok, accept, pay, buy, purchase, install, send, delete and erase (with their forms), or drop agent-suggested options and show the question text only.

## F10. [minor] The app's runtime architecture walk does not cover every agent-facing object, nor the production-only wrappers

**where:** app/src/test/java/com/aicontrolcenter/app/di/OperatorDependencyTest.kt:188-190 (roots); app/src/main/java/com/aicontrolcenter/app/di/wiring/TeamWiring.kt:92-106; data/src/main/java/com/aicontrolcenter/data/DataLayer.kt (ReadOnlyModelPrices)

**problem:** Rule 3 asks both architecture tests to add DefaultModelGateway, DefaultAgentChat, DefaultRegistryDirectory and DefaultToolAuthorizer as roots. Only the framework test does, and it checks declared types only. The app test walks runtime values from the executor, the broker and the tools. DefaultAgentChat is not reachable from those, so what it really holds is never walked, and WP7 is about to give it providerManager and maintenanceGate. The walk also runs over TestApp's narrow views (UsageLedgerView, ModelPricesView). Production uses other classes (RoomModelPrices implements both ModelPrices and OwnerModelPrices, wrapped in DataLayer.ReadOnlyModelPrices), so passing data.ownerModelPrices or data.ownerUsage to an agent-facing holder in ProductionPorts would go unnoticed.

**evidence:** roots() = listOf(toolExecutor, permissionBroker) + toolRegistry.all() (OperatorDependencyTest.kt:188-190). TeamWiring.kt:92-106 builds DefaultAgentChat privately; no tool holds it. WP1d plan step 9 deferred the roots to WP8, although these objects exist now.

**fix:** Add app.graph.agentChat, app.graph.modelGateway and app.graph.registryDirectory to roots() now (WP8 adds the wave-2 roots). Add 'answer' (taking a RequestId) to ownerOnlyActions. Add one Robolectric test that walks a graph built by createProductionPorts over an in-memory DataLayer (DatabaseEncryption.None), so production wiring is checked as well as TestApp's.

## F11. [minor] Upkeep and pick-up steps receive the whole DataLayer and a Context, so rule 1 rests on discipline, not on types

**where:** app/src/main/java/com/aicontrolcenter/app/di/wiring/RoutinesWiring.kt:75-79; app/src/main/java/com/aicontrolcenter/app/work/UpkeepGraph.kt:113-119; app/src/main/java/com/aicontrolcenter/app/AiControlCenterApp.kt:109-111

**problem:** Folder pick-up runs inside the upkeep job while nobody watches. Its step factory gets the full DataLayer: settings writer, secret vault, grants and owner requests. It also gets a Context, from which (context.applicationContext as AiControlCenterApp).graph builds the whole agent graph. WP1d's hand-off says the rule-1 reflection walk must treat Context fields as leaves, so the walk cannot see that path.

**evidence:** RoutinesWiring.upkeepSteps(context: Context, data: DataLayer, memory: MemoryService). handoff-wp1d.md WP2 note: 'the walk must treat android.content.Context-typed fields as leaves (or it reaches AppGraph through any context)'.

**fix:** Give upkeep steps narrow parameters: the PickUpRuleRepository, the shared MemoryService, a read-only folder reader, a clock and the gate. Add a source scan, as rule 11 has for app/scripts, that fails if app/work/Upkeep*, the pick-up runner or app/routines/* name AiControlCenterApp, graph or graphIfBuilt (receivers may only signal).

## F12. [minor] Routines: no way for agent-side code to learn the per-routine screen-actions choice; 'Tap to start' starts work from a notification

**where:** core/src/main/kotlin/com/aicontrolcenter/core/tasks/Tasks.kt:103-115 (ObjectiveOptions); core/src/main/kotlin/com/aicontrolcenter/core/routines/Routines.kt:132-133; app/src/main/AndroidManifest.xml:118-126

**problem:** Appendix A says 'screen actions refused unless ticked per routine'. The started objective carries only routineId, and RoutineRepository is owner-only in both tests. So the broker and the screen tools cannot learn allowScreenActions without breaking the architecture test, and WP17 may simply not enforce the limit. Separately, the manifest routes 'Tap to start' from a notification to RoutineAlarmReceiver, which would start an objective straight from a notification tap, possibly on the lock screen. Rule 4 allows only Pause and Stop as notification actions.

**evidence:** ObjectiveOptions has reviewResults, saveKnowledge, maxTasks and routineId only. Manifest comment at AndroidManifest.xml:119-121: 'receives a routine's alarm and the owner's "Tap to start" from the app's own notification'.

**fix:** Copy screenActionsAllowed (and the approval time-out) into ObjectiveOptions when a routine starts an objective (default true for objectives the owner starts by hand). The broker refuses screen tools for objectives where it is false. Make 'Tap to start' open the app on a confirmation screen, or mark the action setAuthenticationRequired(true) on API 31+. It must never start work from a lock-screen tap.

## F13. [minor] Backup save: provider header values and AI-app identity data are copied as stored; the backup port gets the vault and grant stores

**where:** framework/src/main/kotlin/com/aicontrolcenter/framework/llm/ProviderDraftValidator.kt:28-29,131; core/src/main/kotlin/com/aicontrolcenter/core/registry/AiApp.kt:34,45-48; app/src/main/java/com/aicontrolcenter/app/di/wiring/BackupWiring.kt (production(context, data: DataLayer, gate))

**problem:** Header names are screened, header values are not. A credential under a name the fragment list misses ('X-Token', 'Private-Token', 'X-Access-Key', 'X-Auth') is stored in plain form and would be written into the backup (rule 8: no API keys in the file). AI-app records carry this phone's signingCertSha256, firstInstallTime, verifiedByUser and accessibilityAuthorized; restored on another phone they describe an install that is not there. Task-scope memories belong to objectives that are not in the file. The format also does not say to drop them. WP3 is handed the whole DataLayer (secretVault, permissions, ownerRequests), so the exclusions of rule 8 depend only on WP3's code and tests.

**evidence:** ProviderDraftValidator.kt:28-29: CREDENTIAL_FRAGMENTS = api-key, apikey, api_key, authorization, auth-token, access-token, secret, password, credential, cookie. Core/registry/Memory.kt:30-33: MemoryScope.Task.

**fix:** On save, drop any header value that AnswerCheck.looksLikeKey or matchesStoredKey flags (or keep header values only for an allow-list such as HTTP-Referer and X-Title). Drop AiApp signingCertSha256, firstInstallTime, verifiedByUser and accessibilityAuthorized, and drop Task-scope memories. On restore, set provider locality to max(file, Locality.infer). Build RoomOwnerBackup from only the stores it exports, and add a test that its fields include no SecretVault, PermissionRepository, OwnerRequestRepository, SharedFolderRepository or OperatorAppRepository.

## F14. [minor] Literal address policy lets IPv4-compatible and 6to4 IPv6 forms of loopback through

**where:** core/src/main/kotlin/com/aicontrolcenter/core/operator/WebReader.kt (WebAddressPolicy.literalRefusal, K6)

**problem:** K6 is meant to refuse every literal form of a local address before authorisation. The deprecated IPv4-compatible form ::a.b.c.d and the 6to4 form 2002:AABB:CCDD:: of 127.0.0.1 are allowed. Whether a phone routes either form to loopback was not verified; the risk is low.

**evidence:** Probe /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/sec-w1/probe/Web.java prints '[::127.0.0.1] -> null' and '[2002:7f00:1::] -> null'. Mapped and NAT64 forms are refused ('[::ffff:127.0.0.1]', '[64:ff9b::7f00:1]').

**fix:** Refuse ::/96 (IPv4-compatible) and 2002::/16 literals whose embedded IPv4 address refusal() refuses, in literalRefusal and in refusal(InetAddress).

## Backup format verdict
Sound in its cryptographic core. Freeze only after the major gap (finding 2) and two minor format changes below are made.

What is right:
- Key derivation: PBKDF2-HMAC-SHA256 at 600,000 iterations (the current OWASP figure for this KDF), with a 16-byte random salt per file and a fresh key per file. The iteration count is read from the header and bounded to 100,000..10,000,000 before any work, and the header is authenticated, so it cannot be lowered.
- Encryption: AES-256-GCM in 1 MiB chunks, each with a 128-bit tag and a fresh random 96-bit nonce. With a per-file key and at most 1,024 chunks per file, nonces do not collide in practice.
- Header and chunk binding: the AAD of every chunk is SHA-256(magic..end of header) followed by the chunk number (u64) and the flags byte. Any change to the header (salt, iterations, versions), a reordered, repeated or dropped chunk, a moved last flag, a cut after any chunk, and bytes after the last chunk all fail. Length fields and nonces lie outside the AAD but cannot be changed without failing the tag.
- Versioning: magic, a u16 version, and a header whose format and version must agree. A version above 1 gives NewerFormat.
- Parsing limits: header at most 4 KiB, records at most 8 MiB with a manifest record first and an end record (with the count) last. No compression, so no decompression bomb. Bounds of 1 GB, 500 agents and 200,000 memories.
- Contents: the include/exclude lists match rule 8 (no keys or aliases, database key, grants, owner requests, authorised screen apps, folder grants, activity log, usage, tool calls or results, objectives). The passphrase is generated by default (100 bits, Crockford base32 from SecureRandom).

Required before the freeze:
1. A settings allow-list with forced values, and routines, routine runs, pick-up rules and pick-up imports explicitly out of v1 (finding 2).
2. A key-check value derived with HKDF from the PBKDF2 output (an HMAC over the header hash), verified before chunk 0. This tells WrongPassphrase from Damaged, makes the scheme key-committing, and authenticates the header before its other fields are used.
3. A stable canonical passphrase: NFKC normalisation before upper-casing, and PBKDF2 computed over the exact UTF-8 bytes rather than through PBEKeySpec's provider-specific char-to-byte conversion, plus a non-ASCII vector test on the device.

Recommended:
- Require chunkBytes == 1,048,576 exactly.
- Use no header field other than version, KDF and salt until the header is authenticated.
- Make the KDF cancellable, or lower its maximum.
- On save, drop credential-looking provider header values, AI-app install identity fields and Task-scope memories.
- On restore, set provider locality to max(file, Locality.infer) and apiKeyAlias to null, including when the file's provider has the same id as a built-in one whose key is in the vault.
- Build RoomOwnerBackup without the vault, grant and request stores.
- Tighten own-passphrase rules (finding 7).

I checked the format as written in phase6-contracts.md section 4 and BackupFormat. No implementation exists yet: BackupWiring.production returns null.

## Not checked
- WP3 is not built (BackupWiring.production returns null), so no real file was written, inspected or restored. Everything about the backup is a review of the written format (phase6-contracts.md section 4, BackupFormat, Passphrases), not of code. Android's PBKDF2 provider (its char-to-byte conversion and timing on a phone) was not measured; only the JVM was timed.
- Wave-2 behaviour exists only as contracts and stubs, and I reviewed it as such: the WP4 daily limiter (reserve-then-commit, retries, streaming, effectiveLocality for 'on this phone'), WP5 questions and caps, WP7 notes and gate checks, the WP17 routine runner, alarms and pick-up runner, the WP2 upkeep job, wake lock and AppGate, and the WP6 isolated script process.
- I ran no Gradle build or test: I am report-only and have no worktree. I relied on the green WP1d full-suite log (scratchpad/wp1d-fulltest2.log). My probes ran against the compiled core and testkit classes in /home/user/rune-companion/{core,testkit}/build (built 07:21; core main has no commits since the WP1a merge), with a standalone Kotlin compiler from the Gradle cache.
- Probe files are in /tmp/claude-0/-home-user/468e36cc-c9a5-57ed-8330-6c458f245700/scratchpad/sec-w1: kt/Demo.kt, probe/Probe.java, probe/Web.java, probe/Kdf.java.
- No emulator or device runs. I did not run EncryptedMigrationTest or the spike, did not run aapt2 on a signed APK, and did not check delivery of the boot or exact-alarm broadcasts or the notification lock-screen behaviour. I checked the manifest in source and in the debug merged manifest (07:59): 18 uses-permission; exported components are MainActivity, the accessibility service (guarded by BIND_ACCESSIBILITY_SERVICE), ProfileInstallReceiver (guarded by DUMP) and the debug-only PreviewActivity and ComponentActivity. All new components are exported=false; the routine receivers are disabled; the script service is isolated on :scripts. The release merged manifest was stale (04:27) and was not rebuilt.
- Phase 5 probes were not re-run. AddressGuard's resolved-address check was not exercised; only K6's literal policy was. The migration and SQLCipher path was not exercised. The merge happening in parallel was already complete when I looked: HEAD 8ac755d is identical to p6/wp1d (empty git diff).
