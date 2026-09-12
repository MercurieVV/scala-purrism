# Publishing

The publishable artifact is:

```text
io.github.mercurievv:scala-purrism-scalafix_3:<version>
```

Publishing uses Mill's Sonatype Central publisher.

## Setup (done)

Repo is `scala-purrism`, matching `Project.repositoryName` in `build.mill`. License is Apache-2.0. The `io.github.mercurievv` Sonatype Central namespace is verified. GitHub Actions secrets (`MILL_PGP_PASSPHRASE`, `MILL_PGP_SECRET_BASE64`, `MILL_SONATYPE_USERNAME`/`PASSWORD` as a Sonatype Central portal token pair) are set.

## Local Checks

```bash
rtk mill scalafix.compile
rtk mill scalafix.test
rtk mill docs.run
rtk mill scalafix.publishM2Local
```

## Release Flow

The version comes from the git tag itself (`VcsVersionModule`) — there is no
version field in `build.mill` to edit.

1. If this release intentionally breaks binary compatibility, clear the
   baseline first: set `mimaPreviousVersions = Seq()` in `build.mill` (see
   the comment above it), merge that change, and confirm CI is green.
2. Tag the exact commit:

```bash
rtk git tag v0.9.0
rtk git push origin v0.9.0
```

The `Release` workflow (`.github/workflows/release.yml`) then runs
`scalafix.compile`/`scalafix.test`/`docs.run`, checks binary compatibility
against `mimaPreviousVersions`, publishes to Sonatype Central, and — only
after a successful publish — commits back to `master` pinning
`mimaPreviousVersions` to the version just released (`[skip ci]`), so the
next release checks against this one.

## Documentation Site

<https://mercurievv.github.io/scala-purrism/>, published by the `Pages`
workflow on every push to `master` that touches `docs/`, `mdoc-docs/`,
`build.mill` or the workflow's own files. The repository's Pages source is
already set to *GitHub Actions*, so no repository setting changes with this.

The workflow runs `docs.run`, which first renders `docs/` with mdoc and then
hands the generated Markdown to Laika. Laika applies the Helium documentation
theme used by Typelevel sites and writes the deployable HTML to `website/site`.

To preview it as it will be served:

```bash
rtk mill docs.run
rtk python3 -m http.server 4242 --directory website/site
```
