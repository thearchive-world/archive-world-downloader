# Contributing

Thanks for your interest in Archive World Downloader. This guide covers how to build the mod, the
conventions the code follows, and how to get a change reviewed. Following it keeps a contribution
quick to review and consistent with the rest of the tree. Please keep discussion respectful and
constructive.

For anything beyond a small fix, open an issue to discuss the change first, so the design direction
is agreed before effort goes in.

## Prerequisites

Install a **Temurin** JDK (Eclipse Adoptium's OpenJDK distribution) matching this branch's target
Minecraft version. The required major version is the `java_version` property in `gradle.properties`
(JDK 21 on this branch). Automatic toolchain provisioning is deliberately off
(`org.gradle.java.installations.auto-download=false`, and no download repository is configured), so
the build always compiles with a JDK you installed and trust rather than silently fetching one. It
uses a JDK it detects locally; if no matching Temurin JDK is on your machine, the build stops with a
toolchain-resolution error: install one and rerun.

Each Minecraft version lives on its own branch and needs the JDK that version targets: the legacy
bands build on Java 8, and the newest bands (Minecraft 26.x) on Java 25. Read `java_version` for the
branch you are building.

The Gradle toolchain pins only the major version and the vendor (Adoptium), not the patch. For a
byte-identical rebuild of a published jar, install the exact patch that release was built with,
recorded in that release's GitHub notes.

## Building

```console
./gradlew build
```

This compiles the three subprojects (`common`, `fabric`, `neoforge`), runs the checks (Checkstyle,
Spotless, NullAway, the license-header check, and the `core` invariants), and produces the loader
jars under `fabric/build/libs` and `neoforge/build/libs`.

## Running a development client

Each loader exposes a dev client run that launches Minecraft with the mod loaded, for manual
testing:

```console
./gradlew :fabric:runClient
./gradlew :neoforge:runClient
```

## Developer setup

Any editor works; the build is driven by Gradle, not an IDE. IntelliJ IDEA is the reference setup.

**IntelliJ IDEA:** open the repository folder and trust the Gradle project; it imports `common`,
`fabric`, and `neoforge`. Use a recent version, since older IntelliJ may fail to sync Gradle 9.6 or
not honor its configuration cache. Point the Gradle JVM at the Temurin JDK this branch targets (the
`java_version` in `gradle.properties`, as the Prerequisites section covers). Under Settings, Build,
Execution, Deployment, Build Tools, Gradle, set both "Build and run using" and "Run tests using" to
Gradle, so the configuration cache and the loader classpath are honored; the "Run tests using"
choice matters for the NeoForge unit-test run, which boots the mod loader and behaves differently
under IntelliJ's own test runner. After sync, the dev-client runs for each loader and the Fabric
`clientGameTest` run appear. Run `./gradlew genSources` once for readable Fabric Minecraft sources
(ModDevGradle attaches NeoForge's on import). EditorConfig is bundled and on, so IntelliJ takes
indentation and import layout from `.editorconfig`; the full formatter is Spotless, so run
`./gradlew spotlessApply` to match the style gate.

**Recommended plugins (optional):** CheckStyle-IDEA, pointed at `config/checkstyle/checkstyle.xml`,
shows the Checkstyle rules live; the rules are enforced in CI regardless. Minecraft Development adds
`fabric.mod.json` and `neoforge.mods.toml` awareness and translation-key completion with missing
and unused key checks, which pairs with the `en_us.json` key parity the build enforces; its Mixin
features are unused here.

**Other IDEs:** Eclipse and Visual Studio Code import the same Gradle build and get their run
configurations from Loom and ModDevGradle (ModDevGradle attaches NeoForge sources on import). Style
and lint are enforced by `./gradlew build` in any editor; to match the formatter locally run
`./gradlew spotlessApply`. Visual Studio Code needs Microsoft's Extension Pack for Java. The license
header is the same two lines in every editor, and `./gradlew build` fails if a Java file is missing
it.

## Project layout

The mod is three Gradle subprojects:

- `common` holds the shared, loader-independent code, which is most of the mod.
- `fabric` and `neoforge` are the thin per-loader entry points.

Inside `common`, one internal boundary is load-bearing and enforced by the build:

- `core/` is Minecraft-free and must compile on Java 8. It uses no `net.minecraft.*` import and none
  of `record`, `var`, `sealed`, switch expressions, or pattern-matching `instanceof`. The
  `checkCoreImports` and `checkCoreJava8` tasks fail the build if either rule is broken.
- Everything above `core/` may name Minecraft types and use modern Java freely.

If a change touches `core/`, keep it Minecraft-free and Java-8-clean, or move the Minecraft-facing
part up a layer.

The mod avoids Mixins. The small amount of extra access it needs comes from a read-only access
widener (`fabric/src/main/resources/wdl.accesswidener`) on Fabric and an access transformer
(`neoforge/src/main/resources/META-INF/accesstransformer.cfg`) on NeoForge, not from bytecode
patching. Adding a Mixin has a high bar: prefer widening access or an existing seam, and raise the
case in an issue first.

## Code style

Style is enforced mechanically. `./gradlew build` runs Checkstyle (layout and imports), Spotless
(formatting), NullAway (nullness), and a license-header check, so a nonconforming change cannot
land. The config in the
repository is the source of truth: `.editorconfig` at the root, `config/checkstyle/`, and
`config/spotless/`. If a formatting check fails, `./gradlew spotlessApply` reformats the code.

A few conventions the tools do not check:

- **Naming and prose:** match the surrounding code and the Mojang-mapping naming style, and use
  American English.
- **Nullness:** non-null is the default, set per package by `@NullMarked` (JSpecify); annotate only
  the exceptions `@Nullable`. Do not use `@NotNull`, `@Nonnull`, or `@NonNull`.
- **Comments:** keep them minimal and explain why, not what.

## Commits

Pull requests are squash-merged, so your pull request title becomes the commit subject that lands.
Give the title a Conventional Commits prefix (<https://www.conventionalcommits.org/en/v1.0.0/>): one
of `build`, `ci`, `docs`, `feat`, `fix`, `perf`, `refactor`, `style`, or `test`, followed by a short
summary. You do not need to polish the individual commits inside the pull request; keep them clear
enough to review, and squash-merge keeps you as the author of the merged commit.

## Pull requests

1. Run `./gradlew build` and make sure it passes before opening the pull request. Continuous
   integration runs the same gate on every push and pull request, and it is the required check to
   merge. Three heavier tiers are advisory: a headless-client capture test, a mutation-testing run,
   and a byte-reproducibility double build. Each runs only when the pull request touches something
   that can move its result, so most pull requests skip some of them.
2. Base the pull request on the `dev` branch, which is the active development branch.
3. Keep it focused on one change. A smaller, single-purpose pull request is faster to review.
4. Reference any issue it resolves with `Closes #NN` in the description.
5. Cover new behavior with a test where you can. A change to the capture path also needs manual
   in-game verification, since the headless capture test is advisory rather than a full gate.

## Translations

Translations are welcome. The language files live in
`common/src/main/resources/assets/wdl/lang/`, one `<locale>.json` per language, and `en_us.json` is
the source of truth for the set of keys and their order.

The simplest way to add or update a locale is a pull request that edits the matching
`<locale>.json` (copy `en_us.json` to start a new one). Keep every key that `en_us.json` has, in the
same order, and keep the format placeholders intact: the `%s`, `%1$s`, and `\n` in a string must
survive translation, only the surrounding words change. The test suite checks this parity and
placeholder fidelity, so a submission that drops a key or breaks a placeholder fails the build. Leave
a key untranslated (equal to the English) rather than deleting it; Minecraft falls back to English
for any missing key.

That is what the build can check. What it cannot check is whether the words read like Minecraft,
which is what the review turns on: your language's own Minecraft wording, casing, register, and form
of address. The standard is written up at
<https://wdl.docs.thearchive.world/contributing/translation-style/>; read it before you start.

If you would rather not open a pull request, use the **Translation issue** template to report wrong,
missing, or outdated text, and the change can be applied for you.

## Writing documentation

The user documentation lives at <https://wdl.docs.thearchive.world/> and is maintained in its own
repository; this repository carries only the developer-facing files (this guide, the README, and the
security policy). When writing or reviewing documentation, two conventions apply:

- **Pick the page type deliberately.** The site is organized along Diataxis
  (<https://diataxis.fr/>): a tutorial teaches a first walk-through, a how-to guide solves one
  concrete task, a reference page states facts that mirror the code, and an explanation gives
  background. Write each page for one reader need and do not mix the types; a how-to that drifts
  into background reads better as two pages.
- **Lead with the GUI route.** Instructions give the pause-menu, mod-list, or settings-screen path
  first; chat commands and log checks come after, as the alternative for advanced readers.

**The config reference tracks the schema.** The three config reference pages (Interface, World, and
Download) document every option one-to-one against `ConfigSchema`. A change that touches
`ConfigSchema`, `SettingsLayout`, or a `wdl.settings.*` translation key is complete only when the
matching rows on those pages are updated, or the pull request notes the needed edit so it can be
applied with the merge.

## Reporting issues

Open an issue from the [issue templates](https://github.com/thearchive-world/archive-world-downloader/issues/new/choose)
and fill in the form. The bug and crash forms ask for your Minecraft version, the loader (Fabric or
NeoForge) and its version, the mod version, and steps to reproduce, so the report arrives actionable.
For a crash, attach the crash report or the relevant part of `logs/latest.log`.

Do not open a public issue for a security vulnerability. Report it privately instead, following the
[security policy](SECURITY.md).

## License

Archive World Downloader is licensed under LGPL-3.0-or-later. By contributing, you agree that your
contributions are licensed under that same license (the GitHub inbound = outbound default). There is
no CLA.

Every Java source file carries the same two-line header; a new file you add carries it verbatim:

```java
// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later
```

Authorship is recorded in the git history, not in the header, so there is no per-name line to add.
You author under your own identity, and squash-merge keeps you as the author of the merged commit.

The mod icon is a vector drawing, `art/logo.svg`. To change it, edit that file and re-export
`common/src/main/resources/assets/wdl/icon.png` at 512x512; do not paint over the PNG, or the two
drift apart and the source stops being the source.
