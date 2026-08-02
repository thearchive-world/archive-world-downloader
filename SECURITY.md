# Security Policy

Archive World Downloader is a client-side Minecraft mod. It runs only on your own
machine, has no server component, and opens no listening ports. The attack surface
is narrow, but it is not empty. This policy states that surface honestly and explains
how to report a problem privately.

For general usage and configuration questions (not security reports), see the
documentation site at <https://wdl.docs.thearchive.world/>.

## Reporting a vulnerability

Please report suspected vulnerabilities privately. Do not open a public issue for a
security problem.

Use GitHub's private vulnerability reporting: open the repository's **Security** tab
and choose **Report a vulnerability**, or go straight to
<https://github.com/thearchive-world/archive-world-downloader/security/advisories/new>.
That opens a private advisory visible only to you and the maintainer.

Where you can, include:

- the mod version and the Minecraft version (the `+` tag in the jar file name), and
  the loader (Fabric or NeoForge),
- what the problem is and what an attacker could achieve with it,
- steps or a small proof of concept to reproduce it,
- any relevant excerpt from `logs/latest.log`.

You will get an acknowledgement as soon as the maintainer can review the report. This
is a solo-maintained project, so please allow reasonable time for a fix before any
public disclosure. Fixes ship in a new release, and the advisory is published once a
fix is available.

## Supported versions

Security fixes are made against the most recent release for each Minecraft version the
project currently ships. The compatibility matrix in the README lists those versions,
and the Modrinth and CurseForge listings are the live source of truth for exactly which
builds are published. Older builds and pre-release snapshots are not supported: update
to the latest release before reporting.

## What the attack surface actually is

The mod rebuilds a singleplayer save out of the data your own client already receives
while you play on a server. Handling that data means handling some untrusted,
server-controlled input. The honest surface is:

- **Parsing untrusted server data.** The mod reads the packets and NBT the server sends
  your client and turns them into save files. Malformed or hostile packet or NBT data is
  untrusted input to that parsing and writing path.
- **Writing files from server-influenced names.** Save, region, map, and entity file
  names can derive from server-controlled identifiers such as world and map names.
  Path traversal is mitigated by sanitizing and escaping those names before they reach
  the filesystem. A way around that escaping would be a valid report.
- **The outbound update check.** With the update check enabled, the mod makes one small
  anonymous request to the Modrinth release index at startup to see whether a newer
  version exists. It sends no world data and nothing identifying, and it can be turned
  off in settings. That single request is the mod's only network activity of its own.

## What is not in scope

- **No server-side component.** The mod is not installed on and does not run on the
  server. It cannot be used to attack a server through this code.
- **No world data leaves your machine.** Everything the mod saves stays on your own disk.
