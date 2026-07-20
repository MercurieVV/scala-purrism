package scalafix.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.meta.internal.io.FileIO

import scalafix.internal.config.ScalaVersion
import scalafix.internal.patch.PatchInternals
import scalafix.internal.reflect.ClasspathOps
import scalafix.internal.testkit.AssertDiff
import scalafix.internal.testkit.CommentAssertion
import scalafix.internal.v1.Args

/** A munit port of `scalafix.testkit.AbstractSemanticRuleSuite`.
  *
  * Scalafix's own testkit suite extends `org.scalatest.funsuite.AnyFunSuite`,
  * but this project runs munit. The pieces needed to build a `SemanticDocument`
  * -- `RuleTest.fromPath`, `SemanticDocument.fromPath` -- are
  * `private[scalafix]`, so there is no way to construct one from a rule
  * author's own package. Living in `scalafix.testkit` is what makes them
  * reachable; everything else here is `AbstractSemanticRuleSuite`'s body with
  * the ScalaTest assertions swapped for munit ones.
  *
  * Each input fixture must open with a `/* rules = [ ... ] */` comment naming
  * the rules to run -- that is how a fixture selects its rule.
  *
  * Set `SCALAFIX_SAVE_EXPECT=true` to overwrite the expected files with
  * whatever the rules currently produce.
  */
abstract class MunitSemanticRuleSuite(
    val props: TestkitProperties,
    val isSaveExpect: Boolean
) extends munit.FunSuite {

  def this(props: TestkitProperties) =
    this(props, sys.env.get("SCALAFIX_SAVE_EXPECT").contains("true"))

  def this() = this(TestkitProperties.loadFromResources())

  lazy val testsToRun: List[RuleTest] = {
    val args = Args.default.copy(
      scalaVersion = ScalaVersion.from(props.scalaVersion).get,
      scalacOptions = props.scalacOptions,
      classpath = props.inputClasspath
    )
    val symtab = ClasspathOps.newSymbolTable(props.inputClasspath)
    val classLoader = ClasspathOps.toClassLoader(args.validatedClasspath)
    TestkitPath
      .fromProperties(props)
      .map(test => RuleTest.fromPath(args, test, classLoader, symtab))
  }

  def runAllTests(): Unit = testsToRun.foreach(runOn)

  def runOn(diffTest: RuleTest): Unit =
    test(diffTest.path.testName)(evaluateTestBody(diffTest))

  def evaluateTestBody(diffTest: RuleTest): Unit = {
    val (rule, sdoc) = diffTest.run.apply()
    rule.beforeStart()
    val res =
      try rule.semanticPatch(sdoc, suppress = false)
      finally rule.afterComplete()

    // Both patch application paths must agree; a mismatch means the rule
    // anchored a patch on positions that do not belong to this document.
    val fixed = PatchInternals.tokenPatchApply(
      res.ruleCtx,
      res.semanticdbIndex,
      res.patches
    )
    assertNoDiff(
      fixed,
      res.fixed,
      "tokenPatchApply output differs from rule.semanticPatch output"
    )

    val obtained = SemanticRuleSuite.stripTestkitComments(fixed)
    val expected = diffTest.path.resolveOutput(props) match {
      case Right(file) => FileIO.slurp(file, StandardCharsets.UTF_8)
      case Left(err)   =>
        // A linter produces no diff, so it needs no output file.
        if (fixed == sdoc.input.text) obtained else fail(err)
    }

    val lintDiff =
      AssertDiff(res.diagnostics, CommentAssertion.extract(sdoc.tokens))
    val contentDiffers =
      DiffAssertions.compareContents(obtained, expected).nonEmpty

    if (isSaveExpect && (contentDiffers || lintDiff.isFailure)) {
      diffTest.path.resolveOutput(props).foreach { output =>
        println(s"promoted expect test: $output")
        Files.write(output.toNIO, obtained.getBytes(StandardCharsets.UTF_8))
      }
    } else {
      if (lintDiff.isFailure) fail(s"lint assertions did not match:\n$lintDiff")
      assertNoDiff(obtained, expected)
    }
  }
}
