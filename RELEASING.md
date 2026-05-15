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

Local Gradle properties (only if you publish from a developer machine
rather than CI):

```
# ~/.gradle/gradle.properties
ossrhUsername=...
ossrhPassword=...
signingKey=...
signingPassword=...
```

The PGP key must be uploaded to a public keyserver (e.g.
`keys.openpgp.org`) so Sonatype can verify signatures.

## Pre-release checks

Run locally before tagging:

```bash
./gradlew clean build                     # 137 tasks green, 40 tests pass
./gradlew :examples:submit-and-stream:run # spot-check one example
./gradlew :arcp-core:publishToMavenLocal  # POM diff against the previous
                                          # release should be small
```

Then sanity-check:

- [ ] [`CHANGELOG.md`](CHANGELOG.md) has a section for the new version.
- [ ] [`CONFORMANCE.md`](CONFORMANCE.md) test counts match the actual
      `./gradlew test` output.
- [ ] [`README.md`](README.md) version coordinates match the version you
      are about to cut (Gradle + Maven snippets).
- [ ] All 10 examples print `OK …` on a fresh clone.
- [ ] `make -C docs/diagrams` produces no diff against the committed SVGs.

## Cutting the release

1. Visit **Actions → release** in the GitHub UI.
2. Click **Run workflow**.
3. Enter the version (e.g. `1.0.1`) — no leading `v`.
4. Leave **dry_run** unchecked for a real release.
5. Confirm.

The workflow:

1. Replaces `version = "..."` in the root `build.gradle.kts`.
2. Runs `./gradlew build` — must be green.
3. Publishes all 10 artifacts to Sonatype Central staging.
4. Tags the commit as `vX.Y.Z` and pushes the tag.

Sonatype staging holds the artifacts until you log into the Central UI
and run **Close** then **Release** on the staging repository. Once
released, artifacts sync to Maven Central within ~30 minutes.

## Post-release

- [ ] Bump the root `version` back to the next SNAPSHOT (e.g.
      `1.0.2-SNAPSHOT`) and commit.
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
