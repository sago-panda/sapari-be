# chat — live chat domain

Root `AGENTS.md` owns the cross-cutting rules; this file is chat-specific only.
✅ connect / send / fan-out / room-end / kick + ban escalation / history (VOD) / reverse paths —
ban release, kick-list reconcile, end-marker clear, dictionary propagation + admin API
(REST host: **live-app**). Run `./gradlew :modules:chat:chat-core:test`.

## One module, two stacks — the rule that governs everything else

`chat-core` is consumed by **streaming-app (WebFlux, no relational DB, no blocking Mongo)** and by
**live-app (MVC)**, which hosts the kick REST endpoint. Both stacks land on the classpath of both apps.

- **Reactive adapters carry `@Repository`** and are component-scanned.
- **Blocking adapters carry no stereotype at all** (`ChatKickLogRepositoryImpl`,
  `ChatKickWriteRedisRepository`, `ChatKickEventRedisPublisher`, `ChatMessageEvidenceMongoRepository`).
  Scanning them creates beans in the reactive app whose dependencies do not exist there. The host app that
  owns the stack registers them with `@Bean`. **Do not "fix" this by adding an exclude list to
  streaming-app** — every new adapter would be a place to forget.
- Auto-configuration ignores stereotypes: `DataSourceAutoConfiguration` fires on classpath presence alone
  and broke streaming-app's boot once. It is cut by `excludeName` (string, because the class is
  runtime-only there) and `AutoConfigurationExclusionTest` guards the spelling.
- **The MVC host is live-app, and that is forced, not chosen.** Any app carrying `live-core` (needed for the
  `GetLiveRoomUseCase` bean) also scans `RoomTokenConfig`, whose `@Validated` properties require live's RS256
  **private signing key** and LiveKit credentials — so any other host would have to duplicate those secrets.
  There is no inter-app HTTP anywhere in this repo to route around it.
- **The two apps exclude each other's half in opposite ways.** streaming-app scans `com.sapari.chat` and
  relies on blocking adapters having no stereotype; live-app cannot do the mirror of that (the reactive
  adapters *do* carry `@Repository`), so it drops `com.sapari.chat` from its component scan **by package
  regex** and registers the blocking beans with `@Bean`. Excluding by class would rot — a new reactive
  adapter would silently boot there, and one of them connects to Redis in its constructor.
  `ChatScanExclusionTest` guards the filter without booting a context.
- ⚠️ **The exclusion also drops `ChatMongoConfig`, which is the only place UUIDs are pinned to standard BSON
  binary.** The writing side (streaming-app) still pins it, so data stays correct and only the *reading* side
  breaks — evidence documents come back with `org.bson.types.Binary` where a UUID belongs, and there is no
  converter for that. live-app re-registers the customizer itself. Anything else that config provides has to
  be re-provided the same way.
- `mongodb-driver-sync` is **testImplementation only** — the blocking adapter compiles against
  `MongoTemplate` without it, and promoting it would hand the reactive app a sync driver.

ArchUnit enforces the calling direction: streaming-app must not touch blocking Redis/Mongo templates or
blocking chat use cases; live-app (MVC) must not touch reactive ones. **Rule ② matches class names by regex** —
name a service something other than `KickUserService` and it silently guards nothing.

## Failure policy — the moderation reads open, the rate limit degrades

Every moderation read (kick, ban, room-ended) is **fail-open**. The rate limit is **not** one of them
any more: it falls back to a pod-local window of the same length, so a Redis **outage — or a round trip
slower than 200ms** — no longer removes the cap. The latency half matters: dropping packets instead of
dying keeps the command pending to Lettuce's 60s default, and without the bound the fallback never fires.
It also means degradation is no longer a rare event you can leave to a throttled `log.error`; it fires on
latency, so "what fraction of the time was enforcement pod-local" wants a metric, not a log line.
There is still no fail-closed path in chat.

| Read | On failure | Loses |
|---|---|---|
| room-ended marker (entry + send re-check) | allow | ended-room blocking |
| kick set | allow | kick enforcement |
| ban key | allow | ban enforcement |
| rate limit | **degrade** (pod-local 3s window) | cross-pod accuracy (≈ N pods × 1 per window) |
| session HASH register / viewer count | allow | count accuracy only |

The bet: total chat outage is worse than a kicked user slipping through. **Adapters propagate, consumers
decide** — an adapter that swallows into `false` destroys the distinction between "definitely not kicked"
and "could not ask".

**One exception, and it changes severity, not policy.** A kick key of the wrong Redis type keeps failing
forever — retry is idempotent *and* permanently futile. `KickStoreCorruptedException` splits it so the
"never heals" case is not buried in the logs of an outage that will. Detection is by message prefix
(`RedisWrongType`), because the driver uses one exception type for every server-side error; measured, not
assumed. **No self-healing DEL** — that would erase the room's whole kick list and possibly someone else's data.

Throttled logs are **interval-based, never count-based**: a count threshold silences short bursts after the
first, which is backwards. Reasons are counted separately so one does not bury another's first occurrence.
**Never let a metric or audit call throw inside a fail-open handler** — the policy inverts.

**Never hand a record to a logger.** `ChatSession`'s default `toString()` prints `email`, so one
`log.info("... {}", chatSession)` leaks PII that every call site is currently careful to avoid — the 12
production log statements all pass `sessionId`/`roomId`/`userId`/`role` scalars. The same holds for any
record that carries `email` or an unmasked body. Absorbing a failure is also not the same as silencing
it: when a handler swallows to keep a verdict, it still owes a counter and an interval-throttled log
(`RedisRateLimiter.denied` is the worked example — it kept the verdict and lost the signal until that was
put back). ⚠️ **A previous version of this paragraph claimed a counter cannot live in `application/`.
That was false** — rule 16 (`domain_and_application_must_not_depend_on_metrics_library`) bans the
*library* (`io.micrometer..`), and its own javadoc says to put a port there instead. chat already has
that port and injects it in `application/`: `application/port/ChatMetrics` →
`application/service/SendChatService`, `application/handler/ChatBroadcastSubscriber`. Do not skip
application-layer observation on the strength of that rule.

What is true is narrower. `ChatMetrics` is deliberately written in **domain-event** vocabulary, not
`counter`/`gauge`, so adding a signal means adding a method to that port — not injecting a
`MeterRegistry`. And one swallow in this module does *not* carry a counter on purpose:
`ManageFilterWordsService.warnIfCommittedSourceViolates` swallows a **diagnostic** read that runs after
the write and the publish have both succeeded, and the state it would have reported is caught by the
next write's `guardSource` as a 409 — an externally visible, structural signal. That is the argument for
leaving it out; "the layer cannot hold one" is not.

## Profanity dictionary — the table is the source, the file seeds it, and short words are the trap

Before matching, the filter folds the message to **NFC** and folds case plus fullwidth Latin/digits.
Without that, input that looks identical on screen walks past the whole dictionary — a decomposed
`개새끼` and a fullwidth `ｆｕｃｋ` both did. The **output** plane stops at NFC on purpose: NFKC would
rewrite the seller's own text (`㈜사파리` → `(주)사파리`, `Ⅲ단계` → `III단계`) and that text is what gets
persisted. The 2A/2B **detection** planes go further — they expand compatibility forms per code point —
because nothing there is sliced or returned; they only answer "does a profanity appear once this is
folded". Do not extend "we stop at NFC" to them: collapsing the two is exactly how 829 cases were
traded for 22 in one commit.

`fold` also maps **jamo written in another encoding** back to the compatibility jamo the dictionary
uses — conjoining (U+1100–11FF) and halfwidth (U+FFA1–FFDC). This has to happen in the
length-preserving 1-pass fold, not in the 2A plane, because 2A strips non-letters and that breaks
entries with a digit or symbol inside them (`ㄴ1ㄱ`, `@ㅐ미`). The reverse table is derived from the
UCD, not hardcoded, but `NFKD` alone yields **choseong forms only** — Unicode does not link a
jongseong to its compatibility jamo — so the table is filled a second time by character name
(`HANGUL JONGSEONG PIEUP` ↔ `HANGUL LETTER PIEUP`). Measured: without that second pass, 72 of the
73 jamo entries pass when written with jongseong forms.

⚠️ **Evasion axes that are open, by decision.** The `ProfanityFilter` class javadoc held these alone,
which meant the module's own guide read as if the filter were closed. They are declared, not fixed:

- **Cyrillic / Latin homoglyphs** — `fold` normalises case, fullwidth and jamo encoding, not confusable
  scripts. Closing it means pulling in the Unicode confusables table, a data dependency.
- **Jamo spelled out** — writing an entry's syllables as separate **compatibility** jamo evades it:
  measured 2026-11-04, **849 of the 1,024** entries that contain a Hangul syllable pass. Build the vector
  with compatibility jamo (U+3131–318E); with conjoining jamo the entry NFC recomposes it and the
  measurement reads 0. Seven all-conjoining forms are a separate sub-case, where the entry NFC recomposes into
  a syllable (`ㅅㅣㅂㅏ` → `시바`) that the dictionary deliberately does not contain, because those
  syllables are ordinary Korean (`시바견`, `보지 못했다`, `김씨`).
- **Digit homoglyphs** — subscript and circled digits are not folded, so `ㅅ₁` and `s₁bal` pass while
  `ㅅ1` and `s1bal` are caught. Same decision as the confusables axis.
- **Halfwidth and jongseong are two axes with one name.** Writing a *jamo-spelled* entry in another
  encoding is closed (83 and 73 entries, 0 pass); spreading a *syllable-spelled* entry into halfwidth
  jamo is the spread-out axis above (1,024 entries, 16 pass — all 16 already contain a digit or symbol,
  so they are inside the leet class). The construction, not the number, is the definition: state it
  wherever you record a figure for this, and always run the control (0 unmasked originals; a denominator
  of 0 means the vectors were never built). Four different numbers have been recorded under this one
  name — see the table in `ProfanityFilter`'s class javadoc.
- **An exhaustive 2- and 3-syllable sweep** needs a Korean word list, which this repo does not have.
  The two-way split sweep (below) bounds the 2-syllable class structurally; the 3-syllable class is not
  bounded.
- **2B worst-case cost** scales super-linearly with the longest entry (`K`) — measured `K` 7→64 is
  9.1x but the cost is 19.7x. `MAX_WORD_LENGTH` caps `K` at **16** (SPR-165 lowered it from 64 once
  the dictionary became runtime-mutable); the full table is at that constant, and the `maxWindow`
  field and the 2B loop point at it. The cap replaced the work-budget fallback that used to be
  prescribed in the 2B comment — do not re-add it.

Numbers and vectors for each live in `ProfanityFilter`'s class javadoc and the two data headers; this
list exists so the axes are visible from here.

All three canonical tiers now exist — `live_schema.chat_filter_word` → Redis snapshot → in-pod trie
(SPR-165). ⚠️ `db/migration/live/V1__init_live.sql:157` calls tier 2 "Redis Pub/Sub(변경 전파)", which
is imprecise: the content travels in a **snapshot key**, and pub/sub only carries the new version
number (content in a non-persistent channel would leave a pod that missed the message enforcing a
different dictionary forever). That file is a **frozen migration** — it is on `origin/dev`, so editing
even its comments changes its checksum and stops the deploy (`infra/AGENTS.md`). This paragraph is the
correction; do not go fix the comment.

The split is forced by the apps: streaming-app has **no DataSource at all**, so the app that
can read the source of truth (live-app) reads it and leaves a snapshot where the app that cannot
(streaming-app) will find it.

- **live-app writes.** `ChatDictionaryBootstrap` seeds the table from the bundled `.txt` **only when it
  is empty** — re-pushing every boot would resurrect words an operator deleted, which is the same as
  `remove` not working. After the seed, the table is the author and the files are just its origin.
  Then it publishes, on every boot: idempotent, and it makes "snapshot missing or stale" self-clear with
  one deploy.
- **The snapshot is one key.** `chat:dict:snapshot` holds version + both sets in one value, so a reader
  cannot see "half new" — a whitelist entry only means something next to the profanity it covers.
  The version bump + `SET` + `PUBLISH` are **one Lua script**; split them and you get "version
  advanced, body did not", after which the receiver's poll reads the old body and advances its version
  anyway. The bump is `current + 1` under a CAS, not an `INCR` — see the next bullet.
- **streaming-app reads.** `ProfanityDictionaryRefreshHandler` merges three triggers (boot, notify,
  poll) into one `concatMap` stream, so only one refresh runs at a time and monotonicity is a field
  comparison. ⚠️ The **poll** is what makes propagation work, not the notification: Redis pub/sub is
  non-persistent, so a pod whose subscription was down during a publish never gets that message and
  would enforce the old dictionary until restart. The notification only saves the poll interval.
- **Failure directions are deliberate.** A transport failure does **not** advance the local version
  (otherwise the poll sees "same" and never retries — that ordering is the load-bearing part of the
  self-heal, pinned by a revert test). A **content** refusal does advance it, because re-reading the
  same snapshot refuses for the same reason and would ERROR every minute on every pod; a fix is a new
  publish, so the version rises and it is picked up.
- **The last gate is the swap point.** A propagated dictionary goes through the *same* door as the
  bundled and config ones (`ProfanityFilterFactory` → `ProfanityDictionary.of`), K budget included.
  Rejection means **keep the previous filter** — not boot failure: one ADMIN request must not CrashLoop
  the fleet. The write path pre-checks so this state is not reached normally; this gate catches writes
  that bypassed it (direct SQL or Redis).
