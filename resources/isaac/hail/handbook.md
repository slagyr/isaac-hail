# isaac.hail — Isaac's operating handbook, hails and bands

You are a crew running inside Isaac. This chapter covers what **isaac-hail**
owns: hails (out-of-band requests that become turns), bands (config-declared
templates a hail can address), `isaac hail send`, the `POST /hail/send` HTTP
route, and the `hail-send` crew tool. If you haven't read `isaac.foundation`
yet (config mechanics, `handbook__configure` itself), read that first — this
chapter assumes it. Crews, sessions, frequencies as a shape, and the turn
queue/tool loop belong to `isaac.agent`; this chapter names them once and
moves on. This chapter's own topic id is `isaac.hail`; each `##` heading
below is also addressable on its own, e.g. `isaac.hail#bands`.

**The one thing to know up front:** Hail is stateless. A hail is not a
tracked object with its own lifecycle — it is a message that becomes
**exactly one** durable Agent turn, synchronously, at send time. There is no
separate hail id, no hail store, no hail worker, no retry queue, and no
dead-letter file inside this module. The id `isaac hail send` prints (or the
HTTP route returns) *is* the turn's id — look it up with `isaac turns show
<id>` (`isaac.agent`). Everything that happens **after** a turn is admitted
— busy-waiting for a session, retrying a stalled provider, cancellation — is
Agent's turn queue's business, not Hail's (isaac-ex4q). An older design (a
Hail router/worker/store with its own dead-letter quarantine, and a
`:reach` fan-out key) was retired; `:reach` on a band is now a hard config
error (isaac-5gu1), not a legacy fallback.

## Hails and the turn queue

**What it is.** A hail is a request built from **frequencies** (who should
receive it — see Addressing, below) and either a `:prompt` or a band that
supplies one. Sending a hail does three things in order, all before
anything is queued: expand the band (render its template, merge its
`:data`, build a metadata preamble), resolve the frequencies to a live
session synchronously, and — only if that resolves — submit one turn to
Agent's durable queue and return. If resolution fails, nothing is queued
and the caller gets an error immediately; there is no "try again later."

A hail record (whichever surface builds it — CLI, HTTP, or the crew tool)
carries: `:frequencies` (addressing), `:prompt` (optional if a band
supplies the template), `:params` (a map interpolated into the band
template and always echoed back as data), `:reply-to` (a prior turn id to
thread under), `:thread-id` (explicit override), and `:idempotency-key` (a
retried send with the same key returns the same accepted turn instead of
submitting a second one — dedup lives in Agent's turn store, `isaac.agent`).

**How to send one.**

- CLI: `isaac hail send [addressing flags] [--prompt <text>] [--params
  <edn>]`, or `isaac hail send -` to read a whole hail record from stdin
  (`--from-json` to parse it as JSON instead of EDN).
- HTTP: `POST /hail/send` — see The HTTP route, below.
- From inside a turn: the `hail-send` crew tool — see below.

None of this is a `config` path; sending a hail is an action, not a
declared setting. What *is* config is a band declaration (Bands, below).

**How to verify.** `isaac hail send ... --dry-run` builds the record and
checks it round-trips through EDN (so it can actually be persisted) without
resolving addressing, expanding the band, or queuing anything — it's a
shape check, not a delivery check. Add `--json` or `--edn` to print the
full accepted record (id, `:frequencies`, `:origin`, `:preamble`) once it
really sends. `isaac turns show <id>` / `isaac turns list [--all]`
(`isaac.agent`) is the source of truth for what actually got queued, held,
or finished.

### Troubleshooting

- **A hail seems to have vanished with no trace.** It didn't — either it
  was refused at send (check the CLI's stderr / the HTTP response's
  `error`/`hint`, or the exit code) and nothing was ever queued, or it
  queued fine and is sitting on Agent's turn queue; `isaac turns list
  --all` covers both "still held" and "already finished." There is no
  separate hail-side pending file or dead-letter to check — that mechanism
  was retired (isaac-ex4q).
- **A retried send with the same `--idempotency-key` created two turns.**
  It shouldn't — dedup is keyed and lives in Agent's turn store; two ids
  for one key is a bug to report.
- **You're looking for `hail show` / `hail drop` / `hail requeue`.** Those
  belonged to the retired stateful router and no longer exist. Use `isaac
  turns show <id>` / `isaac turns drop <id>` instead (`isaac.agent`).

## Bands

**What it is.** A band is a config-declared template a hail can address by
name instead of spelling out a session, prompt, and overrides every time.
Bands live in the `hail` config table, one entity per band: a `.edn` file
(fields only), a single `.md` with YAML frontmatter plus a prompt body, or
an `.edn` paired with a companion `.md` for just the prompt — all under
`config/hail/<name>.edn` / `.md`. `isaac hail send --band <name>` (or a
hail whose `:frequencies` name `:band <name>`) resolves that band before
anything else happens.

A band's fields (the full schema is at `` `config:hail` `` — every field
below carries its own description there too):

| Field | What it does |
|---|---|
| `crew` | Sessions whose `:crew` matches this id are eligible (a single processing crew, not a list). |
| `session` | Explicit session id(s) eligible for the band. |
| `session-tags` | Tags a session must carry (AND). |
| `create` | `:never` (default) or `:if-missing` — see Addressing, below. |
| `prompt` | The band's template, usually written as the companion `.md` body rather than inline. |
| `data` | A map of extra context delivered with every hail on this band, merged with the hail's own `:params` (params win per key) and interpolated the same way the prompt is. |
| `with-crew`, `with-model`, `with-effort`, `with-context-mode` | Per-turn overrides projected onto the dispatched turn, same shape as `isaac.agent`'s frequencies overrides. |
| `cycle` | An overlay map (`:limit`, `:checkpoint-every`, `:checkpoint-prompt`, `:wrap-up-prompt`) merged onto the crew's own cycle config field-by-field — a key the band sets wins, a key it leaves out keeps the crew's value `[verify: the merge point is isaac-agent's charge construction, not this module]`. |
| `continuations` | Schema default 2 when omitted (`isaac.hail.bands/default-continuations`). `[verify: not currently read by hail send/prepare — confirm it's actually wired to a dispatched turn before relying on a non-default value]`. |
| `base` | Inherit from another band file (below). |

A band with **no** `session`/`session-tags`/`crew`/`base` fails config
validation outright — a band that can't address anything is a config
error, not a silent no-op.

**Templating.** The prompt body and any string value under `:data` use
`{{var}}` placeholders, filled from the hail's `:params` (a missing
binding renders as empty string, not an error). `:params` always win over
a same-named `:data` key. An explicit `--prompt` on the send bypasses the
band's template entirely but the band's `:data` still applies, still
merged with `:params`, still rendered into the turn's metadata preamble —
band data is never silently lost just because the prompt was overridden.

