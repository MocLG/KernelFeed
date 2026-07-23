# LKML

An Android reader for the Linux Kernel Mailing List, backed by the public-inbox archive at
[lore.kernel.org](https://lore.kernel.org/lkml/). Kotlin, Jetpack Compose, Material 3,
offline-first.

Built for the way kernel mail is actually read: threads are trees, most messages are
patches, and the interesting ones are long.

---

## The archive: what actually works

The endpoint contract below was established by probing the live service, not from
documentation. It is the foundation the data layer is built on.

| Purpose | Endpoint | Format |
|---|---|---|
| List catalogue | `/manifest.js.gz` | gzipped JSON, ~400 git epochs → 353 lists |
| Thread feed | `/<list>/`, next page `/<list>/?t=<cursor>` | HTML topic list, ~50–100 threads / 36 KB |
| Search | `/<list>/?q=<query>&o=<offset>` | HTML, 200 hits per page |
| Full thread | `/<list>/<root-message-id>/t.mbox.gz` | gzipped **mboxrd** |
| Single message | `/<list>/<message-id>/raw` | RFC 5322 |
| Newest messages | `/<list>/new.atom` | Atom, 25 entries |

`<list>` is any slug from the manifest (`lkml`, `netdev`, `linux-staging`, …) plus the
virtual aggregate `all`, which is browsable but absent from the manifest and so is
injected by hand.

### The aggregate inbox lags, and cannot be a universal source

`/all/` looks like it should be the one place to fetch any thread from, and it is not.
Measured against live data, the newest thread on 4 of 9 sampled lists returned **404**
from `/all/` while resolving fine on its own list — the aggregate index catches up behind
the per-list ones. But when `/all/` *does* have a thread it returns a **richer** result,
unioning in replies that only went to a cross-posted list (one lkml thread: 172 KB via
`/all/` against 53 KB via `/lkml/`).

So each thread records the list it was discovered in and is fetched from there, with
`/all/` as a fallback for threads whose origin is unknown (search hits) or which that list
no longer carries. Only a 404 advances to the fallback; other failures propagate, since
retrying a timeout against a second path just doubles the wait.

### The User-Agent is load-bearing

lore.kernel.org sits behind Anubis bot protection plus an nginx UA filter, and the two
disagree about who counts as a bot:

| Client UA | Result |
|---|---|
| contains `Mozilla` | Anubis JavaScript proof-of-work page, served as **`200 OK text/html`** |
| `curl/...` | **`403`** from nginx |
| `lkml-app/1.0 (Android; ...)` | served normally on every endpoint |

The failure mode matters more than the rule. A browser-shaped UA does not get an error —
it gets a *successful* response containing an interstitial, which then fails deep inside
the mbox or topic parser as a confusing "parse error". So:

- `UserAgentInterceptor` pins a descriptive, contactable, non-browser UA.
- `BotChallengeInterceptor` peeks at HTML responses and converts an interstitial into a
  typed `AppError.BotChallenge`, so the diagnosis points at the request, not the data.

The app does not attempt to defeat the bot protection: identifying honestly is both what
makes it work and what public-inbox asks of automated clients.

Two other sharp edges, both encoded in tests:

- The plain index ignores `?o=` (it returns page 1 again). Only the `?t=` cursor pages it;
  `?o=` is for search.
- Atom `<id>` elements are `urn:uuid:` values, **not** Message-IDs. The Message-ID has to
  be recovered from the `<link href>` path segment.

---

## Architecture

```
ui/          Compose screens, ViewModels, AnnotatedString rendering
  ↓ (uses)
domain/      Models, parsers (diff, body, thread tree), use cases, repository interface
  ↑ (implements)
data/        Room + Retrofit/OkHttp + mbox ingest
```

### Navigation

The app opens on the **list catalogue**, not on a single hardcoded feed:

```
Lists (catalogue)  →  feed/<slug>  →  thread/<message-id>
Search ────────────────────────────↗
Saved  ────────────────────────────↗
```

The catalogue is ordered pinned → curated → most recently active, with search over all
353 lists. Ordering alphabetically would bury the dozen lists anyone actually opens under
~340 narrow subsystem and CI archives.

Feed membership is a **join table** (`feed_entries`), not a column on the thread. The same
thread genuinely appears in several lists at once — a networking patch is on `netdev`,
`lkml` and `all` — and a single `feedList` column would make those lists fight over the
row, with whichever refreshed last winning and the others losing the thread entirely.

`domain` has no Android dependencies apart from the parsers' complete absence of them,
which is why the diff engine, body segmenter and tree builder are all testable as plain
JVM code.

### Everything observable comes from Room

The single most important structural decision: **`Flow`s are backed by the database, never
by the network.** Network calls are one-shot `suspend` functions whose only job is to
write into Room; the UI updates because the database changed.

```kotlin
fun observeFeed(listSlug: String): Flow<List<ThreadSummary>>   // Room
suspend fun refreshFeed(listSlug: String): Resource<Unit>      // network → Room
```

Consequences that fall out for free:

- A cold start paints the last-seen feed immediately — there is no spinner-first state.
- No code path exists where an unreachable network yields a blank screen.
- A thread being streamed in from its mbox **re-renders progressively** as batches land,
  with no progress plumbing: Room re-emits, the tree rebuilds, the list grows.

`Resource<T>` carries `Loading` / `Success` / `Error` / **`Offline`**. Offline is a
first-class outcome, not an error — "here is your cached data and we could not refresh it"
should be a banner over real content, never an error screen.

### One request per thread, not per message

Opening a thread fetches `t.mbox.gz` once: a complete 14-message thread is about 9 KB
compressed. Fetching messages individually would mean 14 round trips for the same bytes,
and on mobile latency the round trips dominate.

It also makes offline trivial — **the thing already downloaded *is* the offline artefact**.
Saving a thread pins it against eviction rather than triggering a separate download.

`isSaved` (user intent) and `isCached` (cache fact) are separate columns. Eviction only
ever touches `isCached && !isSaved`, which is what makes the offline promise
unconditional.

Three separate lifetimes govern a thread, and conflating them would be a mistake:

| Constant | Value | Meaning |
|---|---|---|
| `READ_CACHE_TTL_MILLIS` | 24 h | how long an opened thread is served without a refetch |
| `SAVED_REFRESH_TTL_MILLIS` | 6 h | when background sync considers a *saved* thread stale |
| `EVICT_AFTER_MILLIS` | 14 days | when an unsaved cached thread is discarded |

Note that none of these control how fast a thread reopens. Because the thread screen
observes Room, cached messages paint immediately regardless; the read TTL only decides
whether a network request is also worth making.

Feed refreshes use insert-then-patch rather than `REPLACE`, so server-owned columns never
clobber client-owned ones — a plain upsert would silently un-save the user's threads on
every refresh.

---

## The parsers

### Diff parsing is count-driven

A mail body is not a well-formed patch file. The patch is embedded in prose, may be
truncated mid-hunk, and the surrounding commentary is full of lines starting with `-`.

`DiffParser` therefore never infers where a hunk ends from line prefixes. It reads the
`@@ -a,b +c,d @@` counts and consumes exactly that many old/new lines:

```
@@ -1,2 +1,2 @@
-old
+new
 ctx
- this is prose, not a deletion    ← counts exhausted; hunk ends here
```

Precedence in `BodyParser` is deliberate, and quotes outrank diffs: a quoted patch
(`> +foo`) is material *being discussed*, not a patch to apply, and rendering it as a live
diff would be wrong.

### Thread trees are defensive

Real data is messier than the RFC implies. `In-Reply-To` is trusted first, then the *last*
resolvable `References` entry (nearest ancestor), because mailers truncate `References`
and some drop `In-Reply-To` entirely. A message whose parent is missing becomes an extra
root instead of vanishing, and reference cycles are broken rather than hung on.

### mbox ingest is streamed and mboxrd-aware

lore serves the **mboxrd** variant, where body lines matching `>*From ` were escaped with
one extra `>`. Not unescaping corrupts quoted text — and since `From ` commonly opens a
quoted reply, it corrupts reply structure too.

Parsing is line-by-line with one message in memory at a time, written to Room in batches
of 40, so a 2,000-message thread costs the same peak memory as a 2-message one. Apache
Mime4J handles RFC 2047 encoded-word headers, quoted-printable/base64, per-part charsets
and nested MIME — the parts where hand-rolled mail parsers break on non-English names.

---

## Rendering

No parsing happens in a composable. Bodies are segmented into `BodyBlock`s by a use case
on `Dispatchers.Default`, and diffs are rendered into `AnnotatedString` off the main thread
via `produceState`, keyed on file + palette so the result survives recomposition and
scrolling.

A diff file becomes **exactly two `AnnotatedString`s** — gutter and code. Emitting one
`Text` per line would mean 4,000 text nodes for a large patch, which no amount of lazy
layout makes cheap; as two strings it is two nodes and one layout pass. They are separate
strings so the line-number gutter stays pinned while long lines pan horizontally.

Rows are padded to a common width so the added/removed backgrounds form unbroken bars
rather than stopping ragged at each line's last character.

**Diff colours sit outside the Material dynamic palette.** Added/removed carry semantics
every developer already knows; re-tinting them from a wallpaper-derived scheme would render
a green-on-red patch as lavender-on-mauve. Material dynamic colour governs the app chrome;
the diff keeps a fixed, high-contrast palette in both light and dark.

Other UI decisions worth naming:

- Message content is monospaced and **not re-wrapped**. Kernel mail is hard-wrapped at
  72–80 columns and full of aligned ASCII — tables, register maps, stack traces. Reflowing
  destroys that; over-wide lines are reachable by panning.
- Tree indentation caps at 8 levels (threads nest 15+ deep); past the cap depth is shown
  numerically so the deepest replies stay readable.
- Collapsing a subtree *removes* its descendants from the flattened list rather than
  hiding them, so collapsing 300 replies actually removes 300 items.
- `LazyColumn` keys are Message-IDs, so each card's internal state (expanded diffs, quote
  toggles) survives collapse and reflow.