- **The operator surface is `/api/v1/chat/filter-words`** (GET / POST / DELETE, ADMIN, live-app).
  GET is ADMIN too, deliberately: the list is "everything the platform blocks", which tells an evader
  which spellings are *absent* — and without a read, delete is unusable. A single
  `requestMatchers("/api/v1/chat/filter-words/**")` covers the bare path as well (measured: PathPattern
  and AntPathMatcher both match it; pinned by `ChatFilterWordAuthorizationTest`, which fails on all
  three methods if that line goes).
- ⚠️ **The write path validates the resulting *whole set*, not the submitted word** — otherwise one
  pre-existing violating row freezes propagation: the write succeeds, every pod refuses the swap on that
  old row, and the operator sees their word in the list while no pod enforces it (same dead-letter shape
  as `SEC-11`). The two reachable causes are direct SQL and **tightening a rule later** — exactly what
  lowering the K budget 64 → 16 was; the table was empty then, so it cost nothing, but the next
  tightening will meet a populated one.
- ⚠️ **What is stored is what is enforced.** The door judges `strip`+NFC of an entry, so the write
  must store that same value — writing the raw input gave two outcomes from one request: `"시발\n"`
  passed the gate (200) and left a newline row that broke the codec's self-check, **freezing every
  later publish fleet-wide** (and `guardSource` uses the same door, so it could never detect it);
  `"개새끼 "` made `DELETE ?word=개새끼` match 0 rows and answer `changed=false` while the word was
  still being enforced. The plane is exposed as `ProfanityFilterFactory.normalize` precisely so the
  checking side and the storing side cannot drift.
- **Publishing is conditional, not an unconditional `INCR`.** The body is read *outside* the script,
  so an unconditional bump let a later publisher win the version while carrying an older body — the
  word stayed in the table (visible in the admin list) and absent from the fleet until the next write
  or boot, which a rolling deploy reaches. The script now advances only if the version is still the
  one the publisher took, and a lost race **re-reads the source** before retrying (re-sending the same
  body would reinstate the very bug). A failed attempt writes nothing, so it cannot burn numbers and
  break the version-key/snapshot invariant.
- **The receiver applies a *regressed* version too, loudly** — but an *equal* one it drops in
  silence. The `<` branch logs and swaps; the `==` branch (and the upstream
  `filter(published != processedVersion)`, which sits *above* the body read) returns without a line.
  That asymmetry matters because the publisher **can restart at v1**: see the next bullet. Do not read
  "applies a regressed version loudly" as covering the equal case. A strict `<=` guard used to drop the
  regressed case too, which is reachable (FLUSHDB, old RDB restore, stale replica failover) and left
  that pod diverged from the fleet forever. Refreshes are serialized by `concatMap`, so monotonicity
  was defensive and its price was a silent branch. "Version present, body absent" likewise used to be
  the one fully silent branch — `allkeys-lru` evicts TTL-less keys and the odds favour evicting the
  snapshot (tens of KB, read on change) over the counter (bytes, read every minute).
- **The version has a digit ceiling, and "can be read" is a *different axis* from "can be built on".**
  `MAX_VERSION_DIGITS` bounds exact double arithmetic in Lua (above 2^53 `tonumber` and `+1` stop being
  exact), and Lua's `..` would write `1e+14` for a 15-digit value, so the script formats with
  `string.format('%d', …)`. Two predicates, deliberately not one:
  - `readVersionToken` (**readable**; shared by reader, publisher and codec) — digits ≤ ceiling *and*
    the successor's digits ≤ ceiling.
  - `isEligibleBase` (**buildable on**; publisher only) — readable *and* the successor is readable too.

  `eligible(b)` therefore implies `readable(b+1)`, i.e. **everything the publisher writes can be read
  by the fleet** — by definition, not by luck. One axis cannot give that: a finite counter has a last
  cell, and narrowing acceptance only moves the cell. Two earlier attempts failed there (refusing →
  every dictionary write 500s forever; resetting to 1 → the same number carried two different bodies
  and pods holding 1 skipped it in silence).
- **At the boundary cell the base falls to 0 and the next publish restarts at v1 — and that restart is
  logged.** v1 *is* readable, which is what separates this from the discarded reset: the reset reused a
  number *silently*, this one says so. Reaching the cell needs one out-of-band write plus one
  eviction/`DEL`, so it is not exotic.

  Two things that paragraph used to get wrong. **The residual is not "irreducible with ephemeral
  state"** — the receiver has the decoded body in hand (`apply(snapshot, …)`), so comparing *content*
  would close it; what keeps it open is the *choice* to compare only versions. ⚠️ That choice's price
  is **not** "a trie rebuild per equal-version poll" — an earlier draft said so and it is false in both
  readings: today the upstream `filter(published != processedVersion)` sits *above* `read()`, so an
  equal-version poll ends at the version `GET` and builds **nothing**; and a content comparison would
  still only build when the content differs. The price is the **body transfer plus decode — ~11 KB per
  pod per poll** (1,078 + 38 bundled entries), which is exactly the cost the version key exists to
  avoid. Do not read "irreducible" as a reason to stop looking at that axis, and do not price the axis
  by trie builds. And **the recovery is not "delete both keys"** — that reproduces the
  deadlock (base 0 → v1 again). Measured: boundary + write → v1 (fleet at 1 skips) → `DEL` both →
  v1 again → skip again; whereas *re-sending the same ADMIN write* bases on the 1 this publish just
  wrote → v2 → the fleet applies. Adds are idempotent and publish even when nothing changed, so "send
  it once more" is the whole procedure. Verify by the receiver's `갈아 끼웠다 … version=` INFO (its
  absence for the response's `version` is the verdict) — the same check `FilterWordChange.version`'s
  javadoc already prescribes; the functional check (does the word mask?) is the backup, since there is
  no dry-run endpoint and it needs a live room.
- **The config CSV (`chat.profanity.words`) is boot-only and dies at the first swap.** It is *not*
  merged into propagated filters on purpose: merging would make the enforced set differ from the set
  live-app pre-checks, so one config word colliding with a source whitelist entry would freeze swaps
  while writes passed — the same gate-plane/runtime-plane split this ticket keeps closing. Its lifetime
  is logged at boot so it does not vanish silently; durable changes go through the ADMIN API.
- **Rule (1) crosses kinds, so two concurrent adds can each pass and still commit a violation**
  (`rejectUselessWhitelist` tests a whitelist entry against the *word* set). No lock — and the reason is
  **not** that closing it is expensive: `ChatKickRecorder.record` already shows the cheap shape (advisory
  lock as the first statement inside `REQUIRES_NEW`, Redis only after it returns), so there would be no
  external call inside the transaction. The real cost is **one more lock resource**, which
  `ChatKickRecorder` obliges you to order or prove acyclic. It is left open because the probability is
  low (same string, both kinds, simultaneously, at human typing rates), the result is recoverable
  (remove either), and a post-write re-check now logs it against the request that caused it. If any of
  those three changes — e.g. dictionary writes become automated — revisit it.
- **Rejection is attributed, and that split is load-bearing.** The service checks the *pre-change* set
  first: if that already fails, the cause is not the caller's input → `409 CHAT-011`. If the pre-change
  set is clean and the resulting one fails, the only difference is the submitted item → `400 CHAT-010`.
  Collapsing them tells an operator who sent a perfectly good word that their word is wrong.
- **Delete does not go through the door at all** — it only refuses emptying the word set. Checking the
  *result* closed the recovery path it was meant to open: with **two or more** violating rows, removing
  one is refused because of the other, and the API removes one at a time, so nothing can be removed
  (measured). And two-or-more is the *default* of that scenario, not an edge: tightening a rule — which
  is exactly what K 64 → 16 was — makes many rows violate at once. The check buys nothing there,
  because **removal cannot create a violation**: rule (1) fires on `entry ∈ words` and removal only
  shrinks `words`; rule (3) looks at the residue after stripping profanities, which only grows; shape
  and budget are per-entry. Measured exhaustively over the bundled dictionary: 1,116 single removals,
  **0** new violations (control: an empty word set *is* refused, so the 0 is not a blind probe). What
  remains refused is emptying the words — that is masking silently off. A still-violating result after
  a delete is a **warning**, not a refusal: refusing blocks recovery, staying silent lets the operator
  believe recovery finished.

Missing resource, or zero words after comments, is a **boot failure**. An empty dictionary is not a
degraded filter — it is the filter silently off (`displayMessage == originalMessage`), and that state
already shipped once. `chat.profanity.words` / `.whitelist` **add to** the bundled list; they do not
replace it, so one config line cannot wipe the dictionary.

**The trap is short entries.** `ProfanityFilter` splits on `\S+` and matches substrings, with no notion
of a word boundary — and Korean glues particles and auxiliary verbs onto stems. So any two-syllable
entry that is also an ordinary morpheme censors ordinary speech: `새끼` kills *새끼 고양이*, `세끼` kills
*하루 세끼*, `호로` kills *번호로*, `개떡` kills *쑥개떡*. A whitelist cannot rescue these — an entry only
protects a token that **contains** it, so covering a bare two-syllable word needs an entry as short as
the word itself, which then unmasks the real insult. Those four are dropped for that reason, as `보지`
and `자지` already were upstream. **Do not add a short generic entry without running the false-positive
regression**; in live commerce a censored seller description is worse than a missed insult, because the
broadcast cannot be recalled and the masked text is what gets persisted.

Masking is **span-scoped**: a profanity match is dropped only where a whitelist match *fully covers*
it. That closed the oldest hole in this feature — a whitelist hit used to preserve the entire token, so
every entry was an off switch for the whole dictionary and `시발점씨발` walked through. Measured across
all 38 entries against the whole dictionary in **both** directions (81,928 cases), exactly **one** does:
`시발유니섹스`, because the whitelist entry `시발유` covers the `시발` span. That is not a new hole — it is
the suffix class stated below. The older `0/42,627` counted one direction only. Filtering happens
**before** merging adjacent ranges; reverse that and overlapping matches re-open the hole, so do not
move `mergeRanges` earlier.

Whitelist entries obey three rules, pinned against the data by `ProfanityDictionaryTest`. An entry must
**not be a bare profanity** (`시발점`, not `시발` — the two spans would coincide exactly and unmask that
insult everywhere), and what is **left after removing the profanity must be a word worth keeping** —
one Hangul syllable, or two letters in any script
(`8시발` leaves `8`, handing an attacker a one-character prefix: `8시발` and `8시발이다` both pass.
`8시발놈아` does **not** — it masks to `8***아`, because the dictionary span for `시발놈` runs past
the whitelist span and survives. The rule stands on `8시발` alone; the earlier claim that
`8시발놈아` passes was wrong, and the reason it is wrong is the crossing-span cost noted above).
Those two are refused at load. Latin needs two letters because a single one is as cheap as `8`
(`xpussy` and `xfuck` are refused; `pussycat` loads with a warning naming its residue `cat`). Entries
whose residue has no Hangul syllable load **with a warning** rather than failing boot: judging by
Hangul alone refused every legitimate Latin entry (`passivation`, `passivate`, `unisex`, `sexual`,
`Essex`, `pussycat` — 6/6 with **both** this threshold and the no-profanity skip reverted; reverting
the threshold alone gives 1/6, because after `sex` and `siva` left the dictionary the other five
contain no profanity and the skip takes them first), and a Latin false positive during a broadcast then has no remedy but
a deploy that drops the dictionary entry; `sex` and `siva` already went that way, each time losing the
detection outright. An entry that contains **no** profanity skips this rule entirely — it frees
nothing, so the "cheap prefix" wording would be false about it, and it is reported by rule three
instead. That third rule — an entry should **contain** a profanity — is only warned
about, because with span scoping an entry that covers nothing simply does nothing. The line for failing
boot is harm, not uselessness.

⚠️ Span coverage has a cost in the other direction, and the first number written here was wrong: a
fragment that follows a protected word is masked where it sits, because the token is no longer kept
whole. The claim "0 cases on current data" came from a sweep whose 22 suffixes were **all particles,
no verb endings** — and the real false positives were verb endings (`유니섹스해요` → `유니***요`).
The same claim was then wrong a second time with a wider suffix list — 60 suffixes found 10 hits and
"no natural Korean", but the real false positives were **following nouns**: `전염병신고` → `전염***고`
(`감염병 신고` is standard public-health phrasing), `시발점물류` → `시발***류`. A suffix list is not the
population of this class, because what follows can be a particle, a verb ending or a noun.

Count it structurally instead. The figure moves whenever either list changes, so **re-measure it rather
than copying it**:

| measured | dictionary / whitelist | crossing pairs | distinct dictionary entries | results | masked |
|---|---|---|---|---|---|
| 2026-11-03 | 1,093 / 39 | 130 | 99 | 127 | 121 |
| 2026-11-04 | 1,078 / 38 | 124 | 93 | 121 | 115 |

