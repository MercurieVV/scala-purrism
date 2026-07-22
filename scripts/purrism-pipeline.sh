#!/usr/bin/env bash
# Runs the two-stage Typelevel refactoring over a target project.
#
#   stage 1  PreferKleisli  -- lift effectful defs to Kleisli, re-split callers
#   recompile               -- refresh SemanticDB for the new signatures
#   stage 2  PreferArrow    -- compose the lifted Kleislis into arrows
#
# The recompile is not optional: PreferArrow reads SemanticDB to decide what is
# a Kleisli, so running both rules in one invocation means stage 2 never sees
# anything stage 1 produced.
#
# Usage:
#   scripts/purrism-pipeline.sh <target-project> [rule-version]
#
# The target project must already emit SemanticDB (`-Xsemanticdb` /
# `--semanticdb`) and have a `scripts/refresh-semanticdb.sh`, or set
# REFRESH_CMD to whatever recompiles it.
set -euo pipefail

TARGET="${1:?usage: purrism-pipeline.sh <target-project> [rule-version]}"
VERSION="${2:-0.5.3}"
SCALAFIX_VERSION="${SCALAFIX_VERSION:-0.14.7}"
SCALA_VERSION="${SCALA_VERSION:-3.8.4}"
REFRESH_CMD="${REFRESH_CMD:-scripts/refresh-semanticdb.sh}"

cd "$TARGET"
ROOT="$PWD"
CONFIG="${CONFIG:-$ROOT/.scalafix-pipeline.conf}"

if [[ ! -f "$CONFIG" ]]; then
  cat > "$CONFIG" <<'CONF'
rules = []

# Decide the whole project's lifts at once, so a public def can be re-shaped
# with its callers in other files following. See README.md#preferkleisli.
PreferKleisli {
  crossFile = true
}

# Rewrite fan-outs of plain F-returning calls into arrows, accepting output
# busier than the source. See docs/ARROW_PATTERNS.md.
PreferArrow {
  aggressive = true
}
CONF
  printf 'wrote default pipeline config: %s\n' "$CONFIG"
fi

# A locally published rule lands in ivy2/local, which coursier prefers; the
# swap forces the m2 copy when one is present.
IVY_JAR="$HOME/.ivy2/local/io.github.mercurievv/scala-purrism-scalafix_3/$VERSION/jars/scala-purrism-scalafix_3.jar"
M2_JAR="$HOME/.m2/repository/io/github/mercurievv/scala-purrism-scalafix_3/$VERSION/scala-purrism-scalafix_3-$VERSION.jar"

resolve_classpath() {
  local cp
  cp="$(coursier fetch -p -r m2Local "io.github.mercurievv:scala-purrism-scalafix_3:$VERSION")"
  if [[ -f "$M2_JAR" ]]; then
    cp="${cp//$IVY_JAR/$M2_JAR}"
  fi
  printf '%s' "$cp"
}

run_stage() {
  local rule="$1"
  shift
  coursier launch "scalafix:$SCALAFIX_VERSION" -- \
    --config "$CONFIG" \
    --rules "class:fix.$rule" \
    --tool-classpath "$(resolve_classpath)" \
    --sourceroot "$ROOT" \
    --semanticdb-targetroots "$ROOT/.semanticdb" \
    --scala-version "$SCALA_VERSION" \
    "$@"
}

# Only files the compiler actually emitted a payload for. Scripts and
# alternative build descriptors are `.scala` but never compiled into the
# project, and passing one makes scalafix fail the whole run with
# "SemanticDB not found".
#
# `mapfile` is bash 4+; macOS ships bash 3.2, so read the list the portable way.
collect_files() {
  FILE_ARGS=()
  while IFS= read -r file; do
    if [[ -f "$ROOT/.semanticdb/META-INF/semanticdb/$file.semanticdb" ]]; then
      FILE_ARGS+=("--files=$file")
    fi
  done < <(git ls-files '*.scala' ':!:*.test.scala')

  if [[ ${#FILE_ARGS[@]} -eq 0 ]]; then
    printf 'no compiled .scala files found under %s/.semanticdb\n' "$ROOT" >&2
    exit 1
  fi
}

printf '\n== refreshing SemanticDB ==\n'
$REFRESH_CMD
collect_files

printf '\n== stage 1: PreferKleisli (%d files) ==\n' "${#FILE_ARGS[@]}"
run_stage PreferKleisli "${FILE_ARGS[@]}"

printf '\n== recompiling for stage 2 ==\n'
$REFRESH_CMD
collect_files

printf '\n== stage 2: PreferArrow (%d files) ==\n' "${#FILE_ARGS[@]}"
run_stage PreferArrow "${FILE_ARGS[@]}"

printf '\n== done; review with git diff ==\n'
