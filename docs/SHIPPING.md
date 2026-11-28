# Shipping: worktrees, the ship loop, CI, CurseForge, wiki, fleet

*Moved out of the root `CLAUDE.md` on 2026-11-28 (verbatim) so it is not loaded every session.*
Read this before cherry-picking to a version branch, cutting a release, publishing, or deploying jars.
The release recipe itself (gates, bump, tag, CurseForge, fleet, wiki, WEBSITE.md) is visible in every
release entry of `docs/RELEASES.md`.

## Worktrees and the ship loop

- **Build the version branches in their worktrees, don't switch branches here.** `master` is 1.21.11;
  `mc1.20.1`, `mc1.21.1`, `mc26.1`, `mc26.2` and `mc26.3` are checked out permanently at
  `../CityWorld-ReForged-worktrees/{mc1.20.1,mc1.21.1,mc26.1,mc26.2,mc26.3}`, each with a `tools` symlink back to this
  checkout's JDKs (`tools/` is git-ignored, so a worktree has none of its own). Build with an explicit
  `JAVA_HOME` — **1.20.x needs JDK 17, the 1.21 lines need JDK 21, 26.1+ needs JDK 25**. A new Minecraft line is a worktree
  branched from the previous one plus `gradle.properties`/MDG bumps, then compile-fix against the
  decompiled sources; **26.3 was not a one-liner** (PORTING.md "26.3 port — what actually moved": the
  density engine, `ConfiguredFeature`, `buildTerrain`, concurrent registry loading, loot-table schema). `compat/Material.java` is generated per
  branch: a cherry-pick that conflicts on it is resolved by taking the branch's copy and re-running
  `scripts/gen_material.py` there (the template edits live in the script):

  ```bash
  cd ../CityWorld-ReForged-worktrees/mc26.2
  JAVA_HOME="$PWD/tools/jdk25" PATH="$PWD/tools/jdk25/bin:$PATH" ./gradlew build
  ```

  **Why, and it is not just tidiness:** the owner often has a second Claude session working in this
  repo (the sablecraft.co.uk website one). Switching branches in the shared checkout rewrites its
  files underneath it (when `WEBSITE.md` was tracked on master only, a switch tried to delete it and
  aborted — or worse, landed mid-edit). `WEBSITE.md` is now **git-ignored and local-only**: it is the
  note-passing file between this session and the website one, so read and write it freely, never
  commit it. Worktrees keep `master`'s tree still. They share
  the Gradle cache, so a build there is no slower after the first.