Reading the 121 masked results by hand found 12 natural compounds, and **those twelve** are closed —
read that as *found so far*, never as the class being exhausted: the reading is by hand, there is no
Korean word list to sweep against, and a later pass found nine more (`성교`, `에비`, `에미`, `개년`,
`개똥`, `쪼다`, `개세`, `저년` were removed on 2026-11-04; `염병` is kept — `오염병`, `수염병`,
`방염병풍` are contrived, and `염병` is a high-value catch). The 6 longer
whitelist entries (`전염병신`, `감염병신`, `화염병신`, `시발택시발`, `다시팔년`, `일시불알`), which cost
nothing (measured 12,936 cases = 6 × 1,078 × 2 — 0 evasions, every insult compound still masked), and
4 dictionary removals (`섹스해`, `섹스하자`, `점물`, `육갑`). Three more whitelist entries (`환불알`,
`동봉알`, `검색키`, 6,468 cases, 0 evasions) and five more removals (`조까`, `조또`, `족까`, `젓까`,
`젖까`) closed the particle-attachment variant.

`상년도` (2026-11-04) is the one entry of this kind that closes a whole class rather than a case: every
`X상년도` compound contains it, so `예상년도`, `결산년도` and `기준년도` are covered by one entry instead
of an open-ended list (2,156 cases, 0 evasions).

⚠️ The same class also exists **with no whitelist involved**: `지점물류` → `지***류`, `교육갑니다` →
`교***니다`. A two-syllable entry lands across a syllable boundary of an ordinary compound, and no
whitelist entry can fix that (you would add `지점물`, `교육갑`, … forever) — the entry itself has to go.
Neither sweep finds this: there is no Korean word list in the repo, so it is found by reading the
crossing results (121 today — use the current value from the table above). Do not skip that reading when
adding an entry, and run a corpus pass too — a suffix sweep cannot see a plain word that matches with no
insertion at all (`검색스토리지` ← `색스` did).

⚠️ **A space is not protection, and this was the largest wrong claim in the data.** `profanity-words.txt`
asserted that "with a space the original always goes through, because tokenisation is `\S+`". It is
false: 2B joins adjacent tokens *by design* — that is how `구매 시 발송` is caught — and **1,048 of the
1,078 entries** are masked by a single space inserted anywhere inside them. The 30 that survive all
contain a digit or a symbol, which the planes strip, so their join can never equal the entry.

The real criterion is **the joined run must equal a dictionary entry exactly** (2B fires only on full
consumption). `시 발` masks; `반드시 발급` does not, because `반드시발급` is no entry (written closed it
masks as `반드***급` via the 1-pass instead). Digit tokens are not glue — they merely fail to break the
join, since plane A deletes them: `돌아 401 이`, `돌아 1 이`, `돌아 ! 이` and `돌아 이` all behave alike.

So the false-positive population is **two-way splits where each side is an independent word**, and that
*is* structurally enumerable (measured 2026-11-04): collect the 16,442 pure-Hangul tokens that actually
appear space-delimited in the repo's Korean, then for every entry test every internal split point for
both halves being in that set — 2,366 split points → 103 both-in-corpus → 35 with each side clean, all
masked. Most of the 35 are profanity written with a space (correct catches); the ones that form ordinary
sentences were closed by removing 15 entries (`성교`, `에비`, `에미`, `개년`, `개똥`, `쪼다`, `개세`,
`저년`, `띠풀`, `개가든`, `개가뜬`, `박고빼고`, `게같은`, `돌아이`, `ㅗ`) — they were censoring this
platform's main categories (`개 세 마리`, `게 같은 갑각류`, `개 똥 치우는 봉투`, `에비앙`, `에미레이트`,
`남성교복`, `3개년 계획`, `중개세무`, `돌을 쪼다`, `ㅗㅜㅑ`) and even this repo's own source comment
(`돌아 401 이 오는데`). The synthetic sweep cannot see this class at all: it builds *closed* forms, never
split pairs. Known remaining cases are stated in that file's header (`개 세끼`, `게 새끼`, `개 소리`,
`개 쓰레기`, `똥 구멍`) — those come from core insults, so the entries stay.

