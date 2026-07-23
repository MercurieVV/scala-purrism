package fix

import scalafix.testkit.MunitSemanticRuleSuite

/** Runs every fixture under `scalafix/testInput/src` through the rules named in
  * its own `/* rules = [...] */` header comment, and diffs the result against
  * the matching file under `scalafix/testOutput/src`.
  */
class SemanticFixtureSuite extends MunitSemanticRuleSuite {
  runAllTests()
}