**Inheritance.** A band may declare `base: <other-band-file-stem>` to
compose over a shared template — coordinates every band in a project needs
(a bean repo, a notification channel) without repeating them. Merge is one
level deep, child over base: map-valued fields (like `data`) merge
key-by-key with the child's key winning; everything else is replaced
wholesale by the child. A child with no prompt body of its own inherits the
base's. Chains resolve transitively, with cycle detection. A band file
whose name starts with `_` (a **template** band) participates in
inheritance but is never itself addressable — `hail send --band
_some-template` is refused.

**How to change it.** Bands are config, so ordinary config mechanics apply:

```
config set hail.bean-pickup.crew ops
config set hail.bean-pickup.session-tags.project/chess
config set hail.bean-pickup.create if-missing
```

A companion prompt is easiest written as `config/hail/<name>.md` directly
(frontmatter for the fields, body for the prompt) rather than through a
single-field `set`.

**How to verify.** `isaac config validate` schema-checks every band file —
a bad type, an unknown key, a missing base, or a base cycle is a config
error (exit 1), not a warning. `isaac hail send --band <name> --dry-run`
still only checks record readability (see above); to actually see a band's
rendered prompt and merged data, send for real (or against a throwaway
session) and read it back with `isaac turns show <id>`.

### Troubleshooting

- **`config validate` rejects a band mentioning `:reach`.** That key is
  gone — Hail no longer fans a single hail out to multiple recipients.
  Replace it with `session`, `session-tags`, or `crew`.
- **`config validate` rejects `:crew` as a sequence.** `crew` on a band (or
  in `:frequencies`) is one processing-crew id, a string — not a list.
