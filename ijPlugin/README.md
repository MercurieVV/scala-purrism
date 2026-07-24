# Purrism IntelliJ Plugin

Mill module for IJ Platform plugin: Refactor-menu scalafix runner + Navigate-menu Three.js/JCEF viewer.

## Layout

- `plugin.xml` — actions `Purrism.RunScalafixRules` (RefactoringMenu), `Purrism.OpenThreeJsViewer` (GoToMenu), `Purrism.OpenModuleGraph` (GoToMenu)
- `PurrismRefactorAction.scala` — runs `scalafix` module rules via scalafix-interfaces
- `PurrismThreeJsAction.scala` — JCEF dialog, bundled `web/purrism-viewer.html` + `three.min.js`
- `PurrismGraphAction.scala` — scans the project for `META-INF/semanticdb` payloads, builds a `graphmodel.GraphModel` and shows its module-level projection in `web/purrism-graph.html`
- `graphmodel/` package — the intermediate model between SemanticDB and the Three.js UI: `Model.scala` (Node/Edge/GraphModel + JSON codec), `GraphModelBuilder.scala` (SemanticIndex → full entity graph: modules, classes/traits/objects, type aliases, methods with rendered signatures and call edges, values), `ModuleProjection.scala` (collapses the full graph to modules + aggregated dependency edges — that's the only thing sent to the UI)
- debug logging (`debugLog`/`debugWarn`) gated behind `ApplicationManager.getApplication.isInternal` (on when `-Didea.is.internal=true`, set by `runIde`)

## One-time setup

JCEF (used by the Three.js viewer) needs a real JetBrains Runtime (JBR), not a stock JDK. Set:

```bash
export PURRISM_JBR_HOME=/path/to/jbr   # must contain bin/java with JCEF baked in
```

Match the JBR build to the platform's `runtimeBuild` in `<platformHome>/dependencies.txt`. If unset, `runIde` falls back to `sys.props("java.home")` (whatever JVM Mill runs on) — JCEF may not work there.

## Run: `mill ijPlugin.runIde`

```bash
PURRISM_JBR_HOME=/path/to/jbr mill ijPlugin.runIde
```

What it does, every run:

1. Downloads/caches the IJ Platform IDE distro for `ideVersion` (`~/.cache/purrism-ijplatform/<ideVersion>`).
2. Downloads/caches the build-compatible Scala plugin from JetBrains Marketplace (`~/.cache/purrism-ijplatform/scala-plugin-<buildNumber>`) — no manual install needed.
3. Builds our plugin (`pluginDist`), stages it + the Scala plugin into a fresh sandbox (`out/ijPlugin/runIde.dest/sandbox/plugins`).
4. Launches `com.intellij.idea.Main` with VM args read straight from the platform's own `product-info.json` (`platformLaunchVmArgs` — avoids hand-maintaining `--add-opens`/JNA flags).
5. Passes this repo's root as a CLI arg, so the IDE opens `scalafix-purrism` directly (no empty Welcome screen).
6. Sets `IDEA_RESTART_VIA_EXIT_CODE=66` in the child env; `com.intellij.util.Restarter` uses that instead of trying to exec a native relauncher. If the IDE requests a restart (e.g. after a plugin update), `runIde` loop catches exit code 66 and relaunches automatically, keeping the same sandbox/plugins/project. Any other exit code (normal quit) just returns.

Sandbox config/system/plugins dirs are wiped clean each Mill `Task.dest` — no stale state between runs.

## Mill daemon gotchas

- `runIde` blocks until the sandbox IDE closes and holds the Mill daemon. Run other `mill` commands with `--no-daemon` while it's up, or wait for it to exit.
- The daemon captures env vars at its own first launch and won't pick up a newly-`export`ed `PURRISM_JBR_HOME` in a later shell. If `runIde` isn't seeing it: find the daemon PID (`out/mill-daemon/processId`), `kill` it, then rerun — next invocation spawns a fresh daemon with current env.

## Manual smoke test

1. `PURRISM_JBR_HOME=... mill ijPlugin.runIde`
2. Wait for IDE window + `scalafix-purrism` project to load (indexing finishes).
3. Refactor menu → "Apply Purrism Scalafix Rules..." on any `.scala` file → enter rule names → confirms/applies.
4. Navigate menu → "Purrism Viewer..." → dialog opens, spinning cube renders (JCEF working), type text + "Send" changes cube color.
5. Navigate menu → "Purrism Module Graph..." → dialog opens immediately, graph builds in the background (needs `*.semanticdb` files under the project — run `mill scalafix.compile` first if none exist yet), then spheres (modules) + lines (cross-module dependency edges) render, camera slowly orbits.
6. Scala plugin should already be active (check Settings → Plugins, or open a `.scala` file and confirm syntax highlighting) — no manual Marketplace install required.