A fourth rule, *no entry already covered by a shorter one*, was retired. Its premise ("a longer entry
cannot change the outcome") held only while a hit preserved the whole token. Under span coverage the
longer entry covers a wider span and keeps matches the shorter cannot: with `cd` in the dictionary and
`abc` whitelisted, `abcd` masks to `ab***`; add `abcd` and it stays whole. A duplicate-looking entry is
a different protection, not dead weight.

There is no exception to the rules. English `sexual`/`sexy` used to be one, and those two were the key
that let `sexyfuck` and `sexy시발` through on an ASCII keyboard. Dropping `sex` removed their coverage
and the exception with it. `sex` was not the only ASCII entry that is a substring of ordinary English,
as that change claimed — `siva` was another (`passivation`), found a round later. Measure the class, do
not trust the count.

Span scoping has a cost in the other direction: a token is no longer preserved wholesale, so a fragment
*after* a protected word can be masked. That cost is measured above — do not re-derive it from the
suffix sweep in `ProfanityDictionaryTest`, which is **particles only** and is the sample that produced
two wrong "zero cases" claims in this ticket.

A second cost runs the other way, and the mechanism — not the count — is what to carry. Preservation
keys on *the token **containing** the entry*, not on the token starting with it, so a tail unmasks both
classes alike (measured 2026-11-04, four tails `ㅋㅋ` / `!!` / `123` / `" 짜증나"`):

- **16 entries begin with the profanity they cover** (`시발점`, `시발유`, `지랄탄`, `상년도`, …) → 64/64 pass
- **22 entries merely contain it** (`전염병`, `일시불`, `유니섹스`, `다시팔`, …) → 88/88 pass

Most of the 22 are the *intended* protection — `전염병ㅋㅋ` going through whole is the point of the entry.
What is dangerous is an entry that is not a standalone word but a **fragment**, because it then behaves
exactly like the 16: `동시8`, `다시팔`, `엑섹스`, `아저씨바`, `나따까리`, `다시불러`, `다시불안`,
`구로역시발`. When adding an entry, ask first whether it stands alone as a word. Narrowing preservation
to a token/non-Hangul boundary would close both and break `시발점에서` with it, so it stays as a stated
cost.

Config (`chat.profanity.words` / `.whitelist`) goes through the same validation as the bundled
resources, with **one deliberate asymmetry**: shape for both keys (whitespace plus the invisible
classes: NBSP, ZWSP, word joiner, BOM, Hangul fillers, variation selectors, tag characters,
blank-looking symbols like U+2800; and the 16-char latency budget), and the whitelist rules above —
except that at the
**config** entrance a *zero-width* invisible character is **stripped with a warning** instead of
refusing the boot. Space-like characters still refuse, because trimming `개 새끼` to `개새끼` would be
guessing the operator's intent. The repo's own stated boundary is that refusal is for what is *harmful*,
not for what is *useless*, and a zero-width character opens no bypass — it only makes the entry match
nothing. It was refusing every standard emoji form an emoji picker emits (`❤️` = U+2764 U+FE0F), on the
one path the runbook tells an operator to use **mid-broadcast**, where a failed boot takes down all of
chat (WS sessions, entry, persistence) on every pod. The bundled-resource entrance still refuses: that
failure happens at deploy time and interrupts no broadcast.

The whitelist rules (1) and (3) now run over the **bundled file** too, not only the config CSV. Before,
a `시발` or `8시발` line in `profanity-whitelist.txt` loaded with no refusal and no warning, while the
same value given as config was refused — the file's only gate was one test, and a dictionary-only commit
passes `./gradlew build -x test`. Bundled entries are judged against the **bundled** dictionary only:
judging them against the merged set would make a config word that happens to equal a bundled whitelist
entry refuse the boot, and that situation is deliberately a **warning** (`ChatBeansConfigWarningLogTest`)
— the refusal there was removed on purpose because its premise was false.
All comparisons run on the same `asciiLower` plane the matcher uses, so `SIBAL` and `sibal` are not
judged differently.

A config **word** that is **identical to** a whitelist entry is loaded, not rejected, and reported as
a warning (one that merely *contains* an entry is loaded silently — `CHAT_PROFANITY_WORDS=전염병같은놈`
produces no warning, and `ProfanityDictionaryTest` pins that).
It is tempting to reject — but "it can never match" is false. Only the literal 1-pass token is
preserved; 2A and 2B never consult the whitelist, so `CHAT_PROFANITY_WORDS=전염병같은놈` still masks
`전염병 같은놈`, `전염병1같은놈`, `전염병.같은놈` and `전염병 같 은놈` — four shapes out of five.
Rejecting on that false premise turned the mid-broadcast emergency path into a boot failure for
legitimate terms (measured: 5 of 5 ordinary new insults refused), and a refused bean is chat down on
every pod.

It has to be this strict. The emergency path is used mid-broadcast and every one of these failures is
silent: the entry loads, the count goes up, and nothing is blocked — or, for a bare profanity in the
whitelist, one line unmasks that word across the whole dictionary. Each was measured open before its
check went in.

## Redis keys — one source, and it is package-private

`ChatRedisKeys` (package-private, `infrastructure/redis`) owns every key string. Blocking and reactive
adapters share it by living in the same package. **Do not widen it to `public`.**

| Key | Type | Notes |
|---|---|---|
| `chat:pubsub:{roomId}` | Pub/Sub | `PUBSUB_PREFIX` is shared by publish, pattern-subscribe and channel parsing |
| `chat:room:{id}:sessions` | HASH | value is `userId:lastSeenMillis`; entries not re-registered within 4 heartbeats stop being counted. 24h key TTL is a backstop, refreshed by every heartbeat |
| `chat:room:{id}:ended` | String | 30m — floor = longest room token, ceiling = blast radius of a false end signal |
| `chat:kicked:{roomId}` | SET | **no TTL while live**; room-end attaches 24h. `SADD` must be followed by `PERSIST` — **two writers now**: a kick (`register`) and a reconcile (`reconcile`), both in live-app |
| `chat:banned:{userId}` | String | key presence = banned; TTL is the expiry. No status field, so no stale window |
| `ratelimit:chat:{userId}` | String | prefix position differs; ownership is already in the name |
| `chat:account:events` | Pub/Sub | account-scoped, **deliberately outside `PUBSUB_PREFIX`** — the room pattern parses its suffix as a UUID, so a name under it makes every pod log a parse failure and drop the envelope |

`live:room:ended` is **live's channel** — subscribed, never written, and deliberately not in `ChatRedisKeys`.

Only the kick SET is exposed to WRONGTYPE (`SISMEMBER` type-checks). `SET`/`EXISTS` keys are not, so the
corruption split is not widened to them.

## Wire contracts — four, and all four fail silently when broken

0. **The room token must carry `iat`.** `RoomTokenVerifier` rejects entry when the token is older than
   `max-lifetime`, which bounds the window in which a stale `role`/`owner` can start a *new* session —
   the token has no `jti`, so it is replayable until `exp` and the entry gate re-checks only room-ended,
   kick and ban. live sets it today (`RoomTokenProvider.issue` → `.issuedAt(...)`). **This one does not
   fail silently** — a missing `iat` is rejected like a missing `exp`, so dropping it on the live side
   fails every handshake loudly rather than quietly removing the bound. That is the point: the
   alternative is losing an enforcement nobody would notice.

1. **`ChatEnvelope`** (`application/protocol`, not infrastructure — the port returns it). Both the reactive
   broadcaster and the blocking kick publisher serialize the **real type**; hand-built JSON was the old
   design and a one-character field-name typo compiles fine and vanishes across every pod.
   - The broadcaster disables `FAIL_ON_UNKNOWN_PROPERTIES` for the same reason contract 4 does: a rollout is
     never atomic, so an older pod *will* receive envelopes carrying a field it does not know, and strictness
     makes it drop **every** envelope the newer publisher sends. **Only one direction is bounded by that.**
     Add envelope fields **nullable and unvalidated** — a required one makes newer pods drop every *older*
     envelope instead, and KICK_EVENT shares this channel, so moderation goes with it.
   - Lenience covers *properties*, not *type ids*. Both discriminators (`ChatEnvelope.kind`,
     `ChatMessageTypeMixin.kind`) have no `defaultImpl`, so an unknown `kind` still throws — a **new envelope
     kind reaches older pods as a silent drop of exactly that kind**. Ship the consuming version first, or
     give the discriminator a `defaultImpl` that explicitly ignores unknowns.
   - Lenience also turns *rejections into nulls*: a mistyped field name now yields a null component instead of
     an error. Envelope records therefore validate their own required fields (`KickEvent` rejects a null
     `userId`; `ChatMsg` relies on `ChatMessage`), because this channel is outside the trust boundary — the
     channel-name parser says as much.
2. **`ChatMessageTypeMixin` registration is mandatory** on any mapper touching a CHAT envelope. Omission
   dies loudly on first deserialize — that is the good case.
3. **`OutboundMessage` omits null fields** (`@JsonInclude(NON_NULL)`). It is an 8-type union, so over half
   is empty in any frame; the padding used to exceed the message body. Front-end contract: absent key, not
   `null` — `=== null` breaks, everything else does not.
4. **`ChatAccountEvent`** (`application/protocol`) on `chat:account:events` — chat's own representation,
   **not a cross-domain contract**: it lives in chat-core, so no other module can publish with it. The
   receiver disables `FAIL_ON_UNKNOWN_PROPERTIES`; adding a field otherwise makes every older pod drop the
   event mid-rollout and ban enforcement silently reverts fleet-wide.

**PII gating happens at fan-out, not on the wire.** The envelope carries `senderEmail` and the unmasked
original in plaintext because a privileged reader may be on a different pod. The masked view is the default
and the privileged one is the exception — **never invert that**, or every new role leaks by omission.

Privileged means **room owner or ADMIN** — a decision, not a default: moderators hold the same chat rights a
seller does. What it accepts is that the email is never read by the moderation path and that an ADMIN, unlike
an owner, is scoped to no room at all; the entry log (once per connection) is what answers "who looked, and
when" in exchange. Splitting the two — original text without the email — is one enum constant away, which is
why the axes are separate types rather than a boolean pair.

Ownership and role are two different axes on purpose. Ownership is
per-room: a SELLER visiting someone else's broadcast is a viewer and sees masked text — that split is why
gating moved off `role == SELLER` in the first place. ADMIN is account-level and ignores ownership, which is
what the permission model always said (`f(role, isRoomOwner)`); fan-out had only ever implemented the
ownership half. Moderators need the unmasked text for the same reason owners do — you cannot judge what to
kick without seeing what was said.

## Send path — the order is the contract

`isRoomAlive` (memory) → validation → permission → kick (Redis) → rate limit (Redis) → profanity → Mongo
save → publish. Zero-cost checks first; reordering makes a rejected guest cost a Redis round-trip.

- **`concatMap`, never `flatMap`** on inbound frames — flatMap's 256 concurrency lets a pipelining client
  bypass the local rate-limit window entirely.
- **Persist before publish**, so `DuplicateKey` is caught before a duplicate broadcast.
- **Publish failure is absorbed**: saved + ACK'd is a success; cross-pod delivery is best-effort.
- Rate-limit exemption is `role + room ownership`, not role — a seller in someone else's room is a viewer.
- `clientMsgId` is **required**; the dedup index is partial (`$type:"string"`), so a missing value silently
  disables idempotency for that message. Its length cap must match on both the rejecting and the echoing
  side (`ChatConstants`), or a frame is rejected while its echo is truncated.

## Sessions & shutdown (`ChatSessionRegistry`, streaming-app)

Outbound buffer is **256 and bounded**. Raising it is the documented path to node death.

- **Buffer overflow and emit contention are different failures.** Overflow means the client cannot keep up →
  close. Contention means another thread held the lock → drop that message, keep the session. Merging them
  makes load shed healthy viewers, whose reconnects raise load further.
- Close codes: **1000** room ended, session-lifetime expiry, and liveness-check failure (all three on purpose
  — see below), **1008** entry denial / kick / account-level ban, **1009** inbound frame over the 4 KB limit
  (Netty's decoder closes; the reason string leaks the configured limit), **1013** buffer overflow.
  1013 rather than 1000 exists to stop instant reconnect.
- **1008 no longer implies "do not reconnect" — the SYSTEM code decides.** `KICKED`, `BANNED` and
  `ROOM_ENDED` are states a person cannot clear by retrying. `TOO_MANY_SESSIONS` (SPR-152) is the
  opposite: it clears as soon as one of that user's sessions in that room goes away, and a dead slot is
  reclaimed on the next attempt. So the client **may** retry it after a backoff, and must not fold it into
  the "denied, give up" branch. Splitting the close code instead (1013-style) was rejected: 1008 is
  already the front end's "entry denial" bucket and splitting it changes how every denial is handled.
- ⚠️ **1000 now means three different things, and only one of them carries an explanation.** Room end is
  preceded by `SYSTEM(ROOM_ENDED)`; lifetime expiry and liveness failure send no frame at all, because the
  only thing the client should do is reconnect. **That makes "did a SYSTEM(ROOM_ENDED) arrive just before the
  close?" the sole signal separating "the broadcast is over" from "reconnect now"** — an implicit contract the
  front end must hold. If it instead reads 1000 as "ended", every healthy viewer sees "방송이 종료되었습니다"
  once per lifetime window. **The front-end behaviour is not verifiable from this repo** (no front-end code
  here), so treat it as an assumption to confirm, not a measured fact. Giving expiry its own code would remove
  the ambiguity at the cost of a new front-end contract.
- ⚠️ **1009 has no defined front-end behaviour yet** — nothing in this repo mentions it besides the limit
  itself. A client hitting it is sending frames the protocol layer refuses, so retrying unchanged will loop.
- Terminate signals **both** channels: sink `complete` (drains the buffer so a just-sent SYSTEM arrives) and
  a control sink (the escape when the client never reads). First reason wins; a later kick cannot overwrite it.
- Fan-out budgets are **per operation, not per session** — the loop runs on the pod's shared Redis subscriber
  thread.
- A close frame that never flushes leaves the channel open; `NettyConnectionRegistry` reclaims it after 5s.
  Server idle-timeout does not substitute (measured: it does not apply post-upgrade, and it would also cut
  silent viewers).

## Room end — the signal is lossy, so it is checked three times

`live:room:ended` is Pub/Sub with no persistence: a pod whose subscription was down misses it entirely, and
its sessions keep writing to a finished room.

1. Send path re-checks the marker on a **30s window**, and **stops asking once ended** — a window alone only
   blocks one frame per interval.
2. On discovery it closes **that session only** (1000, same frames as the normal path). Room-wide closing
   would mean duplicating the 5-step handler in transport.
3. Session HASH TTL is the last backstop.

The 5-step handler writes the marker **first** (order sets the size of the re-entry hole), and each step is
independent — a failed notification must not prevent sessions from closing. Log level is chosen by **what
was lost** (control → ERROR, self-healing cleanup → WARN), not by exception type. The kick set is
**expired, not deleted**: the waking signal cannot be verified, and deletion would be irreversible.
⚠️ **That expiry is the only path that ever puts a TTL on `chat:kicked:`, and it hangs entirely on receiving
`RoomEnded` — a Pub/Sub message with no persistence.** Lose one and that room's kick set stays forever.
This mattered less while the write path was broken and the set was never created; now that kicks land, every
lost end signal leaks a key. Room ids are UUIDs so nothing malfunctions — it is pure leak, and it belongs to
live's delivery guarantee, not chat's.

**Recovery for this key now exists, and it lives in live-app** (`PUT /api/v1/lives/{roomId}/chat/kicked`,
ADMIN, `ReconcileKickListService`). It reads the source of truth (`chat_kick_log`) and tops the set up.
Three properties of it are load-bearing and each closed a way it could have made things worse:

- It **only adds** to a healthy key. Deleting first raced the kick path — a kick is DB-first (log commits,
  then `SADD`), so a kick committing after the reconcile's `SELECT` was erased by its `DEL`, permanently and
  silently. Adding is sufficient because `SET ⊆ source of truth` always holds on the app path: the only
  writer of members is a kick, and a kick commits its log first. `DEL` is used **only** on a key whose type
  is not ours, where there is nothing to lose because the content was never readable by us.
- It **refuses a room that is not live**. Otherwise `PERSIST` strips the room-end expiry above and nothing
  re-attaches it — the prescription for this is already written in `ChatKickWriteRepository.register`'s
  javadoc ("block it upstream: read the room and refuse if it is not live"), which the kick path follows and
  this one did not.
- It **reports whether the key was corrupted**, read before the `DEL`. That fact is the strongest evidence
  that enforcement was off, and it is unrecoverable afterwards — `DEL` succeeds whatever the type was.

⚠️ **The signal can also arrive and then be undone.** Room-end sets the 24h expiry; a kick that lands in that
window runs `SADD` + `PERSIST` and strips it, and the two callers sit on different pods so nothing orders
them. `PERSIST` is not removable — it exists because `SADD` inherits a leftover expiry and the whole list
would vanish silently. Two purposes push the same key in opposite directions, which is why recovery for this
key belongs in one place rather than being patched at either end.

## Kick & ban — every input is server-read

Evidence is fetched **by the server** from `messageId`; the command never carries the text (the kicker
would author their own evidence). `ChatKickLog.from` is the only place room·author consistency is checked.

**No input to the permission decision comes from the request body.** Room owner and liveness come from
`live-api`; the kicker's role from the authenticated principal; the target's role from the **evidence
message** (`senderRole`, captured at send time from the live-signed room token). That last one is why this
path does not depend on `user-api`: asking the account store would hand the host app `UserAccountUseCase`,
which also carries withdrawal and nickname changes. The cost is that a user promoted after writing the
message is judged by the old role — acceptable, since the message is what is being judged.

**Order is security, not taste.** Permission before evidence lookup (otherwise the endpoint reports whether
a `messageId` exists), and liveness after permission (otherwise it reports whether a room is live). Unknown
room and unknown user both collapse into permission-denied for the same reason.

Idempotency is the DB constraint (`UNIQUE(user_id, live_room_id)` + `ON CONFLICT DO NOTHING`); the affected
row count is the **ban-escalation trigger and nothing else** — treating `false` as an early return means a
user whose enforcement cache was lost can never be kicked in that room again.

**DB-first**: the log commit is confirmed before Redis and publish. **What that accepts:** a crash between
the commit and `register` leaves an audit row and possibly a ban with no enforcement — the kicked user is
still in the room. There is no compensating job; the recovery path is the moderator kicking again, and that
is safe because every step is idempotent. Saying so out loud matters: silence here reads as "this cannot
happen" rather than "we chose to let it." Registration is `SADD` + `PERSIST` in one
Lua call because `SADD` inherits a leftover expiry and the whole list would then vanish silently.

⚠️ **Both DB writes are `@Modifying` native INSERTs, and Spring Data gives those no ambient transaction.**
Called without one they fail with `No active transaction for update or delete query` — measured, and it made
every kick a 500 until `ChatKickRecorder` was introduced. That bean exists solely to hold the boundary around
the two DB writes while leaving Redis outside it; it must stay a **separate bean** because a same-class call
skips the proxy and the annotation silently does nothing. live's `RtmpIngressAssigner` is the same pattern.
**Unit tests cannot see this** — mocks never run the query and `@DataJpaTest` opens a transaction for you.
`ChatKickRecorderTest` calls with `Propagation.NOT_SUPPORTED` to remove that help.

The propagation is `REQUIRES_NEW`, and that word is load-bearing: under `REQUIRED` the "returned means
committed" invariant silently degrades to "no caller above happened to open a transaction", and the moment
one does, Redis and publish run *before* the commit. **A test that supplies its own boundary cannot see
that** — the recorder just joins it and everything still looks right. The one that can opens an outer
transaction, calls inside it, rolls the outer back, and asserts the log survived.

The mirror write is **extend-only** (`PTTL` compare + `SET` in one `EVAL`). Concurrent kicks in different
rooms each create their own ban and each write the mirror, so an unconditional `SET` lets whoever arrives
last win — and a short TTL landing last releases a user weeks early, because **the mirror is what enforces**.
Re-reading the record for the longest ban was tried and only narrows it: it fixes *what* gets written, never
*who writes last*. Ordering problems need order-independent writes. The cost is that this port can no longer
shorten a ban — an admin reprieve needs the mirror **deleted**, in the same change that adds it.

**That change landed (SPR-165).** `DELETE /api/v1/chat/bans/{userId}` (ADMIN, hosted in live-app) does
three writes as one command: delete the record row, append an **amnesty baseline**, delete the mirror.
The prescription above said "`DEL` + set" — a reprieve turned out to need only the delete, because the
mirror's extend-only script returns 0 on a key with no TTL, so a permanent ban cannot be shortened by
writing at all. Order is **record → mirror**, with the commit in between: the reverse leaves a row that
the next kick re-mirrors, so the ban *resurrects*. The remaining mismatch is the mirror axis — a kick
writes the mirror **after** its commit, outside the lock by design, so a release landing in that window
is undone in the mirror only (over-blocking, repaired by pressing release again; that is why the mirror
delete must not be gated on the row having existed).

Escalation counts **distinct kickers** across rooms on a 2-year window — people, not kicks. Since
SPR-165 the window has **two lower bounds**: that 2 years *and the user's most recent ban release*,
whichever is later (`countConfirmingKickersSince`, a correlated subquery over `chat_ban_release`).
Without the second bound a release is not a release — the kick log is never deleted, so one more kick
**in a new room** re-counts the old kickers and re-applies the same tier. Measuring that class needs a
*new* room as the vector: `ChatKickRecorder` skips the counting step unless `firstKickInThisRoom`. Counting rows
would let one seller reach the threshold alone: the log is unique per `(user, room)`, so three broadcasts and
one kick in each makes three. Three broadcasts is an ordinary week, not a conspiracy, and that would hand
every seller a platform-wide ban over anyone who chats in their rooms. Counting people makes the threshold
mean **three independent judgements**. It costs nothing against the case the window exists for: a user who
rotates rooms is kicked by different people anyway. **The opposite direction is open and has no backstop**:
one seller kicking the same person across ten of their own broadcasts still counts as one, and nothing sits
above that — there is no per-seller ban and no manual ban path. That is the price of this choice, not an
oversight. Note also that the count is really *"three rooms whose **first** kicker differs"* — `ON CONFLICT
DO NOTHING` keeps only the first kick per room, so a second, independent judgement in the same room never
counts. Under-enforcing, and accepted. Scoping per-seller is still wrong for the same reason as
before — it lets a rotating user reach no threshold at all. Thresholds are read as **at-or-above**, not exact — the design doc's table
gives the same answer while kicks arrive one at a time, and differs only where the table is silent (window
shrink, a concurrent kick skipping a threshold). With people as the unit, **only the first rung is realistically reachable** — six or nine distinct
moderators inside two years is rare, so the ladder effectively ends at one week. The numbers were kept
deliberately; the ladder being sparse is the intended shape. That used to read "…while no release path
exists", and SPR-165 removed that premise — but the conclusion stands on a different leg: the reason not
to automate a permanent ban is that **nothing should be server-applied that a human cannot undo**, and a
release *is* a human pressing a button. A path for applying a manual ban still does not exist, which is
why a released permanent ban cannot be re-applied through the app at all (the only `extendOrCreate` caller
is `ChatKickRecorder`, whose tiers top out at one year) — the release therefore snapshots what it deleted
into `chat_ban_release`. **An `ADMIN` kick
counts as one like any other.** That is a decision, not an omission: "one admin ⇒ instant ban" would be a
manual ban wearing `banned_by_id = SYSTEM`, which erases who decided. Admin judgement belongs in a manual
ban path, not in automatic escalation. ⚠️ `V1__init_live.sql`'s comment still describes the old unit
(`3회→1주`, counting kicks) and is **checksum-locked** — this file is the current one.
**Automatic escalation stops at one year.** The doc's
12-kick permanent ban was moved to a human's hands: nothing reversible-only-by-hand should be applied by a
server that has no code to reverse it, and that matches every other call this domain has made (the kick set
expires rather than being deleted; a corrupted key is not self-healed). Permanent bans still exist as rows
with a null expiry — an admin puts them there — but **not through `extendOrCreate`**. That path overwrites
`banned_by_id` and `created_at` whenever a longer expiry arrives, so a later automatic escalation would
replace the admin's identity with `SYSTEM` and the admin's action would exist nowhere (`chat_kick_log`
records kicks, not manual bans). Only a permanent manual ban is safe today, protected by the
`existing.expires_at IS NOT NULL` guard; a dated one is not. A manual-ban path needs its own write —
and the decision about preserving the previous issuer (a history row, or leaving those two columns
alone on conflict) belongs to **that** change, before any manual ban exists to lose. A plain `INSERT`
for a user who already has a row is rejected with `23505`, so it cannot be that write either. A duplicate
kick does **not** re-count: it would let a seller extend a ban indefinitely by re-kicking.

**One row per user is a DB constraint** (`uk_chat_ban_user_id`), not a convention. Concurrent kicks from
different rooms used to write two rows — READ COMMITTED hides each other's uncommitted row, so a transaction
does not stop it; a constraint does. Two rows made un-ban half-work: deleting one leaves the other
enforcing, and the screen says "released". **Both stores are now extend-only** — the record's write is
`ON CONFLICT DO UPDATE` guarded by a longer expiry, matching the mirror's Lua. They are not the *same* rule
(the record compares absolute instants, the mirror compares remaining TTL) but they converge on the same
effective state, and where they differ the mirror is never shorter — the safe direction.

**A ban reaches sessions that never speak — but only if the pod was listening.** The entry gate stops new
connections and `SendChatService` re-checks on every frame, so a banned user is blocked the moment they
*talk*. A session that sits silent in another room used to survive indefinitely; `chat:account:events` now
closes it. What remains open is narrow and worth knowing: Pub/Sub is not durable, so a pod whose
subscription was down at publish time never sees the event, and a silent session on that pod stays until it
speaks, the room ends, or the next kick republishes. **Widening `EntryGate`'s 30-second recheck window does
not help** — that window only runs on the send path, and anyone sending is already caught. Closing it needs a
periodic per-pod sweep, priced against session count. The order there is a contract: **reason first, then
close** (a closed sink drops the frame, leaving a 1008 with no explanation), and **a failed reason must not
stop the close** — a notification is worth less than a banned user staying connected.

**An ADMIN entering someone else's room is logged, and that is all it is.** *(The design doc specifies no
admin-entry audit; chat chose this shape. If §6 ever regulates one, compare against the choices below rather
than assuming this predates it.)* The line lives in
`ChatSessionRegistry.register` — where the *session* is admitted, not where the token is issued (a token that
never connects saw nothing) — and carries `sessionId`, `adminId`, `roomId` so it joins this file's close and
drop lines. Deliberately nothing else: an audit records the access, **not a copy of what was read**, and the
room's owner is not on the token (`owner` is a boolean) so naming them would cost a live lookup on every
entry, for a value `roomId` already answers after the fact. **Fan-out and audit ask the same question through one function.**
`ChatPermissionPolicy.seesUnmaskedContent(role, isRoomOwner)` decides who reads the original, and
`usesPrivilegedViewInSomeoneElsesRoom(...)` is that plus "not their own room". They used to be written out
by hand in two modules, which meant a new role granted the privileged view could reach fan-out without
reaching the audit — **more exposure, less trace**, and silently. The two cannot drift because there is one function; what a
parameterised test adds is a **table written independently of the policy** — deriving the expectation from
the policy makes the assertion a tautology that survives opening the original to everyone (it did, once).
Widening the privileged view is caught by `PrivilegedEntryAuditTest.ordinaryEntriesAreNotRecorded`, which is
the actual tripwire. The policy
returns a boolean rather than `ChatMessageVisibility`: that enum lives in `application`, and domain may not
depend on it (ArchUnit enforces this). `ChatSession` also rejects `ADMIN` with `isRoomOwner`, so the
own-room half is unreachable today — a test pins that invariant so it gets looked at with the audit
condition if live and chat ever settle that combination.

⚠️ **This is a trail, not an audit record.** Retention, search and integrity all live in log shipping, which
this repo does not configure. And it answers *that* an admin entered, never *what they saw* — a room with no
messages and one that streamed ten thousand emails to them produce the identical line. Per-message logging
was rejected on fan-out cost, so that gap is a choice, not an oversight: a real audit needs a store **and** a
way for a person to query it. `chat_kick_log` shows the difference — it is read by escalation, but nothing
lets a human ask it anything, which is what "a store is not an audit" looks like.

**The envelope is chat's own, not a cross-domain contract.** `ChatAccountEvent` lives in chat-core, so no
other module can publish with it (`X-core → Y-api ONLY`; `-core` is never depended on). Withdrawal needed
the same effect and took that route: `user:account:withdrawing` is **user's** channel, published by
`UserAccountService` from an `afterCommit` hook, and chat absorbs it in `RedisAccountWithdrawingSource`
(`{"userId","requestedAt"}` → `Flux<UUID>`) for `AccountWithdrawingHandler` to close the sessions. It cost
one more channel, subscription and handler, and kept the type inside this module — same trade `live:room:ended`
makes.

Why a **separate** handler rather than a branch in `ChatAccountEventHandler`: that one's source is chat's own
channel, this one's is user's. Two streams, two subscriptions — merged, one source's disconnect would stall
the other's actions too.

Why the signal is the **request**, not the hard delete: withdrawal is two-phase (status → `WITHDRAWING`
immediately; hard delete later in batch). The account stops being active at the request, so waiting for the
delete would leave the whole grace period open. And the request path already calls `revokeAllSessions`, which
only blocks re-entry — **an established WS session never looks at the token again**, so that revocation and
this event are different axes.

⚠️ **There is no backstop for a missed publish**, and there are two ways to miss one: the publish itself
failing, and **a publish that arrives while the subscription is down** (Pub/Sub keeps nothing, so that window's
messages are gone).

**The bound then differs by axis.** On the **send** side it holds and does not depend on the signal:
`EntryGate.isRoomAlive` re-reads the persisted ended-marker on the send path, so a stale session is closed on
its next send (`terminateRoomEnded`) even with a dead subscription — bound = window + next send. On the
**read-only** side it does not: that re-check exists only on the send path ("a receive-only session on the same
pod is not closed until it sends"), and the remaining mechanism is room-ended cleanup, which rides **the same
subscription** (`ClearRoomEndedMarkerService`: "the only caller of markEnded is `LiveRoomEndedHandler` — woken
only by the `live:room:ended` subscription"). For a pod whose subscription was down, that never arrives either,
so the real bound is a **pod restart**.

(This paragraph first claimed the bound was simply "until the broadcast ends, because room-ended clears
sessions". The conclusion held for the send axis but the mechanism cited was the very subscription whose loss
it was bounding.)

The record can only be shortened by deleting the row, and **the mirror must be deleted in the same change**:
enforcement reads `chat:banned:`, never the table. Order it record → mirror — a break in the middle leaves
over-blocking, while mirror-first can *resurrect* the ban (a surviving row is picked up by the next kick and
re-written to the mirror). And **never gate the mirror delete on the row still existing**: after a
record-first break the row is already gone, so a retry that stops at "no ban to release" never reaches the
mirror. The whole release command must be safe to run again — deleting an absent key is a no-op. Over-blocking
only heals by itself for dated bans; a permanent one is mirrored without a TTL and stays until a human acts.

That used to cost a hole — a failed ban write after a committed log meant the retry took the duplicate path
and skipped escalation. **The transaction closed it**: a failing ban INSERT rolls the log back with it, so
the retry starts fresh — pinned by a test that swaps in a throwing ban store and asserts the kick count is
unchanged. What can still fail outside the boundary is the Redis mirror, and a retry heals that
because the active-ban lookup runs *before* the duplicate check and hands the caller the ban to re-mirror.
That ordering is the whole self-healing story, so it is pinned by a test.

## Viewer count — `HVALS` is not the bottleneck it looks like

`count` is `HVALS` + distinct, and the O(N) is deliberate: the HASH is one row **per tab**
(`sessionId → userId`) and the number shown is **people, not windows**. `HLEN` is therefore not an
optimization but a different number — measured off by the multi-tab factor (1 user, 2 tabs → 2×). The
repository test pins this, so the swap fails the build.

The cost was investigated and **no redesign is warranted** (measured, redis:7 container, 36-byte UUID values):

| Shape | Worst concurrent command |
|---|---|
| `HVALS`+distinct alone, 12,000 entries | p50 3ms / max 6ms |
| 100 concurrent on the *same* 12,000-entry hash | SETNX **211ms** — crosses the rate limiter's 200ms |
| after in-flight merge: 60,000 sessions in one room | 28ms |
| after merge: 6 pods × 100 rooms (360,000 entries at once) | 130ms |

So the cache + in-flight merge in `ChatSessionRegistry` is not partial mitigation — it *is* the fix, and it
holds at five times the sizing model. Entry-gate reads (`EXISTS`/`SISMEMBER`) stayed at 0–2ms throughout.

And the residual tail is **not Redis being single-threaded**: one `ReactiveStringRedisTemplate` means Lettuce
shares a single native connection, so a large `HVALS` head-of-line-blocks the commands behind it. The same
load through a separate connection factory dropped p99 from 113ms to 12ms. If this tail ever matters, a
dedicated connection for this one read is the cheap answer — a maintained counter is the expensive answer to
the wrong cause.

## Viewer roster — a zombie entry is worse than a missing one, and both are possible

`chat:room:{id}:sessions` holds `sessionId → userId:lastSeenMillis`. A pod crash skips `remove`, so its
entries stay; counting them inflates the room's viewer count **for the rest of the broadcast**, because the
only reclaim was the 24h key TTL. And the key's expiry was refreshed only on join, so its real condition was
"24h since the last *new* join" — a quiet room could expire mid-broadcast.

- **The heartbeat is a re-registration (`HSET`), never a TTL touch.** A touch cannot bring back an entry that
  Redis lost (replica promotion), so the count would stay short for the whole broadcast. Pinned by a test that
  deletes the field and asserts the heartbeat restores it — a touch implementation cannot pass it.
- **Staleness is 4 × the heartbeat interval**, so three missed beats still count a live viewer. Two would
  drop a healthy viewer the moment Redis wobbles — the same trap the liveness check already sprang once
  (2 lost pings cut healthy sessions in the first window). A static guard refuses to load if the ratio drops
  below 3×.
- **Counting never writes.** Stale entries are skipped on read and reclaimed by the key TTL or `clearRoom`;
  making the count a write path would put a write on every join.
- **Values with no separator count as live.** That is the pre-format shape, and old pods keep writing it during
  a rolling deploy — treating it as stale would erase *that pod's entire audience* from the count. The other
  direction (a zombie surviving to TTL) heals once the deploy finishes.
- **One timer per pod, not per session.** The registry already knows every local session, and each session
  already owns a liveness interval — per-session would double that. The interval comes from the port
  (`heartbeatInterval()`), because "how often must I re-register to stay counted" is that adapter's contract;
  split across two places, one side changes and live viewers vanish from the count.
- **A single session's failure is absorbed.** If it ended the stream, the pod would stop re-registering
  *everything* and the whole audience would disappear after the staleness window — worse than the bug being
  fixed. `concatMap` also keeps a slow Redis from overlapping rounds and doubling the writes.
- Staleness is judged against the **reader's** wall clock, so cross-pod clock skew applies; the 3-beat margin
  swallows second-scale skew. Moving the comparison into Lua would use Redis's single clock but would also
  move a full scan onto its single thread, and that cost is measured to be fine where it is.
- **`HEXPIRE` (field TTL) is deliberately unused.** It is simpler — no timestamp parsing, no bounded
  arithmetic — and `redis:7` resolved to 7.4.11 here, which has it (measured). But the test tag floats, so a
  machine that pulled 7.2 lacks it, and production's version is not visible from this repo. That is a
  "green locally, broken in production" shape. Revisit when 7.4+ is guaranteed.

## Scale axis — what is measured, and what the numbers must still come from

Two items were deliberately **not** implemented here; SPR-152 then split the first in two and enforced
one half. Both were investigated; the reasons differ.

**Connection cap** — **split into two axes in SPR-152; one is now enforced, the other is not.**

- **Abuse cap — enforced, and tunable since SPR-176.** `ChatSessionRegistry.maxSessionsPerUserPerRoom()`
  bounds concurrent sessions per `(userId, roomId)`; the value comes from **`chat.session.max-per-room`**
  (default: `DEFAULT_MAX_SESSIONS_PER_USER_PER_ROOM`, which the placeholder's own default is written from,
  so the number lives in one place) and **a value below 1 fails boot** rather than
  quietly running an uncapped pod — the same choice as `managementPortMustDiffer`. There is no upper
  bound: a very large value means "no cap", which is a state an operator may choose (it is what this
  pod did before SPR-152). ⚠️ **That guard covers `< 1` and nothing else**, so it does not mean a typo
  cannot leave a replica unprotected: `500`, or a misspelled key falling back to the default, both boot
  happily — the first uncapped in practice, the second not what the operator set. And the number is
  **not magnitude-free**, which an earlier version of this bullet asserted without measuring:
  `reapUnresponsiveIfFull` only runs **once occupancy has reached the cap** — it returns at
  `size() < cap` — and the scan it then does inside `computeIfPresent` is sized by **occupancy, not by
  the cap**. So the direction is the opposite of the obvious one: **raising the cap makes this path run
  less often**, because reaching it gets harder. The absolute cost is unmeasured on this side.
  The configured value is exposed as **`chat.sessions.max_per_room`** (`ChatSessionGaugeConfig`), because
  the `admission_capped` / `reaped` pair that `infra/AGENTS.md` tells the on-call to read **stops being
  readable at a large cap** — both flatten to 0, which is also what healthy looks like — and because
  replicas can disagree mid-rollout.
  Over the cap, `register` denies with `EntryDeniedException.TOO_MANY_SESSIONS`
  → `SystemMessageCode.TOO_MANY_SESSIONS` + close 1008, counted by `chat.sessions.admission_capped`. The
  unit is `(user, room)` because a room token is scoped to one room and is **replayable within its ~90s
  TTL** (`modules/live/AGENTS.md`), so that pair is the narrowest thing that closes the replay window.
  ⚠️ **Three live-owned references still name the old symbol** and this side cannot fix them:
  `modules/live/AGENTS.md:392` and `:402` cite `MAX_SESSIONS_PER_USER_PER_ROOM`, and
  `apps/live-app/src/main/resources/application.yaml.example:279` states
  `"streaming-app 의 MAX_SESSIONS_PER_USER_PER_ROOM=5 는 …"` — a value that is no longer a constant.
  Neither prose statement depends on the number (one says the issuance cap does not bound it, the other
  that it is keyed by user), so both stay true; the yaml comment's `=5` does not. It is recorded here
  rather than edited, because live is not this side's to change.
  Three things it does **not** do, all deliberate and all recorded at the constant:
  - **It does not cover guests.** Unauthenticated entry mints a **fresh random `userId` per entry**
    (`EnterLiveService`), so guest connections never collide on the key. That branch is an issuing-side
    rate limit in live, outside this repo.
  - **It is pod-local**, so the effective bound is `cap × replicas` (the cap moved out of the code in
    SPR-176). Going global would add a Redis round
    trip to every connect; the data is already there (`ChatSessionRepository.add` writes userId), so this
    is a choice, not a constraint.
  - **It created a new capability: locking the rightful owner out.** A replayed token can fill the slots,
    and sessions outlive tokens (default lifetime 60m, no mid-session re-verification). Evicting the
    *oldest* session would fix the UX and hand the attacker a worse primitive — cutting someone's **live**
    session — so only **dead** slots are reclaimed (`reapUnresponsiveIfFull`, needed because a silently
    dropped socket holds its slot until the first liveness check at `deadline + interval` ≈ 2 min). The
    root fix is `jti` on the room token, in live.
    - ⚠️ **It does not close the ~2 min window, and that window cannot be closed.** An earlier version
      of this section claimed it did. Since the reap is handed `deadline + interval` (it has to be — see
      the next bullet), its threshold is exactly when the liveness loop fires anyway, so what it buys is
      the **≤8 s tail** between the loop firing and `cleanup` sweeping the slot. More fundamentally the
      liveness clock starts at registration, so a socket that dies right after connecting is
      **indistinguishable** from a quiet-but-alive viewer until the first check — and the mobile-handover
      scenario is exactly that case. Closing it would mean giving up "never cut a live session". The
      mitigation is real but partial; do not read the old claim as a reason to lower the threshold.
    - That reap **frees the slot and does not terminate**. Terminating there would spin on the Lettuce
      NIO event loop — the connect path's thread, since nothing in streaming-app switches schedulers —
      for up to 5 × 50 ms, which is the thing this file forbids twice. Each session's own liveness watch
      does the terminating on `Schedulers.parallel()` within one interval.
    - It is handed `deadline + interval`, not `deadline`: the liveness loop delays its first evaluation
      by exactly that buffer, and a reap evaluating the same predicate at an **arbitrary** moment without
      it would kill a session that the loop's 30 s grid would have spared — then hand the freed slot to
      the caller. That would make "only dead slots" false in the window the buffer exists for.
    - Consequence, with the derivation because the number hangs on a ratio: live sessions per
      `(user, room)` can reach **2× the cap for ≤8 s** — `FORCED_CLOSE_GRACE 3s +
      CLOSE_FLUSH_TIMEOUT 5s` — and a reap cannot fire more than once per `liveness-deadline` per key
      (the newly admitted sessions are not unresponsive before it). At the default 90 s,
      **8 s ≪ 90 s**, so it settles at 2× and does not stack. Raise the close ceilings and it grows
      linearly instead (measured without cleanup at the default cap: 5 · 10 · 15 · 20 over four cycles).
      - ⚠️ **`liveness-deadline` below 8 s also breaks it, and nothing stops you setting that.**
        `SessionTimeouts` validates only `>= 2 × ping-interval`, so `ping-interval: 1s` +
        `liveness-deadline: 2s` passes and this derivation stops holding. That gap predates SPR-176
        (which made the *cap* tunable, not the deadline) and no guard was added for it — recorded here
        rather than implied, because the sentence above would otherwise read as unconditional.
    - It reclaims **nothing** against an attacker who answers pings, and its count is exposed separately
      (`chat.sessions.reaped`) because `chat.session.cut{liveness}` cannot tell "the cap freed a dead
      slot" from "the liveness loop collected one" — exactly the axis that separates an attack from a
      mobile handover.
- **Capacity cap — still not implemented**, and its number still has to come from a real pod. The failure
  it would prevent is cascading: a pod takes more than it can serve, GCs to death, and its whole audience
  reconnects onto the next pod. (`1013` closes an individual slow session; it was never a cap.)

Measured (in-process, 20,000 sessions, three register/unregister cycles):

| | |
|---|---|
| Registry heap per idle session | **~1,990 B** |
| 12,000 sessions | ~24 MB |
| Retained after everyone leaves | **0 MB** — no leak across cycles |

So **heap in the registry is not the binding resource** — the cap's number has to come from the axes this
measurement cannot see (Netty/TLS buffers, file descriptors, fan-out CPU), which means the load track, not a
guess. Shipping a number from nowhere is what this repo spends its time undoing.

⚠️ That measurement first showed 15 MB retained per cycle, growing linearly — which reads exactly like a
per-session leak. It was **Mockito recording 40,000 invocations** on the stubbed repository. With a
hand-written stub the residual is 0. If you re-run this, do not use a mock for the collaborator.

**Per-room serialization — done, not pending.** An earlier version of this section described it in the
present tense as future work ("today `serialize` runs inside each session's pipeline, so one message costs
N serializations in a room of N"). `OutboundFrame` closed it: the sink carries a frame that computes the
wire **bytes** lazily and shares them across every session in one fan-out, so **serialization and UTF-8
encoding** became O(variants) instead of O(sessions). The per-session term is not zero: that class measures
`wrap` at **56.1 ns** per session on the production factory, which is why it puts the encoding saving at
**~1.4 ms**, not the 2.09 ms a naive reading gives. Measured and recorded at that class: one serialization 0.77 µs, 12,000 sessions 9.23 ms →
4 variants 0.0031 ms (~3,000×), and the UTF-8 encoding half was collected in the same change. The reason
the old paragraph gave for deferring it ("the sink would have to carry wire text instead of
`OutboundMessage`") is also spent — the sink carries `OutboundFrame` today.

**Dropping `clientMsgId` from broadcasts — do not open this as written.** The old paragraph claimed the
contract premise was **verified**, on the grounds that `ACK` carries both `id` and `clientMsgId` so the
mapping can be built from ACK alone. **That premise is false**, and the front-end guide says so itself:
**`chat-frontend-guide.v1.2.md:142`** (in the separate `sapari-be-docs` repo, not in this one) states it
as a **contract**, not a symptom: *"그래서 서버가 보낸 사람의 프레임에는 `clientMsgId`를 실어 보낸다
(브로드캐스트 포함). 순서와 무관하게 짝지을 수 있다."* Removing it from the broadcast is therefore a
published-contract violation on its own, before any reasoning about order. The reasoning is there too:
its 2026-08-08 correction (`:140`) states that ACK arriving first is *not* guaranteed and that the
broadcast is in fact the likelier one to win (Redis writes subscriber output buffers before replying to the publisher).
With no `clientMsgId` on the broadcast, a sender whose broadcast arrives first sees a message with an `id`
it does not know yet and renders it as new; the ACK then stamps that same `id` onto the optimistic bubble,
leaving **two renders of one message**. The four per-recipient variants therefore stay four: the sender
axis is what carries `clientMsgId` — and note that the guide scopes that value to the **account**, not
the session (`:144`: other tabs of the same account receive it, and the rule is "match only what is in my
pending list"), so "sender" here is a user, not a socket. After `OutboundFrame` its cost is **at most one extra
serialization per fan-out** — 0.77 µs by that class's measurement, and **zero when no session consumes
that variant**, because the bytes are computed lazily. An earlier version of this paragraph put it at
"~1.5 µs per message" with no derivation; the honest statement is a bound, not a figure. Either way it is
not the O(sessions) term that made collapsing them worth a ticket in the first place.

**Batching** is still unimplemented and still unmeasured. With serialization already shared, what is left
to save is sink emissions and WebSocket frames per fan-out — nobody has put a number on either, so there
is nothing here to size a ticket from.

## Viewer count updates — the same frame again, only when it changed

`ROOM_INFO` used to be sent once at join, so the number was a **frozen snapshot**: the roster work made the
count accurate, and nothing could deliver that accurate number to sessions already connected.

- **The frame is reused, not replaced.** The front-end guide's documented handler is a pure state setter
  (`setActiveCount` + `setOwnerMode`), so repeat delivery is idempotent — whereas nothing in the contract says
  what a client does with an *unknown* `type`, which makes a new frame type the riskier option.
  ⚠️ It is still a **front-end contract addition**: that guide says "join only", and it has been updated.
- **Only on change.** Pushing every round costs one frame per session per round forever, which in a large
  room is the entire cost of the feature. A quiet room now costs nothing.
- **The first round seeds, it does not send.** The join-time `ROOM_INFO` is the baseline; sending again would
  hand every session in every room one duplicate of a number it already has.
- **An unknown count is never pushed.** `activeCount` is nullable to mean "don't know", and pushing that as an
  *update* blanks a number the viewer could already see — different from not knowing it at join.
- **The read bypasses the 3s count cache.** That cache exists to damp a join storm, but this timer's whole job
  is to observe change, so going through a read-suppressor would hide anything inside the cache window. There
  is also nothing to damp here: one read per room per round is already bounded.
- **`isRoomOwner` is resolved per session** (`sendToRoomGated`). One shared frame would switch the owner UI on
  for everyone or off for the owner.
- Pods read independently, so viewers in one room can briefly see different numbers. Accepted — reconciling
  would need cross-pod agreement, which this soft number does not earn.
- It rides the **heartbeat timer**, after re-registration, so the count it reports is already the
  zombie-free one. A second timer would double a per-session cost.

## History (VOD) — the gate is one predicate and the exclusion is in the query

`GetChatHistoryUseCase` → `GetChatHistoryService` (chat-core, **blocking**) ← `ChatHistoryController`
(live-app). Blocking because the caller is MVC: a `Mono` there just adds a reactive hop before a `block()`.
It lives in live-app for the same reason kick does — the beans that answer "who owns this room" and "when did
it start" are in live-core, and chat asks through `live-api` ports only.

- **Two modes, mutually exclusive**: cursor (`beforeId`) and VOD window (`playbackPositionMs`). Both at once
  is rejected rather than one silently ignored, because a client that gets the other mode's result cannot tell.
- **`findBefore` reads exactly `size`, not `size + 1`.** The shipped frontend contract has no `hasNext` and
  infers it from `messages.length === size`. Reading one extra makes "more pages" and "exactly full"
  indistinguishable. Do **not** put this result in the shared `CursorPage` — that helper assumes `size + 1`.
- **Kicked senders are excluded inside the Mongo query** (`$nin`), never after the read. Filter afterwards and
  a full page comes back short, which the frontend reads as end-of-list — **the scroll stops silently**.
  Pinned by a Testcontainers test and by a service test that captures what was passed down.
- **The privileged view (original text + sender email) uses the same predicate as the live fan-out**:
  `ChatPermissionPolicy.seesUnmaskedContent(role, isRoomOwner)` — room owner **or** ADMIN. The code used to
  warn that writing history against the owner-only helper would open live to admins while closing history;
  that helper is now `toPrivilegedView()` and the warning is answered. The design doc's §10.7 still says
  owner-only — the product decision recorded on `ChatMessageVisibility.FULL` (admins and sellers get the same
  chat powers) is the one that holds.
- **An unknown requester role throws instead of narrowing.** Falling back to MASKED would leave a legitimate
  operator unable to see the original text in their own room, and that looks like nothing at all.
- **Anonymous requests are allowed** (public VOD chat, design-confirmed), so `requesterId` is nullable and
  `requesterRole` is its pair — one without the other is rejected because a half-identity silently narrows.
- **`playbackPositionMs` → absolute instants** via `LiveRoomView.startedAt`; elapsed time is not stored.
  The window is 5 minutes, clamped at `startedAt`, and capped at **200 regardless of `size`**. A room that
  never started is rejected, not answered with an empty list — empty means "nobody talked".
- Command-contract violations become **`CHAT-009` (400)**. The record throws `IllegalArgumentException`, which
  the global handler does not map, so a client bug would otherwise be a 500 plus an `Unhandled exception` log.
  The handler was *not* widened: that would turn infrastructure-origin `IllegalArgumentException` into 400 too.
- `ChatMessageRepository.findByRoomIdBefore` (reactive) has **no caller and never will** — history went to the
  blocking port. Its javadoc used to instruct the next person to do the opposite; that is corrected. It is a
  cleanup candidate, left in place only because deleting it removes nine tests with it.

## Logging — the level is the required response, and the field is the query

Structured ECS JSON for every app, set as *default* properties from `common/global`
(`StructuredLoggingDefaults`). Two measured traps live there: Boot 4 moved `EnvironmentPostProcessor` to
`org.springframework.boot` and left a `@Deprecated` twin in `...boot.env`, so the old one compiles and
silently never runs; and registration is `META-INF/spring.factories`, not `.imports`. A test asserts the
actual console bytes, because the property can read `ecs` while the output is plain text.

**Correlation is written to the Reactor Context, not MDC.** MDC is a `ThreadLocal`, and measured it is
`null` after a `publishOn` and `null` inside `onErrorResume` — where nearly every degradation log in this
module lives. `io.micrometer:context-propagation` plus `Hooks.enableAutomaticContextPropagation()` bridges
it; `ChatWebSocketHandler` writes `session_id`/`room_id` for the handshake and `user_id` inside the verified
session. Field names are snake_case and live only in `LogFields` — ECS lifts MDC straight to top-level JSON
beside `log.level` and `error.stack_trace`, so camelCase would put two conventions in one object.

- **No `request_id` on the reactive side.** A WS session, not a request, is the lifetime unit, and
  `session.getId()` exists from the first line. A second identifier for the same thing just raises the
  question of which one to group by.
- **The Redis subscriber thread has no session scope**, so fan-out, room-end and account-event pipelines
  write `room_id`/`user_id` themselves. Those three run only in streaming-app (live-app publishes and
  excludes `com.sapari.chat` from scanning), so the hook is always on where they run.
- `addKeyValue` (single-line fields) also reaches the JSON, verified — it is the only option where no scope
  exists. Whether a key present in *both* MDC and `addKeyValue` stays valid JSON was **not** measured, so
  nothing in this module creates that case.

**Level discipline: ERROR means a human must act.** Now that the degradation metrics exist, "log it at ERROR
or it disappears" is no longer a reason — the meter carries the rate, and alerting belongs there, not on
individual lines.

| Shape | Level | Why |
|---|---|---|
| Moderation read failed, request let through | WARN | heals when Redis returns; `chat.policy.open` has the rate |
| Rate limit degraded / TTL fallback | WARN | enforcement continues, only accuracy is lost |
| Publish or fan-out dropped | WARN | the message is persisted; only live delivery was lost |
| **Key type conflict** (`WRONGTYPE`) | **ERROR** | retries never heal it; a human must clear the key |
| Payload parse / deserialize failure | **ERROR** | a contract mismatch; it takes a deploy |
| Room-end or account-event step failed | **ERROR** | control is lost — the room stays open, the ban is not applied |
| Forced-close signal failed to emit | **ERROR** | that session can no longer be cut |

The contrast is what matters, so it is pinned by a test: raising the transient branch to ERROR is a
one-character change that breaks nothing otherwise, and it would bury the lines that need action.

**Runtime log level changes are deliberately unavailable.** The actuator `loggers` endpoint would do it, but
it mutates state over POST and this app has no Spring Security — exposing it on the unauthenticated
management port would let anyone reachable there turn on DEBUG and flood the log pipeline. Until there is
auth or a NetworkPolicy, levels change by `LOGGING_LEVEL_<PACKAGE>` and a restart. That is also why this
module has **no DEBUG logging worth enabling** (measured: 0 of 143 statements repo-wide are DEBUG) — adding
it only pays off once it can be switched on.

## Observability — what is measured is the policy *not* being enforced

`ChatMetrics` (`application/port`, no micrometer) ← `infrastructure/metrics`. ArchUnit rule 16 already
covers chat by wildcard — verified by planting a `MeterRegistry` field in an application class and watching
it fail. `ChatMetrics.NOOP` is selected via `ObjectProvider` when no registry exists, **not**
`@ConditionalOnBean`: that condition is evaluated before autoconfiguration, so it picks NOOP even when a
registry does arrive. Measured — the wiring test supplies the registry *from an autoconfiguration*, and the
conditional variant really does fall back to NOOP.

| Meter | Tags | What it means |
|---|---|---|
| `chat.policy.open` | `policy`, `checkpoint`, `failure` | a moderation read failed and the request was let through |
| `chat.ratelimit.degraded` | `mode` | pod-local window, or a denial whose TTL read failed |
| `chat.delivery.dropped` | `stage` | publish failed / session emit contention / ping tick dropped |
| `chat.session.cut` | `reason` | the server closed a session |
| `chat.dictionary.fastpath.lost` | — | the fast-path dictionary swap was missed; this pod enforces with a stale one |
| `chat.persist.timeout` | `stage` | the Mongo operation bound cut a persist step — see the branch warning below |

⚠️ **`chat.persist.timeout` is a different axis from `chat.delivery.dropped` and the two must never be
summed.** `delivery.dropped` means *persisted, live delivery lost* — every `DeliveryStage` value rests on
that. Adding them produces a number that says "it is in the history" about messages that are not.

⚠️ **`chat.persist.timeout` carries a `stage` tag, and its two values mean opposite things — do not sum
them either** (`PersistStage`, SPR-178). `stage=write` means the write never landed and **nothing was
stored**. `stage=duplicate_recovery_read` is the `DuplicateKey` recovery read: its premise is that the
document **already exists**, so the message is in the history and what was cut is reading it back. Summing
the two reports a stored message as lost — exactly the inversion the `delivery.dropped` warning above
forbids in the other direction.

The two also differ in what the sender sees. `stage=write` is translated to
`ChatErrorCode.PERSIST_TIMEOUT` (503, `CHAT-012`); the recovery read is **not translated**, so the sender
gets an untranslated 500. That gap is deliberate — translating it would make `CHAT-012`'s "저장하지
못했습니다" false on a branch where the message was stored — and closing it needs one more error code,
i.e. a wire-contract change. The count exists now; the per-branch wording does not.

⚠️ **`chat.dictionary.fastpath.lost` had been missing from both tables** until SPR-177, although
`ChatMeterNames` declared it when it was added. The rule in this section is "a scrape test walks every enum
value; add a line when adding a meter" — and **neither half was done**: an earlier version of this sentence
said "the test did its half", which was false. `ChatMetricsPrometheusScrapeTest` and
`MicrometerChatMetricsTest.neverThrows` both hand-enumerate, and both were short by that meter — so the
`safe()` invariant was unguarded for it too. SPR-177 added both meters to both enumerations; the lesson is
that a hand-enumerated guard is only as complete as the last person who remembered it, and saying "the test
covers it" is a claim that needs the enumeration read, not assumed.

- **The adapter must never throw.** Most call sites are inside `onErrorResume` lambdas — a throw there
  propagates downstream and *denies* the request the policy decided to allow. Instrumentation would invert
  the availability policy. `safe()` plus null-tolerant tags, pinned by a throwing-registry test.
- **`failure=store_corrupted` is never merged into `unavailable`.** A corrupted key does not heal on retry
  or on Redis coming back; a human must clear it. Merged, the permanent condition hides under the transient one.
- **`checkpoint` separates entry from send.** Open at entry only means a sanctioned user is watching; open at
  the send re-check means posts land in an ended room. A mutation test caught that this distinction was
  initially unguarded.
- **`chat.session.cut` exists because the close code does not identify the cause.** Close 1000 means three
  things (room end, lifetime, liveness) and only room end is preceded by an explaining frame; close 1008
  means two (`MODERATION` and `WITHDRAWN` — a ban is a sanction, a withdrawal is the user's own click, and
  merging them inflates the sanction rate). `BUFFER_OVERFLOW` (1013) is in the same meter.
  - ⚠️ **The reasons do not sum to all server-initiated closes** — an earlier version of this line claimed
    they did, and that was false. At least four closes never reach this meter: the three entry denials
    (`KICKED`, `BANNED`, `ROOM_ENDED`) happen in `EntryGate` before a session exists and carry no metric at
    all (of the denials only the session cap is counted, under `chat.sessions.admission_capped`), and a
    frame over the 4 KiB limit closes with 1009 from the transport layer, which chat code never sees. Read
    this meter as **sessions that were established and then cut by a server decision**, not as the whole of
    server-initiated closes.
- Every meter is **0 in the healthy case**, which is what makes alerting possible — tests assert the counters
  are not even created on a healthy path.
- The Prometheus registry rejects same-name-different-tag-key meters, so a call that omits a tag makes that
  series vanish silently at runtime. A scrape test walks every enum value; add a line when adding a meter.
- **Exposure is locked in code, because this app has no Spring Security.** `ObservabilityExposureConfig`
  refuses to boot unless `management.server.port` is set and differs from `server.port`, and unless the set
  of endpoints that are **actually exposed** is exactly `{health, prometheus}` — note **exactly**, so one
  extra endpoint is also a boot failure. It does **not** read `exposure.include`/`exclude` any more: it asks
  `WebEndpointsSupplier.getEndpoints()`, which covers six config axes at once (`include`, `exclude`,
  `endpoint.<id>.enabled`, `endpoints.enabled-by-default`, `endpoint.<id>.access`,
  `endpoints.access.default`) — measured, each of the last four booting successfully while the old
  key-reading guard passed. An earlier version of this bullet described the deleted implementation: a
  *subset* check, a `Binder` read, and a YAML-list-vs-scalar notation axis. All three are gone — parsing is
  gone, so notation is not an axis.
  A wide-open exposure would serve `/actuator/env` unauthenticated. `/actuator/env` masks
  its values (measured from bytecode: `show-values` defaults to `Show.NEVER`, and `Sanitizer.sanitize`
  then returns `"******"` unconditionally) — it leaks the property *names* and sources, and that masking is
  a default one line can flip. An earlier version of this line claimed `env` itself carried the secrets.
- Port reachability is pinned both ways by a test that boots the real app with containers and makes real HTTP
  calls: 404 on the user port, 200 on the management port. **Expect 404, not live-app's 401** — there is no
  security chain here to answer first. Unlike live's equivalent it runs in CI, because the infrastructure this
  app needs is two containers and one throwaway public key.

## Startup — the port is the deadline, not the runner

Measured boot order (`EagerSingletonsRunBeforeThePortBindsTest`, fixed port, real TCP connect):

| Stage | Port |
|---|---|
| singleton pre-instantiation (`@Bean` method bodies) | refused |
| earliest `SmartLifecycle` (`phase = MIN_VALUE`) | refused |
| `ApplicationRunner` | **accepted** |

The reactive web server is a `SmartLifecycle` at phase 2,147,481,599, so it binds inside `finishRefresh()`;
runners come after. **Anything a request depends on must therefore be finished in a bean body, not a runner** —
Mongo index creation is there for exactly that reason. Note the web server *object* exists during
`onRefresh()`, long before `start()` binds, so its presence proves nothing (measured).

Why it matters here and not everywhere: streaming-app has **no actuator**, so the only readiness probe
Kubernetes can attach is a TCP socket check. A runner blocked on Mongo answers that probe `Ready` for its whole
duration — three indexes × 30s server selection = up to 90s of handshakes routed into a pod that is about to
die. Failing inside refresh is not a new failure mode: a throwing runner already broke
`SpringApplication.run()` (measured). It only moves the failure to before the port opens.

## Tests

Boot 4 split the slices into modules and **has no Mongo slice** (verified by jar inspection):
`spring-boot-data-jpa-test`, `spring-boot-jdbc-test`; `MongoProperties` binds to **`spring.mongodb`**, not
`spring.data.mongodb` (removed in 4.0.0 — a wrong prefix binds nothing and falls back to localhost).
Prefer `@ServiceConnection` over naming properties for exactly that reason.

- `ChatCoreTestApplication` decomposes `@SpringBootApplication` to keep a single scan, which loses
  `TypeExcludeFilter` — it is re-added explicitly. **Without it every slice silently runs a full scan.**
- Any `@SpringBootTest` on chat-core boots `RedisChatBroadcaster`, which connects in its constructor
  (`autoConnect(0)`, deliberate — it removes the pre-subscribe loss race). **Such tests need a Redis container**
  even when they test something else.
- Schema for JPA tests is applied from the **real Flyway files** — all of them, in version order
  (`support.LiveSchema`). A second copy drifts while staying green, and reading only `V1` leaves every
  later migration untested while the suite stays green.
- **`db/migration/live` is declared an input of `:chat-core:test`.** It is not one by default — the SQL is
  read at runtime through `user.dir`, so changing only a migration used to leave `test` UP-TO-DATE and a
  schema mutation appeared to break nothing (measured: 832ms green with the unique index deleted). The
  `inputs.dir` line in `build.gradle` closes it; if you move where the schema comes from, move that line too.
- **A red test still has to say so.** Every `StepVerifier` terminator without a ceiling —
  `verifyComplete()`, bare `verify()`, `verifyError(...)`, `verifyErrorMessage(...)` — waits forever for a
  signal that a regression may have removed, so the test *hangs* instead of failing (measured: 110s and still
  running on a missing close, 2m34s on a `thenCancel().verify()` whose `expectNext` never arrived).
  **Every call site now carries one** — measured on the commit this line lands on: **139 sites across 17
  files** carry `expectXxx().verify(VERIFY_CEILING)`, with `VERIFY_CEILING = Duration.ofSeconds(10)`
  declared per test class, counted over `modules/chat` + `apps/streaming-app` tests. `block(VERIFY_CEILING)`
  is 246 in the same scope. (This line read "117 across 15 files" for a while with no base commit — the
  rule two bullets down, which this very number broke. 117 → 128 was tests added in between; 128 → 139 is
  the 11 sites `SendChatServiceMetricsTest` and `EntryGateMetricsTest` carried *unbounded* while both files
  already declared a `VERIFY_CEILING` they used elsewhere. Re-measure with the scope spelled out; do not
  patch the old number.) Verified end to end: making a signal never arrive now fails in 12s with
  `VerifySubscriber timed out` instead of hanging. Keep it that way — a ceiling-less terminator does not
  just hide one bug, it voids **every mutation verdict in that run**, because the suite cannot report at
  all. "passing ≠ catching" has a twin in "failing ≠ telling".
  - **`.block()` carries the same rule** — every test call site carries a ceiling; verified end to end by
    making `register` return `Mono.never()`, which turns the class red at the ceiling instead of hanging.
    **Count a conversion against the commit it lands on and state the scope** — fixing only the baseline
    lets the scope drift instead. This sweep is 201 sites measured at its own parent over
    `modules/chat` + `apps/streaming-app` tests. Three commits earlier the same scope gives 199 (two
    tests added in between call `register(...).block()`); including `src/main` gives 204; widening to the
    whole repo adds two *comment* lines in `architecture-test` that merely mention `.block()`. Two of
    those four numbers landed on 201 by different routes — **the counts matching is not evidence that the
    same thing was counted.**
  - **Production has 4 of its own, and they are a different kind of bounded** — do not read them as part
    of the rule above. The test ceilings buy "the suite goes red and reports"; these four buy "the pod
    dies instead of serving". `ChatMongoConfig`'s startup index creation blocks, and against a closed
    address it throws `DataAccessResourceFailureException` after **30,053 ms** (measured) — the Mongo
    `serverSelectionTimeout` default. That is finite, but what it buys is "boot fails loudly" and the
    cost is a stall bounded by **`ChatMongoConfig.INDEX_CREATE_CEILING`** (SPR-152; 10 min, and it is a
    deadline over **all four** index creations, not per call). Measured driver defaults
    (`MongoClientSettings.builder().build()`): `serverSelectionTimeoutMs=30000`,
    **`socketReadTimeoutMs=0` (infinite)**, `socketConnectTimeoutMs=10000`, global `timeoutMs=null` — and
    the repo sets no Mongo *client* timeout anywhere, so those defaults apply. So 30s bounds only the
    "never picks a server" mode; once a server *is* picked and `createIndex` does not answer, the driver
    has nothing that would cut it — which is why the bound lives at the call site instead.
    - **This line has been wrong four times.** 90s → "120s for the four … a fifth adds 30s" → "~30s" →
      "worst wait is that one value" while the ceiling was still **per call**. The first three fixed the
      *number* without measuring **what bounds it**; the fourth measured what bounds it but not the
      **unit** — per call or per method. Measured: ceiling 400ms × 4 calls at 300ms each → total
      **1,279 ms**, **0 throws** — a per-call ceiling does not bound the method, it scales with the count.
      That number is read as startupProbe sizing, so the unit is the load-bearing part — and the
      sizing itself now lives where an operator would look for it (`infra/AGENTS.md`, streaming-app
      Probes), with the window's "nothing is listening" half beside it.
    - Earlier versions of this bullet also said *"Want a bound? Set `timeoutMs`"* and *"a
      `VERIFY_CEILING`-style value is not the lever here — the probe configuration is (`infra/`), so this
      is recorded, not changed."* SPR-152 pulled exactly that lever, at the call site rather than through
      `timeoutMs` (a global `timeoutMs` would bound runtime queries too, and getting that value wrong
      breaks serving rather than boot). `ChatMongoIndexCeilingTest` pins both the bound and its unit.
    - **Dying at that ceiling does not restart the work** (measured on **standalone** mongo 7 via the
      `hangAfterInitializingIndexBuild` failpoint — the one remaining "uncertain" on this bullet, and the
      answer turned out to be the opposite of what the code said). A second identical `createIndexes`
      issued while a build is in progress **waits** rather than returning `IndexBuildAlreadyInProgress`;
      killing the client outright (its container removed) leaves the server-side build running
      (`createIndexes` op still listed, `IndexBuildsCoordinatorMongod-0` alive) and the index completes
      with nobody attached. So the pod's death does not undo progress — N× the ceiling costs ~N restarts,
      then it boots. **Say "waits", not "joins".** What was observed is that the re-issued call blocks
      instead of erroring; that does not distinguish "registered as a joiner" from "waiting on a lock",
      and the operational conclusion rests on the *survival* half alone. Two gaps remain: a restarted
      pod's `createIndexes` has never been observed **returning success** after the build finished (no
      client was attached in the measured runs), and the topology is standalone — a replica set builds in
      phases with a commit quorum, and DocumentDB is a compatibility layer. The repo does not say which
      one production runs (`documentdb|replicaSet|mongodb+srv`: 0 hits, while `mongodb` has 16), so that
      branch is unmeasured, not measured-and-fine. Two places said "every restart re-fires
      the build" (the constant's javadoc and the operator-facing exception message); both were guesses
      and both are corrected. The sizing advice does not change — it is still a full outage while it
      lasts — but "permanent CrashLoopBackOff" was wrong.
  - **`await()` is the third strand** — a start latch or barrier with no ceiling leaves the worker blocked
    forever when the main thread throws before `countDown()`. The reason is **not** that a non-daemon
    thread holds the worker JVM open: it does not (a thread sleeping 10 minutes still lets the Gradle test
    worker exit cleanly, measured). The reason is that the stuck thread keeps hammering the sinks and
    registry and so pollutes the *next* test in the same JVM. Measured on the commit this line lands on
    over `modules/chat` + `apps/streaming-app` tests: **15 bounded `await(...)` sites**, and the ceilings
    are **not uniform** — 10s, 30s, 60s and a `RENDEZVOUS_TIMEOUT` constant all appear. **Three of them
    discard the returned boolean** (`ChatKickRecorderConcurrencyTest:370`,
    `ChatBanStateRepositoryImplTest:317` and `:324`), so a timeout there is not reported by the `await`
    itself; the assertion that follows is what fails, which is weaker than throwing with a reason. An
    earlier version of this line said "all 7 sites take `await(30, SECONDS)` and throw with a reason" —
    wrong on the count, the uniformity and the throwing, and with no baseline commit, which is what the
    rule two bullets up asks for.
    - **Re-measured at SPR-152's session-cap commits: 18 in the same scope, 0 unbounded.** The count is
      right; **the attribution was wrong three times over** and is corrected here, because this bullet is
      the one that records "an earlier version was wrong on the count" — the repo's own rule is that
      *matching counts are not evidence the same thing was counted*, and that applies to its own
      corrections. The genuinely new site is `ChatWebSocketIntegrationTest`'s admitted-latch;
      `SessionAdmissionCapTest`'s start and finish latches were already in the delta's base commit
      — **`c72dbc5e`**, named because "base" read as pre-SPR-152 and the file itself is new in that
      commit (the start latch was a bare→bounded **conversion** there, not an addition) — and
      `ChatSessionGaugeConfigTest` has **no**
      `await(` at all — what grew there was `block(VERIFY_CEILING)`.
    - A fourth statement-form site shows up in the discard list (`RedisRateLimiterDegradedTest:337`) and
      it is **not** the same defect: that one is a `CyclicBarrier`, whose `await(timeout, unit)`
      **throws** `TimeoutException` instead of returning a boolean, so discarding its result loses
      nothing. Only `CountDownLatch.await` is silent on timeout. Do not "fix" the barrier site.
    - The scope sentence says `modules/chat` + `apps/streaming-app`, so it is incomplete rather than
      wrong: `apps/live-app`'s `ReconcileSchedulerLockTest` still has an unbounded one, outside it.
  - **The uniform 10s costs nothing on a passing run** — a ceiling is only spent when it is exceeded, and
    the worst case is **not** ceiling × call sites. An exceeded `block(Duration)` *throws*
    (`IllegalStateException: Timeout on blocking read…`), which aborts that test method, so the remaining
    calls in it never run. The bound is ceiling × (methods and worker threads that reach a stall), not
    ceiling × call sites — in `ChatSessionRegistryTest` that is **~100 call sites spread over ~60 test
    methods** (measured at `eecde755`; the counts move with every commit that adds a test, which is why
    the rule two bullets up says to state the baseline), so the order of magnitude is minutes, not tens
    of minutes. Slow, but it *reports* — that is the trade.
  - **Scope of "every hang is bounded"**: that claim covers `modules/chat` + `apps/streaming-app` only.
    `modules/live` still has one (`ActiveRoomGaugeTest` — a bare `await()` plus an unbounded
    `Future.get()`), and live is outside this ticket's reach. State the scope whenever the claim is
    repeated; a count or a claim taken over a narrower scope does not verify a repo-wide sentence.
  - **Don't size a ceiling off a test case's reported time.** The ceiling bounds the *terminator's wait*,
    not the case — setup, `Thread.sleep` before `StepVerifier.create`, and an inner `.timeout(...)` all sit
    outside it. Picking values off per-case durations once produced a "raise the ceiling" commit whose slow
    tests turned out to use an inline literal rather than the constant, so the margin did not move at all.
  - **A slow case is not automatically a load-sensitive one.** `RedisChatBroadcasterTest` and
    `RedisLiveRoomEndedSourceTest` take ~1.2–1.5s per recovery case, but they are mock-based (no
    `@Testcontainers`) and that time is the production `Retry.backoff(…, ofSeconds(1))` constant plus its
    jitter — a scheduled delay does not stretch under CPU pressure the way a `park`-vs-busy-spin margin
    does. The actually container-backed classes among the converted 15 are `CrossPodBroadcastTest`,
    `ChatKickRedisRepositoryTest` and `ChatMessageRepositoryImplTest`.
- **A test that supplies wiring the app does not is worse than no test** — it goes green while production
  breaks. Both of this branch's runtime failures hid behind exactly that (`@DataJpaTest`'s transaction, a
  test-local UUID customizer). `ChatModerationWiringTest` boots the real live-app context and asserts on the
  beans it actually built; it supplies its own properties so CI runs it rather than skipping it by tag.
- **A test claiming a guarantee must be mutation-checked**: revert the production line, confirm that test —
  and ideally only that test — fails, restore. `checkOrphanJavadoc` gates javadoc placement in `check`.

## Persistence mapping — the policy is the net

`@Mapper` here is `unmappedTargetPolicy = ERROR`, not the MapStruct default (`WARN`). Under the default, a
field added to a domain record but not to the mapper leaves that target empty and the build still passes
(measured: `warning: Unmapped target property` + BUILD SUCCESSFUL) — every read of a persisted message then
silently misses it. `live` uses `IGNORE` for the opposite reason: its entity carries persist-only columns
with no domain counterpart, and that is stated in `LiveRoomMapper`.

**Deleting the attribute is not caught by the build**, because nothing is unmapped at the moment you delete
it. ArchUnit rule 20 (`mapstruct_mappers_must_fail_on_unmapped_targets`, repo-wide except `live`) closes that gap: it reads the
value out of the class file — `@Mapper` is `RetentionPolicy.CLASS`, so reflection cannot — and when the
attribute is gone it sees the annotation default `WARN`, so one rule covers both the downgrade to `IGNORE`
and the deletion.

Neither net is replaced by the round-trip test. Adding a domain field breaks every positional
`new ChatMessage(...)` call (13 of them, one of which is across the module boundary in `streaming-app`), and
the cheapest way to make those compile again is a `null` argument — which makes both sides null and the
round-trip assertion pass (measured: 4 round-trip cases green, only the test that walks
`getRecordComponents()` red). That walking test has its own blind spot: a primitive field lands on `0`,
which is not null, so only the policy catches it.
