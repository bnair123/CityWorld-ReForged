# The 1.20.1 MinecraftForge line

*Moved out of the root `CLAUDE.md` on 2026-11-28 (verbatim) so it is not loaded every session.*
Read this before building, fixing or shipping anything on `mc1.20.1` (Forge 47, Java 17, legacyforge).

- Versions/metadata live in `gradle.properties` and expand into
  `src/main/templates/META-INF/neoforge.mods.toml` at build time — **edit the template and
  gradle.properties, never a generated `mods.toml`**. On the **1.20.1 Forge** line that template is
  `META-INF/mods.toml` instead, and its schema differs: `mandatory=true/false`, not `type="required"`.

- **⚠ The 1.20.1 Forge line breaks five rules the NeoForge lines let you assume** (full account in
  PORTING.md "1.20.1 **Forge**"; released as v5.11.0):
  1. **`pack.mcmeta` is MANDATORY.** Forge injects no pack metadata, so without that file Minecraft
     discards the **entire mod datapack** — every tag empty, no world preset, no dimension. NeoForge
     synthesises it, so no other branch ships one. One missing file, 21 self-test failures.
  2. **Ship `build/libs`, never `build/devlibs`.** legacyforge writes **same-named jars** to both and
     only `build/libs` is reobfuscated (check the SRG count, not the filename). The dev-mapped one
     installs and loads fine, then crashes at every access-transformed call site.
  3. **Verify an override of a vanilla method by its SRG name**, not the readable one —
     `getScrollbarPosition` ships as `m_5756_`. Grepping the readable name finds nothing and looks
     exactly like "the fix never made it into the build". Map it via
     `build/moddev/artifacts/intermediateToNamed.srg`, and `javap -p -cp .` — the `-cp .` is not optional.
  4. **`scripts/selftest.sh` writes `run/eula.txt` itself.** NeoForge's dev `runServer` accepts the
     EULA; the legacyforge path does not, and a clean checkout has no `run/` at all.
  5. **The `@Mod` class needs a no-arg constructor**; FML injects nothing. Get the bus and container
     from `FMLJavaModLoadingContext.get()` / `ModLoadingContext.get()`.
