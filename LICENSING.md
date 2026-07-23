# Licensing

KernelFeed is **dual-licensed**. You may use it under either:

1. the **GNU General Public License, version 3** (see [`LICENSE`](LICENSE)) — free of
   charge, with the obligations that licence imposes; or
2. a **commercial licence** from the copyright holder, which removes those obligations.

Copyright © 2026 Luka Gejak. All rights reserved.

---

## Option 1 — GPL v3 (free)

You may use, study, modify and redistribute this software under the GPL v3. In return,
the licence requires broadly that:

- **Derivative works must also be GPL v3.** If you distribute a modified version, or a
  larger work that incorporates this code, that whole work must be released under the
  GPL v3.
- **You must provide source.** Anyone you distribute a binary to is entitled to the
  corresponding source code, including your modifications.
- **Notices must be preserved.** Copyright and licence notices stay intact.
- **No warranty.** The software is provided as-is; see sections 15–17 of the licence.

[`LICENSE`](LICENSE) is the authoritative text and is reproduced verbatim. Where anything
in this file appears to conflict with it, the licence text governs.

## Option 2 — Commercial licence (paid)

If the GPL's obligations do not work for you, a commercial licence is available. It is
intended for people who want to use this code **without** having to release their own
source.

You likely want a commercial licence if you intend to:

- ship a **closed-source** product that incorporates this code, in whole or in part;
- distribute a modified version without publishing your modifications;
- redistribute under terms of your own choosing, including sub-licensing to your customers;
- distribute through a channel whose terms are difficult to reconcile with the GPL — the
  Apple App Store is the well-known example, since its usage rules conflict with the GPL's
  prohibition on imposing further restrictions;
- obtain a warranty, an indemnity, or a support commitment, none of which the GPL provides.

**To enquire, contact Luka Gejak at [lukagejak5@gmail.com](mailto:lukagejak5@gmail.com).**
Please describe how you intend to use the software so the terms and price can be scoped.

### Why this is possible

Dual licensing only works if one party owns all the rights. Every contribution to this
project is assigned to the copyright holder under the agreement in
[`CONTRIBUTING.md`](CONTRIBUTING.md), which keeps the ownership undivided and makes the
commercial option available. This is the same arrangement used by projects such as Qt and
MySQL.

---

## Third-party dependencies

This project links against the libraries below. All are compatible with distribution
under the GPL v3, which is *why* GPL v3 was chosen rather than GPL v2 — Apache 2.0 is
incompatible with GPL v2 but explicitly compatible with GPL v3 (one-way: Apache-licensed
code may be incorporated into a GPL v3 work).

| Dependency | Licence | Note |
|---|---|---|
| AndroidX (Core, Lifecycle, Activity, Compose, Navigation, Room, WorkManager, DataStore) | Apache 2.0 | |
| Kotlin stdlib, Coroutines, Serialization | Apache 2.0 | |
| Dagger / Hilt | Apache 2.0 | |
| Retrofit, OkHttp | Apache 2.0 | |
| Apache James Mime4J | Apache 2.0 | |
| `com.android.tools:desugar_jdk_libs` | GPL v2 **with the Classpath Exception** | Derived from OpenJDK. The Classpath Exception explicitly permits linking with independent modules under other licences, so it does not impose GPL v2 terms on this project. |

Licences were verified against the published POM metadata and jar `META-INF` contents of
the exact versions pinned in `gradle/libs.versions.toml`, not from recollection. Re-check
if you change or add a dependency — a new GPL-incompatible dependency (anything
proprietary, or SSPL/BUSL-style source-available terms) would break **both** options
above, not just the commercial one.

## Data and content

This app reads the public mailing-list archives at
[lore.kernel.org](https://lore.kernel.org/). Those messages are the work of their
respective authors and are **not** covered by this project's licence. Nothing here grants
any rights over archived mail, the Linux kernel, or its trademarks.

*Linux* is a registered trademark of Linus Torvalds. KernelFeed is an independent project,
not affiliated with or endorsed by the Linux Foundation or kernel.org.
