# Publishing

The publishable artifact is:

```text
io.github.mercurievv:scala-purrism-scalafix_3:<version>
```

Publishing uses Mill's Sonatype Central publisher.

## Required Setup

1. Rename the GitHub repository to `scala-purrism` or update `Project.repositoryName` in `build.mill`.
2. Verify the `io.github.mercurievv` namespace in Sonatype Central.
3. Confirm the project license before the first immutable release. The current POM metadata declares Apache-2.0.
4. Add these GitHub Actions secrets:

```text
MILL_PGP_PASSPHRASE
MILL_PGP_SECRET_BASE64
MILL_SONATYPE_PASSWORD
MILL_SONATYPE_USERNAME
```

`MILL_SONATYPE_USERNAME` and `MILL_SONATYPE_PASSWORD` should be a Sonatype Central portal token username/password pair.

## Local Checks

```bash
rtk mill scalafix.compile
rtk mill scalafix.test
rtk mill docs.run
rtk mill scalafix.publishM2Local
```

## Release Flow

1. Update `Project.publishVersion` in `build.mill` to the release version.
2. Merge the version change after CI passes.
3. Tag the exact commit:

```bash
rtk git tag v0.2.0
rtk git push origin v0.2.0
```

The `Release` workflow publishes tagged versions to Sonatype Central.

## Documentation Site

<https://mercurievv.github.io/scala-purrism/>, published by the `Pages`
workflow on every push to `master` that touches `docs/`, `mdoc-docs/`,
`build.mill` or the workflow's own files. The repository's Pages source is
already set to *GitHub Actions*, so no repository setting changes with this.

The workflow renders `docs/` with mdoc, copies `.github/pages/_config.yml` in
beside the output, and hands `website/docs` to Jekyll. That config is not
optional decoration:

- `jekyll-optional-front-matter` — these pages carry no front matter, and
  without this plugin Jekyll treats each one as a static asset and serves raw
  markdown;
- `defaults: layout: default` — front matter is also what normally assigns a
  layout, so without this the theme goes unused;
- `baseurl: /scala-purrism` — a project site is served from a subpath, and
  every internal link is written through `relative_url`.

To preview it as it will be served:

```bash
rtk mill docs.run
cp .github/pages/_config.yml website/docs/_config.yml
jekyll serve --source website/docs
```