---

## Background sync

`SyncWorker` runs every 6 hours on **unmetered** networks only — a saved patch series can
be several megabytes. It refreshes stale saved threads, then evicts unsaved cached threads
older than 14 days. Saved threads are never eligible for eviction.

It deliberately does *not* refresh the feed index: that is cheap to fetch on open, and
doing it in the background would spend battery and the archive's bandwidth on a list the
user may never look at.

---

## Building

```bash
export ANDROID_HOME=$HOME/Android/Sdk    # or set sdk.dir in local.properties
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Requires JDK 17. Gradle's configuration cache is disabled because KSP's task state is not
serialisable against it.

### Tests

36 unit tests, run against **real captured responses** from lore.kernel.org
(`app/src/test/resources/`) rather than synthetic fixtures — hand-written samples tend to
encode the parser's own assumptions, while production data carries the details that
actually break parsers: public-inbox's line-broken `href` attributes, mboxrd escaping,
DKIM headers longer than most parsers' default limits.

There is also an opt-in live smoke test that exercises the whole pipeline against the real
archive:

```bash
./gradlew testDebugUnitTest -Dlkml.live=true --tests '*LiveSmokeTest*'
```

It is excluded by default: it depends on a third-party host being up, so a CI failure
there would say nothing about the code. Run it when a parser changes or a lore-side change
is suspected, to confirm the captured fixtures still match what the archive serves.

---

## Status

List catalogue, per-list feeds, search, thread tree, patch rendering, offline saving and
background sync are implemented and build clean (`assembleDebug`, `assembleRelease` with
R8, `lintDebug` with zero errors). The full pipeline has been verified end-to-end against
production lore.kernel.org, across `all`, `lkml`, `linux-staging`, `netdev` and `bpf`.

Not yet built: reply/compose (the archive is read-only; sending would need SMTP), and
instrumented UI tests. **The app has not been run on a device** — there is no emulator in
this environment, so the Compose layer is verified by compilation and lint only.
