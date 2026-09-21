# Findings

Every purrism lint prints its code as the scalafix lint id — `[PreferEffectIdioms.manual-resource]` — and appends a
one-sentence explanation to the message. This table (generated from `FindingCatalog`, also shipped in the jar as
`purrism/findings.tsv`) adds the repair *instruction* an agent harness shows next to the finding.

```scala mdoc:passthrough
print(docs.Findings.renderTable(java.nio.file.Paths.get("docs", "findings.tsv")))
```
