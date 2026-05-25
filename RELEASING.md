# Releasing

This document is the operator checklist for cutting a release. The
mechanical work lives in [`.github/workflows/release.yml`](.github/workflows/release.yml).

## Prerequisites (one-time)

GitHub repository secrets:

| Secret | Source |
|---|---|
| `OSSRH_USERNAME` | Sonatype Central user token name |
| `OSSRH_PASSWORD` | Sonatype Central user token password |
| `GPG_SIGNING_KEY` | ASCII-armored PGP private key, in-memory format |
| `GPG_SIGNING_PASSWORD` | PGP key passphrase |

Local Maven settings (only if you publish from a developer machine
rather than CI) — add a `central` server entry to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>...</username>  <!-- Sonatype Central user token name -->
      <password>...</password>  <!-- Sonatype Central user token password -->
    </server>
  </servers>
</settings>
```

Export the GPG passphrase as `MAVEN_GPG_PASSPHRASE` so `maven-gpg-plugin`
picks it up without an interactive prompt.

The PGP key must be uploaded to a public keyserver (e.g.
`keys.openpgp.org`) so Sonatype can verify signatures.

## Pre-release checks

Run locally before tagging:

```bash
mvn -B clean verify                            # full reactor + tests must be green
mvn -B -pl examples/submit-and-stream -am \
  -DskipTests exec:java                        # spot-check one example
mvn -B -pl arcp-core -am -DskipTests install   # POM diff against the previous
                                               # release should be small
```

Then sanity-check:

- [ ] [`CHANGELOG.md`](CHANGELOG.md) has a section for the new version.
- [ ] [`CONFORMANCE.md`](CONFORMANCE.md) test counts match the actual
      `mvn test` output.
- [ ] [`README.md`](README.md) version coordinates match the version you
      are about to cut (Maven + Gradle snippets).
- [ ] All 10 examples print `OK ...` on a fresh clone.
- [ ] `make -C docs/diagrams` produces no diff against the committed SVGs.

## Cutting the release

1. Visit **Actions → release** in the GitHub UI.
2. Click **Run workflow**.
3. Enter the version (e.g. `1.0.1`) — no leading `v`.
4. Leave **dry_run** unchecked for a real release.
5. Confirm.

The workflow:

1. Runs `mvn versions:set` to stamp the reactor with the release version.
2. Runs `mvn -Prelease verify` — must be green.
3. Runs `mvn -Prelease -DskipTests deploy` — every library module's
   `central-publishing-maven-plugin` uploads the signed artifacts and
   `autoPublish=true` releases them straight from the Central Portal.
4. Tags the commit as `vX.Y.Z` and pushes the tag.

Artifacts typically sync from the Central Portal to the Maven Central
read endpoint within ~30 minutes.

## Post-release

- [ ] Bump the reactor version back to the next SNAPSHOT (e.g.
      `mvn versions:set -DnewVersion=1.0.2-SNAPSHOT -DgenerateBackupPoms=false`)
      and commit.
- [ ] Open a release notes pull request based on the CHANGELOG diff.
- [ ] Create a GitHub Release pointing at the new tag with the CHANGELOG
      excerpt as the body.

## Dry runs

A dry run with **dry_run** checked builds, signs, and publishes the
artifacts to the GitHub Actions runner's local Maven repo only — no
network publish, no tag push. Useful when validating a signing config
or a POM change without committing to a real release.

## Rollback

Sonatype Central does not allow re-publishing the same version. If a
release goes out broken:

1. Cut a patch release with the fix (e.g. `1.0.1` → `1.0.2`).
2. The broken artifact remains on Central but consumers can pin around it.

This is the same constraint every Maven Central consumer accepts;
plan accordingly.
