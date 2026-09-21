# Maintenance

## Finding codes

Every lint's `Rule.kind` code (the scalafix lint id `FindingDiagnostic` prints, e.g.
`PreferArrow.fan-out-shadowed-input`) is **append-only**. `findings.tsv` is checked
in twice (`docs/findings.tsv`, `scalafix/resources/purrism/findings.tsv`) and
harnesses vendor it, so renaming or removing a code is a breaking change for any
harness pinned to a version — treat it like removing a public API, not a doc edit.

To add a kind:

1. Add a `Finding` (rule, kind, title, explanation, instruction, doc anchor) to the
   relevant family object in `scalafix/src/fix/findings/` (`IdiomFindings.scala`,
   `ArrowFindings.scala`, `OpaqueFindings.scala`, `PolymorphicFindings.scala`,
   `CatsFunctionFindings.scala`, …). `Families.scala` only aggregates those objects
   into `FindingCatalog.all`; a `Finding` never goes there directly.
2. Lint with `FindingDiagnostic(finding, position, text)` at the site — `FindingCatalog`
   is the only source of `Finding`s, so the code and the message stay in sync.
3. Add a fixture that triggers it, asserting the code with `// assert: Rule.kind`.
   Fixtures live in `scalafix/testInput/src/**` (`/* rules = [...] */` header), with
   the expected rewrite in the matching file under `scalafix/testOutput/src/**`
   (lint-only fixtures need no output file). If no fixture can trigger it, add the
   code and reason to `FindingCatalog.unfixturable` instead — see below.
4. Run `rtk mill scalafix.findings` to regenerate both checked-in TSVs from the
   catalog, and commit both files together with the code change.

`mill scalafix.findingsCheck` (run in CI after `scalafix.test`) fails the build when
either checked-in TSV is stale relative to `FindingCatalog`. The header version is
the last reachable release tag (`git describe --tags --abbrev=0`), so CI checks out
with `fetch-depth: 0` / `fetch-tags: true`; a shallow, tag-less clone fails with
`findingsVersion: no tag reachable`.

### Umbrella rules re-prefix the code

Under an umbrella rule the prefix is the umbrella's name
(`[TypelevelPurrism.readability-budget]`, not `[PreferArrow.readability-budget]`),
because the umbrella's `fix` sums its children's patches and scalafix attributes
every lint in the sum to the rule that returned it. The same holds for
`PreferTypeParameters` and `PreferCatsExpressions`. Kinds are unique across the
catalog, so join on the kind, never on the full `Rule.kind`. A fixture that runs an
umbrella asserts the umbrella-prefixed code (`ArrowUmbrellaFindingPrefix.scala`);
`FindingCoverageSuite` accepts such an assertion when its kind is catalogued.

### The `unfixturable` allowlist

`FindingCatalog.unfixturable` (`scalafix/src/fix/FindingCatalog.scala`) is a map of
code → reason for codes the coverage suite cannot force a fixture to trigger (e.g. a
branch that is only reachable with a stale build cache). The coverage suite skips
these codes but also asserts each one is still catalogued and still genuinely has no
fixture — so the list cannot silently accumulate. Add an entry here only when a
fixture is provably unreachable, not as a shortcut around writing one; removing an
entry is how a later change re-requires a fixture for that code.

## Scala Steward

This project has a valid `.scala-steward.conf`.

The `.mill-version` file is required so Scala Steward detects Mill 1.x and uses the current `--import` plugin path instead of the legacy `-p` flag.

Scala Steward expects `repos.md` entries in hosted forge form:

```text
- owner/repository
- owner/repository:branch
```

Repo slug: `MercurieVV/scala-purrism`. To run Steward, create a repos file with that slug and run:

```bash
rtk coursier launch org.scala-steward:scala-steward-core_2.13:latest.release -- \
  --workspace /private/tmp/scala-purrism-steward-workspace \
  --repos-file /path/to/repos.md \
  --git-author-email steward@example.invalid \
  --git-ask-pass /path/to/askpass.sh \
  --forge-login <forge-login> \
  --repo-config .scala-steward.conf \
  --disable-sandbox
```

Local validation:

```bash
rtk coursier launch org.scala-steward:scala-steward-core_2.13:latest.release -- \
  validate-repo-config .scala-steward.conf
```
