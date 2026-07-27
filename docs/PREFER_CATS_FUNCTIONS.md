# PreferCatsFunctions

## Corpus run

The corpus run was attempted on 2026-07-27, but could not execute: this checkout
does not contain a `PreferCatsFunctions` rule (no implementation or Scalafix
service registration), so there is no runnable rule to apply and no proposed
rewrite to audit. The local rule build was also blocked before compilation by
Coursier's cache lock permission error at
`/Users/viktorskalinins/Library/Caches/Coursier/v1/.structure.lock`.

All four repositories were attempted and skipped for the same reason:

| Repository | Proposed rewrites | Declines | Audit |
| --- | ---: | --- | --- |
| `gh-tasks-llm-executor` | n/a | n/a — rule unavailable | no proposed rewrites |
| `arrowstep` | n/a | n/a — rule unavailable | no proposed rewrites |
| `cctv-analyzer-scala` | n/a | n/a — rule unavailable | no proposed rewrites |
| `ScalaSemanticMCP` | n/a | n/a — rule unavailable | no proposed rewrites |

No false-positive follow-up can be filed from this run because no rewrite was
proposed. Once the rule is available, rerun this audit and file any false
positive as a follow-up to #34.

No source files were changed in the corpus repositories. They contained
pre-existing working-tree changes, which were left untouched.

ScalaSemantic MCP was unavailable for this audit: the required
`set_workspace_root` call was rejected as `user cancelled MCP tool call`.