- **A base reference fails with "missing base" or "cycle".** The named
  template band doesn't exist, or two bands reference each other. Both are
  refused at validate time rather than hanging or silently dropping the
  inheritance.
- **A `.md` band file is reported "dangling".** Same rule as any
  frontmatter entity (`isaac.foundation`, Files): it needs frontmatter of
  its own, or to be the companion of an `.edn`/`.md` pair that already
  exists.
- **Sending to a template band fails with "template".** That's by design —
  `_`-prefixed bands exist only to be inherited from.

## Addressing a hail

**What it is.** Addressing decides which session (and which processing
crew) receives a hail. It's resolved **synchronously at send time**, before
anything is queued — an address that can't resolve to at least one live
session is refused immediately, never parked "waiting for a match." The
selectors are the same shape `isaac.agent` uses for frequencies generally
(`session`, `session-tags`, `crew`, `with-crew`/`with-model`/`with-effort`/
`with-context-mode`, `create`); Hail's own contribution is the `band`
selector and the rules for combining a band's selectors with a hail's own.

Rules, in order:

- **An explicit `session` always wins.** If `:frequencies :session` names a
  session, that's the complete recipient — a band's own `session-tags`
  never filter it out, though its `with-crew`/template/`data` still apply.
  A named session that doesn't exist is refused at send even if the band's
  `create` would otherwise allow spawning one — explicit addressing never
  falls back to create.
- **A band's selectors and the hail's own selectors intersect.** `--band X
  --session-tag Y` narrows to sessions matching *both* the band's tags and
  `Y`.
- **`create` decides what happens with no match**, only when there's no
  explicit `session`: `:never` (the default) means "no match is
  undeliverable" — refused at send, nothing queued, and a band-declared
  address additionally logs a `:warn` `:hail/undeliverable` event.
  `:if-missing` means match-or-create: an existing match is used (waited
  on if busy — see below); with none, **Agent's turn queue** (not Hail)
  creates a session under the resolved crew when it admits the turn,
  tagged with the band's `session-tags`.
- **A match that's merely busy is not a non-match.** The turn is still
  queued and waits in Agent's durable queue for that session to free up
  (never spawns a sibling session). That waiting, and everything after
  admission, is `isaac.agent`'s concern.
- **`with-crew` overrides the processing crew** without changing which
  session is addressed — useful when a band names an explicit session but
  still wants a specific crew's model/behavior to process it.

**How to change it.** Addressing selectors on a band are config (see Bands,
above); a one-off hail's addressing is CLI flags or HTTP body fields, not
config:

```
isaac hail send --band bean-pickup --session-tag project/chess
isaac hail send --crew marvin --session-tag wip --prompt "Heads up"
isaac hail send --session tidy-cavern --prompt "wake up"
```

**How to verify.** A refused send prints its reason to stderr (`"no
session: <id>"`, `"no session"` for a tag/crew match with none live,
`"unknown band: <name>"`) and exits non-zero; nothing appears in `isaac
turns list --all` for it. A band-declared address that comes back
undeliverable also logs `:hail/undeliverable` (`:band`, `:reason
:no-recipients`) — check `isaac logs server` (or `cli` for an in-process
run) if you expect a delivery that never showed up and the CLI/HTTP
response is out of view.

### Troubleshooting

- **An explicit `--session` value that happens to equal a band name is
  refused, naming both the value and that it's a band.** Deliberate: a
  band name is a selector, not a session, and passing it as `session`
  would otherwise silently address a session that can never exist. Use
  `--band <name>` instead.
- **`hail send --session <typo>` fails with "no session: <name>".** Sessions
  are never created implicitly for an explicit `session` selector, no
  matter what `create` says — use `session-tags`/`crew` with `create
  :if-missing` if you actually want get-or-create.
- **An unknown `--band` name fails with "unknown band: <name>".** Check
  `isaac config validate` or the file name under `config/hail/` — this
  fires before any session matching happens.
- **A hail is refused with plain "no session" (no id named).** That's a
  describe-style match (`session-tags`/`crew`) that found nothing live —
  check the sessions actually carry the tag/crew being asked for.
- **You expect `create :if-missing` to spawn a session immediately and it
  didn't.** Creation happens when **Agent's turn queue** admits the turn
  (on the next tick), not at send time.

