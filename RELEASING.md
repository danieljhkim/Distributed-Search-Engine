# Releasing dsearch

This runbook describes the repository's release contract. It is deliberately
limited to dsearch's Maven, Git, GitHub Actions, and GHCR behavior.

## Release baseline and version policy

`main` is the integration and release branch. The single version carrier is
the root [`pom.xml`](pom.xml): `<revision>` supplies the root project's
`${revision}` version and is inherited by the reactor. Development work keeps
that value on a `-SNAPSHOT` version; a release changes it to the final version
on the `main` commit that will be tagged.

Use semantic versioning after the release baseline is confirmed:

- incompatible public API, wire-contract, configuration, or operational
  behavior changes require a major bump;
- backwards-compatible features require a minor bump;
- backwards-compatible fixes require a patch bump.

Do not infer a pre-1.0 policy from another repository. The current repository
has reachable `v0.1.0-alpha.1` and `v0.2.0` tags, while the root Maven
revision is `1.0.0-SNAPSHOT`. `v0.2.0` points to
`07de0f79affaa7e564fe5f890f60af4318e0dc0e` (2025-12-14), so the current
Maven version is not a continuation of that tagged 0.x line. Before the first
release from this state, a human must explicitly confirm whether `1.0.0` is
the intended stable baseline or change the root revision to a different
semver target. The release-prep probe may report `1.0.0` as a candidate based
on the current Maven carrier, but cannot settle that policy decision.

## Preparation survey

Do not begin a release while unrelated work is in `backlog`, `in-progress`, or
`review`. The guarded `release-prep` auto-task makes this check first. It
exempts only tasks tagged exactly `auto-task:release-prep`.

Once the queue is clear, find the latest reachable release tag and survey only
the commits after it:

```sh
git tag --merged HEAD --list 'v*' --sort=-version:refname | head -n 1
git log v<previous>..HEAD --pretty='%h%x09%s' --no-merges
git log v<previous>..HEAD --pretty='%s' --no-merges | rg -o 'DANI-[0-9]+' | sort -u
```

Record the tag, no-merge commit list, referenced task IDs, proposed semantic
version, and evidence for every possible breaking change. The survey is not a
release authorization: a human classifies the breaking-change candidates and
approves the resulting release task before any version or changelog edit.

## Release checklist

1. Create or update one canonical `Prepare v<X.Y.Z> release` Orbit chore,
   tagged `release`. It points to this runbook and uses only the actual
   modification targets: `file:CHANGELOG.md` and `file:pom.xml`.
2. Draft release notes in [`CHANGELOG.md`](CHANGELOG.md). Add consumer-facing
   entries under `## [Unreleased]`, grouped as `Added`, `Changed`, `Fixed`, or
   `Removed`; at cut time, move the selected entries into a new versioned
   section and preserve older released history. Do not use the changelog as a
   commit-by-commit dump.
3. After human approval, set root `<revision>` to the approved final version
   (without `-SNAPSHOT`). Verify that the effective Maven project version is
   exactly that value:

   ```sh
   mvn -B -ntp help:evaluate -Dexpression=project.version -DforceStdout -q
   mvn -B -ntp spotless:check
   mvn -B -ntp verify
   ```

   Investigate any unexpected dependency, test, or formatting failure before
   proceeding. The repository CI uses the same Spotless check and Maven
   `verify` gate.
4. Commit the approved changelog and root-version change on `main`. Recheck
   that the commit is reachable from the current `origin/main`, then create an
   annotated `v<X.Y.Z>` tag on that exact commit:

   ```sh
   git tag -a v<X.Y.Z> -m 'v<X.Y.Z>'
   git push origin main
   git push origin v<X.Y.Z>
   ```

   Push the branch before the tag. Never force-move a published release tag.
5. Watch the tag-triggered `docker-publish.yml` workflow. It rejects tags not
   reachable from `main`, snapshot Maven versions, and Maven/tag mismatches.
   It publishes `dk.index-node`, `dk.query-node`, `dk.coordinator`, and
   `dk.gateway` to GHCR for `linux/amd64` and `linux/arm64`, always with an
   immutable version tag and, except for RC-tag patterns, a `latest` tag.
   Confirm every matrix image publishes successfully with the expected
   versioned tag before declaring the release complete.

## Approval boundary

The preparation task only surveys and proposes. A human must approve the
candidate version, breaking-change classification, changelog, and release
task before the executor edits files, commits, creates a tag, pushes, or
publishes. The auto-task remains disabled by default and must never be used to
perform those actions.

## Failure and hotfix recovery

If validation fails before tagging, fix the cause in the approved release task
and rerun the Maven checks. If a tag points to the wrong commit or publication
fails after the tag exists, do not rewrite the tag: fix forward in the next
patch release and retain the failed tag as audit evidence. A transient GitHub
Actions failure may be rerun only after confirming the tagged commit and
artifact inputs are correct.

For an urgent production fix, branch from `main`, keep the patch narrowly
scoped, merge it through the normal review and validation path, then cut the
next approved patch release from `main` using this checklist. Do not bypass
the Maven identity, branch-reachability, or human-approval checks.
