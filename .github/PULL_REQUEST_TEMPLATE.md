<!-- Your pull request title becomes the squash-merge commit subject. Give it a Conventional
     Commits prefix (build, ci, docs, feat, fix, perf, refactor, style, test) and a short
     summary, for example "fix: stop the HUD flickering while saving". -->

## What this changes

<!-- What does this pull request do, and why? Lead with the reason. -->

## Related issues

<!-- For example: Closes #123. Remove this section if there are none. -->

## Checklist

- [ ] `./gradlew build` passes locally (Checkstyle, Spotless, NullAway, and the `core` invariants).
- [ ] The change is focused on one thing.
- [ ] Code touching `core/` stays Minecraft-free and Java-8-clean.
- [ ] New behavior carries a test, or I have said why it cannot.
- [ ] A capture-path change was verified in-game (the headless capture test is advisory, not a full gate).
- [ ] New source files carry the two-line SPDX header (see CONTRIBUTING).

<!-- By submitting this pull request you agree that your contribution is licensed under the
     project's LGPL-3.0-or-later license (inbound = outbound). -->