## Threading, replies, and the metadata preamble

**What it is.** Every accepted hail carries a **thread id**: its own turn
id if it's the first message in a conversation, or the thread id it
inherits when `:reply-to` names a prior turn. `--reply-to <id>` (or
`:reply-to` in the record) looks that turn up (`isaac.agent`'s turn store)
and reuses its thread id — replying to an id that doesn't exist is refused
with `"turn not found: <id>"`, nothing queued. `--thread-id` sets an
explicit thread id directly, overriding both defaults.

Separately, every hail-submitted turn carries a **metadata preamble** — a
system-level block (not the user-visible input) naming the hail id,
thread, reply-to (when present), submitter session, sending crew, and the
hail's `:data`/`:params` as structured facts. This exists so an autonomous
handoff doesn't have to re-thread that context by hand from the visible
prompt. `:params` are **always** echoed into the preamble as data, even
when a band template already consumed them to build the prompt — they
never silently disappear just because they were also used for rendering.
The preamble deliberately carries only *per-hail* facts, not identity
(there's no "Session:" line) — identity is ambient to the turn, not
something the preamble needs to assert.

**How to change it.** Nothing here is configurable — the preamble's shape
and what threading inherits are both fixed behavior, not a `handbook__configure`
path. What you *can* change is what rides in it: a hail's own `--params`,
`--reply-to`, and `--thread-id`, or a band's declared `data`.

**How to verify.** `isaac hail send ... --edn` (or `--json`) after a real
send prints the accepted record's `:preamble` in full. `isaac turns show
<id>` (`isaac.agent`) shows the same thing for a turn already queued or
finished, including its `:origin.thread-id` / `:origin.reply-to`.

### Troubleshooting

- **`--reply-to <id>` fails with "turn not found."** The id has to name a
  turn that actually exists in Agent's store — a typo or an id from a
  different root fails loudly rather than silently starting a fresh
  thread.
- **A hail's preamble has no "Params" section at all.** That's correct when
  the hail carried no `:params` — the section is only built when there's
  something to report, not printed empty.
- **You expected the preamble to name the target session and it doesn't.**
  Deliberate (isaac-sx4g) — the preamble carries facts about the hail
  itself (thread, reply-to, submitter, data/params), not the ambient
  session/crew identity the turn already knows.

## The hail-send tool

**What it is.** `hail-send` lets a crew dispatch a hail from inside its own
turn — the same one-turn-per-send substrate as the CLI and HTTP route. A
crew must opt in via its tool allow list (`isaac.agent`'s `tools.allow`);
it's never granted implicitly. Arguments are flat, snake_case (`band`,
`session`, `session_tags`, `crew`, `prefer`, `create`, `with_crew`,
`with_model`, `with_effort`, `with_context_mode`, `prompt`, `params`,
`thread_id`, `reply_to`, `idempotency_key`) — the tool builds the internal
`:frequencies` map itself. The dispatched turn's `:origin.from` records the
calling crew as `:crew/<id>`, so a received hail's context always shows
which crew sent it.

An address that can't be resolved is rejected **in-turn**, as a tool error
the model sees immediately, rather than queuing something wrong — in
particular, passing a band name as an explicit `session` argument comes
back naming both the mistake and the fix ("... is a band name, not a
session ... pass band: ..."), and a `session` naming nothing at all comes
back as an ordinary "no session" error. Either way nothing is queued for
the bad call.

**How to change it.**

```
config set crew.cordelia.tools.allow.hail-send
```

(A namespace-glob allow like `hail-send` itself is the whole grant here —
see `isaac.agent`, Tools and directories, for the general allow/deny
mechanics.)

**How to verify.** After a crew calls the tool, `isaac turns show <id>` on
the id the tool returned (or on the thread) shows `:origin.from
:crew/<id>` and everything else a hail carries. A rejected call never
reaches the queue — check the tool's own error text in the transcript
first before looking for a turn that was never created.

### Troubleshooting

- **A crew's turn shows `hail-send` isn't available even though you expect
  it.** Confirm the crew's `tools.allow` actually names it — unlike the
  `episodes` module's recall tools, `hail-send` is never granted
  implicitly by anything else.
- **The model passed a band name as `session` and got an error naming
  "not a session."** That's the tool protecting against a specific
  confusion (isaac-8lhv): a band is a selector, not a session id. Re-call
  with `band: "<name>"` instead of `session: "<name>"`.
- **The tool result is an error and nothing shows up in `turns list`.**
  Expected — an addressing failure is caught before submission, exactly
  like the CLI and HTTP paths; there's nothing to find because nothing was
  queued.

## The HTTP route

**What it is.** `POST /hail/send` is Hail's entry point for producers
outside the process — CI, webhooks, another Isaac install, the `beans`
CLI. It's a normal `isaac.http` route (`isaac.http/route`, one line here;
see that module for auth/principal mechanics generally) with `:scope
:hail/send` declared on the entry, so a caller's principal needs that
scope before the handler even runs. It accepts a JSON or EDN body (by
`Content-Type`) and answers in the same format (unless the request declares
`application/edn`, the response is JSON). A successful send returns `201`
with the accepted turn's `id` and a `Location: /hail/<id>` header; the
body is the same record shape the CLI's `--json`/`--edn` prints.

Request body shape mirrors the CLI/tool record: `frequencies` (object,
same selector keys — `session`/`session_tags` accept a single string or an
array/set, normalized the same way the CLI's repeatable flags are),
`prompt`, `params`, `thread_id`, `reply_to`, `idempotency_key`. A bare
`crew` at the request's top level (instead of inside `frequencies`) is
rejected outright — a common enough mistake to name explicitly rather than
silently misrouting it.

**Scopes.** Holding `hail/send` lets a principal send an ordinary hail
(band or direct). Overriding a **band's own template** by also supplying
`prompt` additionally requires `hail/prompt-override` — the band's
declared prompt is the safe default, and only a principal explicitly
trusted to bypass it can. A direct (non-band) hail's `prompt` is not an
override in this sense — it's required, not optional, so it needs no
extra scope beyond `hail/send`. The legacy server-wide admin token
(`isaac.http`'s `http.auth.token`) still authorizes everything, prompt
overrides included, as a superuser identity (`origin.principal admin`);
every other caller's identity rides the turn as `origin.principal
<name>`, taken from its principal, not asserted by the request body.

**How to change it.** Route registration itself isn't config — declaring
scopes, minting/rotating a principal's token, and its scope set are
`isaac.http`'s job (`isaac http auth mint`/`rotate`, `http.auth.principals`
— see that module's own chapter). There is nothing Hail-specific to
configure about the route beyond the bands it can address (see Bands,
above).

**How to verify.**

```
curl -sS -X POST "$HOST/hail/send" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"frequencies": {"band": "bean-pickup"}, "params": {"n": 1}}'
```

A `201` with an `id` means it queued; check it the same way as any other
hail (`isaac turns show <id>`). `403` with no body detail beyond the
standard auth error means a scope problem — check which scope was missing
(`hail/send` vs. `hail/prompt-override`) against what the principal holds.

### Troubleshooting

- **`400` with `{"error": "missing frequencies"}` or `{"error": "invalid
  crew"}`.** The body's shape is wrong — `frequencies` is required, and
  `crew` belongs inside it, not at the top level.
- **`415` unsupported media type.** Only `application/json` and
  `application/edn` are accepted; set `Content-Type` explicitly.
- **`401` even though the install has no auth configured at all.** A
  presented bearer credential is always checked once presented — a server
  with no `http.auth.token`/principals configured refuses an unplaceable
  credential rather than treating it as anonymous (`isaac.http`). Omit the
  `Authorization` header entirely for a genuinely open install, or
  configure a token/principal that matches what you send.
- **`403` on an otherwise-correct band send.** The principal lacks
  `hail/send` outright, or is overriding a band's prompt without
  `hail/prompt-override` — two different scopes; holding one doesn't imply
  the other.
- **A direct (session-addressed) hail's `prompt` got refused as an
  override.** It shouldn't — a session-direct hail has no band template to
  override, so `prompt` there only ever needs `hail/send`. A 403 there
  means the request actually carries a `band` in `frequencies`, not a bare
  `session`.
- **Nothing was queued after a `403` or `400`.** Both are refused before
  submission — an empty `isaac turns list --all` afterward is expected.
