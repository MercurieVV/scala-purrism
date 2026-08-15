# HKT Corpus Run Recipe

This document records the integration‑ref resolution, the GO/BLOCKED verdict for
`PreferPolymorphicTypeclasses`, the pinned consumption recipe, and copy‑pasteable command
blocks that exercise the rule on every external corpus repo required by #44.

## Integration Ref

- **Selected ref / branch** : `{branch-name}`
- **Commit SHA** : `{sha}`

**Candidates examined**  
`task-41`, `task-42`, `task-43`, `task-56`, `task-93`, `origin/task-92`,
`origin/task-94`, `origin/task-98`, `origin/task-100`, `origin/task-103`

The chosen ref carries `scalafix/src/fix/hkt/**` and the HKT golden fixtures
under `scalafix/test/resources/golden/` and passes `rtk mill scalafix.test`
with `PreferPolymorphicTypeclasses` registered and its fixtures exercised.

## Verdict

- TODO **GO** – `rtk mill scalafix.test` is green and the rule is exercised.
- TODO **BLOCKED** – see failure output below.
