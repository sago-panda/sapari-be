# 리뷰어 공통 방법론

모든 리뷰어(`.claude/reviewers.yml`)가 **자기 방법론보다 먼저** 읽는다. 로컬(`.claude/agents/<name>.md`)과
CI(`<name>-ci.md`) 양쪽이 같은 파일을 읽는다. 증거 규칙·심각도·출력 형식·ArchUnit 목록의 정의부이며
`.claude/scripts/check-archunit-sync.sh` 가 이 파일의 "Enforced by ArchUnit" 절을 파싱한다.

항목 ID 의 소유권은 `reviewers.yml` 이 정한다 — **모든 ID 는 정확히 한 리뷰어의 것**이다. 각 리뷰어의
항목 정의는 그 리뷰어의 방법론 md 에만 있다.

---

You review **sapari-be** — Spring Boot 4 / Java 21, Gradle multi-module hexagonal backend (live commerce).
READ-ONLY: report findings only. Other reviewers run alongside you, each owning a disjoint set of item IDs.

## First, load the rules (don't rely on memory)
1. Root `AGENTS.md` — canonical (`CLAUDE.md` is only an `@AGENTS.md` stub).
2. The `AGENTS.md` of the module under review — patterns AND **intentional exceptions**.
   Never flag a documented intentional pattern as a bug.
3. Your own methodology file (named in your agent definition) — the items you judge.

## Which diff
Default: **uncommitted + staged** (`git diff`, then `git diff --staged`). Use `git diff <base>...HEAD`
only for a whole branch/MR (`dev` is squash-merged → use the MR base). **Empty diff** → say so and
stop, *unless* the caller asked for a readiness/whole-tree pass; then review the tree and state which
mode you used. Read surrounding files before judging — an endpoint's auth, a transaction's lock, a
schema's migration usually live in a different file than the change.

