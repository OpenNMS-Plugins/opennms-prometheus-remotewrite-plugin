# Releasing

This document describes how a release of the OpenNMS Prometheus RemoteWrite Plugin
is produced.

## Versioning

Releases follow [Semantic Versioning](https://semver.org/), derived from the
[Conventional Commits](https://www.conventionalcommits.org/) since the previous
tag: `BREAKING CHANGE`/`!` → major, `feat` → minor, otherwise patch.

## Branch model

- **`release-2.x`** is the active development line. All changes (including
  Dependabot updates) land here first.
- **`master`** is updated by CI, which merges `release-2.x` forward and pushes.
- CircleCI's `deploy` job runs on `master` and publishes GPG-signed artifacts to
  Sonatype OSSRH (Maven Central) using the `-Prelease` profile.

## What a release produces

- Maven artifacts (parent, `plugin`, `karaf-features`, `assembly`) published to
  Maven Central under `org.opennms.plugins.timeseries`.
- A Karaf assembly archive (`.kar`) that can be attached to the GitHub Release.
- All artifacts are GPG-signed by the OpenNMS release key during the CircleCI
  `deploy` job (`sign-packages` orb + `gpg-signing` context).

## Cutting a release

1. Ensure `release-2.x` is green in CircleCI and up to date.
2. Set the release version on `release-2.x` (via a PR):
   ```bash
   mvn versions:set -DnewVersion=2.1.1
   git commit -s -am "chore(release): 2.1.1"
   ```
3. Merge the PR. CI merges `release-2.x` into `master` and deploys the signed
   artifacts to OSSRH.
4. Tag the release commit with a GPG-signed tag and push it:
   ```bash
   git tag -u opennms@opennms.org -s v2.1.1 -m "v2.1.1"
   git push origin v2.1.1
   ```
5. Bump `release-2.x` to the next snapshot:
   ```bash
   mvn versions:set -DnewVersion=2.1.2-SNAPSHOT
   ```

## Publishing the GitHub Release

Create a GitHub Release for the `vX.Y.Z` tag with curated notes (highlights,
breaking changes, fixes — not a raw commit dump). Attach the assembly archive,
renamed for download:

```bash
mv org.opennms.plugins.timeseries.prometheus.remotewrite.assembly.kar-<VERSION>.kar \
   opennms-prometheus-remotewrite-plugin.kar
```

## Verifying artifacts

Maven Central artifacts are GPG-signed. Verify a downloaded artifact against the
OpenNMS public signing key:

```bash
gpg --verify opennms-prometheus-remotewrite-plugin.kar.asc \
             opennms-prometheus-remotewrite-plugin.kar
```
