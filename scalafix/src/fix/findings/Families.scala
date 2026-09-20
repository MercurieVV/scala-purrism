package fix.findings

import fix.Finding

/** Every family object lists its findings here; FindingCatalog reads this list.
  */
object Families {
  val all: List[Finding] =
    IdiomFindings.findings ++
      ArrowFindings.findings ++
      CatsFunctionFindings.findings ++
      PolymorphicFindings.findings ++
      OpaqueFindings.findings
}