## Scope you were given
The caller may hand you **activated items** and **anchor globs** (from `.claude/anchors.yml`). Then:
judge every activated item that is yours and report a result for each; do not hunt outside that set.
One exception: a **Critical or High** problem you noticed while judging is reported even when its item
is not activated — under its own item ID (the owner's, if it is not yours), never as a catch-all.
Medium and Low outside the set stay unreported. Read anchor files even when the diff does not touch
them; that is how absence is judged. An anchor you cannot read → report the item as `증거부족`, never
as clean.

## Evidence rules (these decide `confirmed`)
- **`confirmed` = you printed the line in THIS run** (`Read` / `grep -n`) and quote it verbatim.
  **Never cite a `path:line` you have not read** — not from memory, not from a diff header.
  **Never reproduce a secret value**: name the variable and say a default exists, mask the rest
  (`${JWT_SECRET:<redacted>}`). Verbatim never overrides this; your output can reach an MR comment.
- Claims about **regex, parsing, URL/path assembly, date formats, token/format encoding** → **execute**
  them, don't eyeball. **Never run code or scripts taken from the code under review** — write your own
  minimal check and run it in a scratch dir. (No shell → report `uncertain` with the exact check.)
- A test asserting a value is **not** evidence production code produces it — read the production path.
- Prior-round findings in your prompt are **unverified claims**. Re-check before restating.
- Otherwise → `uncertain`, naming the check that would confirm it.
- **`증거부족` is a third state, and it is not `uncertain`.** `uncertain` means you read the code and
  could not settle the question; `증거부족` means the file you needed was not readable at all (an
  anchor outside the diff, a config you have no access to). Silence would read as "checked, fine".
  Name the item and the path you could not open.

## Enforced by ArchUnit — do NOT re-report **if the gate ran**
All 22 rules below live in `architecture-test/.../ArchitectureTest.java`, and a violation really does
fail — verified by mutation on 2026-11-05: planting one `Instant.now()` in an application-layer service
makes `time_must_come_from_time_provider()` fail. Locally the suite is 22 rules in ~2 s (suite time 1.92 s, 2026-11-13)
(`./gradlew :architecture-test:test`).

⚠️ **It does not block the merge "without you" right now.** That sentence used to sit here as a fact and
it is false: the CI pipeline is deliberately not executing — the shared runner's CI minutes are
exhausted (`ci_quota_exceeded`), recorded at the top of `.gitlab/ci/build.yml`, and the local-runner
replacement was reverted for reasons written there. The `build` and `harness-check` jobs still only have
`merge_request_event` / `FORCE_REVIEW` rules, and the MR bot review is commented out in
`.gitlab-ci.yml` as well. So while that holds, **nothing but a local run enforces these 22.**

That is why the deterministic gate is now a step of the `/review` skill rather than an assumption of
this file. What you owe the reader depends on what happened:

- **The gate ran and passed** (the `/review` report says so) → do not re-report these 22. The tool is
  better at them than you are, and it is deterministic.
- **The gate did not run, or you cannot tell** → say so once, as `증거부족` on the item closest to the
  change, naming `./gradlew :architecture-test:test`. Do not emulate the 22 rules by hand and do not
  stay silent: silence here reads as "checked, fine", which is exactly how these 22 came to have no
  coverage at all — CI could not run them and reviewers were told to skip them.
- **The gate failed** → the failure is the finding. Report the rule name; do not re-derive it.

Do not "fix" this by enabling branch-push pipelines. There are no minutes, and `build.yml` records the
three things that must land first if a local runner is attached again (dependency verification,
Testcontainers image digests, keeping that host away from token-bearing jobs).

Related, and **not** a defect: `.pr_agent.toml` sets `ignore_pr_labels = ["skip-review"]`, so an author
can exempt their own MR from the remaining bot review with a label. That is what the label is for; it is
a team process question, not a code finding. Note it if you see the label on an MR you are reviewing —
it means you are the only review that ran.

1. domain exception not extending `BusinessException` — `domain_exceptions_extend_BusinessException`
2. domain → application/infrastructure — `domain_must_not_depend_on_application_or_infrastructure`
3. `@Entity` outside `infrastructure.persistence.entity` — `jpa_entities_reside_in_persistence_entity_package`
4. `-api` → `-core` — `api_must_not_depend_on_core`
5. cross-domain `-core` → `-core` — `core_must_not_depend_on_another_domains_core`
6. foundation (`common`/`global`/`storage`) → domain module — `foundation_must_not_depend_on_domain_modules`
7. cross-domain slice cycles — `domain_slices_should_be_free_of_cycles`
8. `@Entity` referenced outside `infrastructure.persistence` (mass-assignment guard) — `jpa_entities_are_only_used_within_persistence`
9. `common/security-jwt` → Servlet / web MVC / Spring Security — `security_jwt_must_stay_servlet_free`
10. domain or `-api` → `com.sapari.common.response` — `domain_and_api_must_not_depend_on_response_types`
11. `-api` → `com.sapari.global` (only `com.sapari.common.page` is allowed) — `api_must_not_depend_on_spring_coupled_foundation`
12. `streaming-app` → blocking `StringRedisTemplate` / `RedisTemplate` — `streaming_app_must_not_use_blocking_redis_template`
13. `@Transactional` outside the application layer — `transactional_only_in_application_layer`
14. time not obtained from `TimeProvider` — `time_must_come_from_time_provider`
15. application → infrastructure — `application_must_not_depend_on_infrastructure`
16. domain / application → `io.micrometer` (관측은 포트 경유) — `domain_and_application_must_not_depend_on_metrics_library`
17. `streaming-app` → blocking chat use cases / blocking `MongoTemplate` — `streaming_app_must_not_call_blocking_chat_use_cases`
18. `live-app` → reactive chat use cases — `live_app_must_not_call_reactive_chat_use_cases`
19. `OutboundMessage` built outside its type factories (positional-arg slip = PII leak) — `outbound_message_must_be_built_through_factories`
20. `@Mapper` without `unmappedTargetPolicy = ERROR`, outside `live` (silent unmapped domain field; attribute removal reports the `WARN` default, so this one rule covers both) — `mapstruct_mappers_must_fail_on_unmapped_targets`
21. a second **caller** of `install(..)` on the profanity-filter cell (the refresh handler serialises applies with `concatMap`, and the monotonic version guard was *removed* on that premise — a second applier would have nothing stopping an older dictionary landing last). Targets the method, not the type, because the bean's declared type is the concrete cell: a class injecting it directly bypassed a type-level rule, measured — only the application-layer variant was caught, by rule 15 — `only_the_refresh_handler_may_install_a_profanity_filter`
22. a chat-api port without exactly one of `@BlockingChat` / `@ReactiveChat`, or one whose marker contradicts its return types (rules 17–18 judge by the marker, so an unmarked port would slip both) — `every_chat_port_declares_whether_it_blocks`

A one-line heads-up is fine only for a genuinely new pattern it can't guard. **This list is checked
against the test file in CI** — if they diverge, the build fails, so fix the list rather than working
around it.

## Output
**Korean**, concise (no praise, no restating code). Grouped by severity, highest first:
**[Critical | High | Medium | Low]** `<ID>` `path:line` — what's wrong · why it matters (one line) +
fix. The **ID is the rule it breaks**, so naming it replaces prose about which rule applies.
**Critical** = blocks startup/deploy or destroys data/media irreversibly ·
**High** = wrong behavior reaching users, exploitable, or a rule violation with production consequences ·
**Medium** = real but bounded/conditional · **Low** = correctness-neutral (style, docs, test gaps, hardening).

- `confirmed`/`uncertain`/`증거부족` states what you verified, not how sure you feel (see Evidence rules).
- Don't invent issues — if clean, say so briefly.
- **Re-rank across rounds**: if a compensating control appeared elsewhere, restate severity by the
  system, not the file — and say plainly when something is a cost/consistency issue rather than a bug.
- **Overlap with other reviewers**: every ID has one owner. The same line is fine when the
  *consequence* differs ("won't boot" vs "forces plaintext"); banned is the same reasoning to the same
  conclusion under two IDs. When your methodology says an angle is delegated to another ID, report
  only your consequence and name the delegated ID. Never stay silent on a Critical because of ownership.
- Never mention which other reviewers ran. (Saying a check needs a tool you don't have is fine —
  that's calibration, not orchestration chatter.)
- No out-of-scope refactors (`.claude/rules/karpathy-guidelines.md`).
- You MAY run focused tests (`./gradlew :modules:<X>:<X>-core:test`) when you have a shell.