- **The ship loop** (used ~30 times in the furniture arc, every trap below hit at least once):
  commit on master → `git cherry-pick <EXPLICIT SHA>` in the worktree (**never `$(git rev-parse
  HEAD)` in a chain — it resolves to the worktree's own HEAD**; hit six times) → **one commit per
  cherry-pick invocation** (multi-commit picks stall the sequencer) → **verify `git log` shows your
  sha before building** (a `| tail -1` once masked a conflict through three "successful" deploys) →
  PALETTES/PORTING/CHANGELOG conflicts resolve `--ours` (docs live on master) → build → deploy →
  `DEPLOYED-$(git log --format=%h -1)` stamp → selftest. F3 shows a jar mtime stamp in-game, so
  "is the game on the new jar" is checkable at a glance.

## Pick traps (loot dialects, cave toggles, conflict markers)

**Traps this arc paid for, each once:** (5.14.0)
- **`modern.json`/`apocalypse.json` name every cave toggle explicitly**, so a style default in
  `styleDefaults` never reaches a preset world; a new Terrain toggle goes in both files. Terrain's codec
  is at its 16-field cap.
- **26.3 has its own loot dialect** (typed rolls, `modifier` not `functions`); a table cherry-picked in the
  1.21 form stops its server at registry load. **1.20.1 keeps `loot_tables/`, `tags/blocks/`, `tags/items/`
  and the `name` key**, and git's rename detection carries master's `value` key straight into them: grep
  after any loot pick.
- **A conflict resolver that asserts before writing leaves the markers in place**, and `git add -A` then
  commits them. Grep for `<<<<` after every resolved pick.

## Nothing in the jar may stop a server

- **⚠ Nothing in the shipped jar may be able to stop a server — not even behind a developer flag.**
  CurseForge **rejected 5.7.0 and 5.8.0**: "Please remove any function that shuts the Minecraft server
  down." The self-test harness and the chunk probe each ended in `server.halt(false)`, dormant unless
  `-Dcityworld.selftest=true` / `-Dcityworld.probe=` was set — but a reviewer greps the shipped bytecode,
  not the flag guarding it, and they are right to. `v5.8.1` removed both: ending a headless run now
  belongs to the script that starts it (the harness logs `SELFTEST: complete`, `scripts/selftest.sh`
  waits for it and kills the run's **process group** — job control, never a `pkill` pattern, and never
  the gradle pid alone or you orphan a server that holds port 25599 into the *next* run).
  Every tag from 5.5.0 carried the same two calls and passed review: that is **volunteer moderators with
  differing thoroughness, not a rule that changed**, so *a past approval is never evidence that something
  is allowed*. And verify a claim like this **against bytecode with a detector proved on a
  known-positive first** (`javap -p -c`, grep `\.halt:|System\.exit:`; a real 5.8.0 jar shows 4 hits —
  **`build/libs/cityworld-5.8.0+mc26.1.2.jar` from Sep 14; the 1.21.11 "5.8.0" jar there was rebuilt after
  the fix and reads 0, so it is NOT a control** — 5.8.1+ none; scan a whole jar with one `javap -cp . <all
  classes>`, not one javap per class, which takes over ten minutes). A `strings`-based check reported 0 for everything — including methods that were certainly
  there — because constant-pool entries sit adjacent. **Never trust a zero from a detector that has never
  produced a positive.**

## Wiki, fleet, CI and publish scripts

- **⚠ Every release updates the wiki: `scripts/gen-wiki.sh --push`** (owner, 2026-10-26: *"fold 'update the wiki'
  into the rollout process"*). The wiki is GENERATED from `CURSEFORGE-CONFIGURATION.md`, `CURSEFORGE-COMMANDS.md`,
  `PALETTES.md`, `LOOT.md`, `BIOMES.md` (biome mods and biome datapacks, 6.11) and `NODES.md`. It had gone a month and fifteen releases stale, with no loot page and
  none of 5.19–6.8's settings, when a user was about to be pointed at it. So the release recipe is: user-facing
  changes go into those docs in the release's docs commit (a new setting -> its row in CURSEFORGE-CONFIGURATION.md;
  a new or changed loot table -> LOOT.md, and `scripts/gen_style_loot.py`/`gen_shop_loot.py` if generated), then
  after the tag `scripts/gen-wiki.sh` (dry run: read the diff) and `--push`, and tell the website session (WEBSITE.md)
  what changed. A release with "nothing to publish" from gen-wiki is fine; a release that added a setting is not.
- **Fleet deploy: `scripts/deploy-fleet.sh`** (`--dry-run` first). Twelve CurseForge instances carry a
  CityWorld jar — five on 1.21.11 (`CityWork-ReForged`, `MobHealth - Forge`, `Neoforge 1.21.11 - sci
  fi/wasteland`, `Standards`), `26.1.2`, three on 26.2 (`26.2`, `26.2.test`, `BoP+Cityworld`), `26.3`, `1.21.1`
  and **`1.20.1  Forge`** (⚠ TWO spaces in that folder name — `--only` matches the exact basename, so
  `--only 1.20.1` silently matches nothing and reports "no instances carry CityWorld"). The
  fleet is whatever `Instances/*/mods` already holds a `cityworld-*.jar` or `DEPLOYED-*` stamp, so a
  brand-new instance is invisible until one jar is copied in by hand; the
  script reads each instance's Minecraft version from `minecraftinstance.json`, picks the newest
  built jar for it across master + the five worktrees (`--version X.Y.Z` for a release, `--build` to
  build them all first), and stamps `DEPLOYED-<sha|vX.Y.Z>` (vX.Y.Z when the jar's Build-Commit is
  the tag or a "Bump to X.Y.Z" commit). A running game locks its jar ("Permission denied") — the
  script reports SKIPPED and carries on; rerun for that one after the game is closed.

- **⚠ The workflows live ONLY on `master`, deliberately — so no push to a version branch ever runs CI.**
  Every branch used to carry its own copies, cut at different times and never synced, and they had
  drifted badly: `mc26.1` fired on its own pushes while `mc1.20.1` and `mc1.21.1` did not, `mc26.2` and
  `mc26.3` had no workflow at all, and the version branches' `curseforge.yml`/`modrinth.yml` were still
  the pre-fix copies that hardcoded the NeoForge loader. Deleted from every version branch on
  2026-09-21 (owner's call: *"cant drift if they dont exist"*).
  **The gate is a `master` push**, whose matrix checks out each branch's head (`actions/checkout` with
  `ref: ${{ matrix.branch }}`) — so coverage is unchanged. **Push the version branch FIRST, then
  master**, or the gate tests a tree you are not releasing. Note `paths-ignore: ['**.md', …]`: a
  docs-only commit deliberately fires nothing.
- **The publish scripts key their loader off the Minecraft version** (`1.20.* -> Forge/forge/Java 17`,
  else NeoForge). Both once hardcoded NeoForge, which was invisible until the first non-NeoForge jar.
  **Before publishing a jar of a new KIND** (new loader, Java, or channel), read the uploader for
  values it *assumes* rather than derives — and check the workflow log's
  `Minecraft <v> = <id>, <Loader> = <id>` line, because that lookup needs the API token and cannot be
  tested locally.

## The upstream owner's setup (moved out of CLAUDE.md, 2026-11-28)

These describe Sablednah's machine and release process. This fork (bnair, 1.21.1 only, not published) does
not use them, but they are kept so nothing is lost.

- **Six lines ship upstream**: 1.20.1 (MinecraftForge 47, Java 17, legacyforge), 1.21.1, 1.21.11 (NeoForge
  21.11.42 on master), 26.1, 26.2, 26.3 (Java 25). JDKs lived in the root `./tools/` (`jdk17`, `jdk21`,
  `jdk25`), reached from each worktree through a `tools` symlink.
- `./deploy.sh` builds and copies the jar into the owner's CurseForge test instance (`CityWork-ReForged`);
  it sets `JAVA_HOME` itself. Override with `CITYWORLD_INSTANCE=/path/to/instance ./deploy.sh`.
- **Add-on jars never go on CityWorld's CurseForge project** (they have their own). Modrinth rejected the
  projects (rule 6.1): do not suggest resubmitting.
- **Every release updates the wiki** (`scripts/gen-wiki.sh`, dry run, then `--push`) and the owner pastes
  `CURSEFORGE.md` into the CurseForge page.
- **Push the version branch first, then master** (CI lives only on master and tests each branch's head).
- Reference port: `../MobHealth-Forge` (on the owner's machine), a completed NeoForge port of another Bukkit
  plugin by the same author — the pattern for Gradle setup, `mods.toml` template, `ModConfigSpec` config,
  Brigadier commands and permissions (no worldgen examples).
