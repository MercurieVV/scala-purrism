package fix

import scala.concurrent.duration.*

import scalafix.testkit.MunitSemanticRuleSuite

/** Runs every fixture under `scalafix/testInput/src` through the rules named in
  * its own `/* rules = [...] */` header comment, and diffs the result against
  * the matching file under `scalafix/testOutput/src`.
  */
class SemanticFixtureSuite extends MunitSemanticRuleSuite {
  // TypeclassWeakening fixtures load the classpath's typeclass facts from
  // TASTy; the first fixture to need them pays a cold scan, which exceeds
  // MUnit's 30s default on a CI runner.
  override val munitTimeout: Duration = 10.minutes

  runAllTests()
}
