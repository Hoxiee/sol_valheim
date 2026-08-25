# Releasing

How to cut a release of SOL: Valheim Continued. Three things here bite if you skip a step:
jar names collide across targets, CI triggers on the *release* (not the tag), and the
Modrinth/CurseForge IDs still belong to the original author.

## 1. Version

Bump `mod_version` in `gradle.properties` and commit (`build: ...`).

Scheme: prereleases are `1.0.0-pre.N`, stable releases drop the suffix (`1.0.0`). On GitHub,
mark prerelease builds with the *prerelease* flag; on Modrinth/CurseForge they go to the *Beta*
channel once publishing is set up.

## 2. Build every target

```sh
./gradlew assemble -PmcVer=1.19.2   # fabric + forge
./gradlew assemble -PmcVer=1.20.1   # fabric + forge
./gradlew assemble -PmcVer=1.21.1   # fabric + neoforge
```

Requires JDK 17 for the 1.19.2/1.20.1 targets; the 1.21.1 target needs JDK 21.

**Copy the jars aside after each target.** Output names carry no MC version
(`sol_valheim-<version>.jar` from `<platform>/build/libs/`), so every build overwrites the
previous one. Stage them renamed:

```
sol_valheim-fabric-mc1.19.2-<version>.jar
sol_valheim-forge-mc1.19.2-<version>.jar
...
```

Take only the plain jars - skip `-dev-shadow` and `-sources`.

## 3. Tag and push

```sh
git tag v<version>
git push && git push origin v<version>
```

## 4. Create the release

This - not the tag push - is what fires the multiversion CI:

```sh
gh release create v<version> <staging-dir>/*.jar \
  --prerelease --title "v<version>" --notes-file notes.md
```

Actions then rebuilds all three versions; the publish steps skip themselves while no
Modrinth/CurseForge tokens are configured.

## 5. Publishing to Modrinth/CurseForge (not yet)

Before the first published upload:

- **Replace the inherited project IDs.** `curseforge_id` and `modrinth_id` in `gradle.properties`
  point at the original author's projects. Publishing against them would push these builds to
  someone else's mod page.
- Add the `MODRINTH_TOKEN` and `CF_API_TOKEN` secrets to the repo settings.
- From then on, creating a release uploads the jars automatically.
