package scalafix.testkit

import scalafix.internal.config.ScalaVersion
import scalafix.internal.reflect.ClasspathOps
import scalafix.v1.SemanticDocument
import scalafix.v1.SyntacticDocument

/** A `SemanticDocument` for a `testInput` fixture, addressed by the same test
  * name `MunitSemanticRuleSuite` uses (e.g. `golden/ArrowKleisliTypes.scala`).
  *
  * `MunitSemanticRuleSuite` can only assert on a rule's *output*, which makes
  * it the wrong instrument for an analysis helper that emits nothing:
  * `KleisliType` decides a boolean per term and has no patch to diff. This
  * exposes the document itself so such helpers can be unit-tested directly,
  * against real compiler-emitted SemanticDB rather than a hand-built tree.
  *
  * Lives in `scalafix.testkit` for the same reason `MunitSemanticRuleSuite`
  * does: `SemanticDocument.fromPath` is `private[scalafix]` and unreachable
  * from a rule author's own package. Path and classpath resolution go through
  * `TestkitPath`/`TestkitProperties` rather than any working-directory-relative
  * guess -- the forked test JVM has a different cwd, and `docs/RULES.md`
  * records that a cwd-relative guard once made a whole suite skip silently and
  * report green forever.
  */
object FixtureDocuments {

  private lazy val props: TestkitProperties =
    TestkitProperties.loadFromResources()

  private lazy val symtab =
    ClasspathOps.newSymbolTable(props.inputClasspath)

  private lazy val classLoader =
    ClasspathOps.toClassLoader(props.inputClasspath)

  private lazy val scalaVersion: ScalaVersion =
    ScalaVersion.from(props.scalaVersion).get

  private lazy val paths: Map[String, TestkitPath] =
    TestkitPath.fromProperties(props).map(path => path.testName -> path).toMap

  def apply(testName: String): SemanticDocument = {
    val path = paths.getOrElse(
      testName,
      sys.error(
        s"no testInput fixture named '$testName'; known: ${paths.keys.toList.sorted.mkString(", ")}"
      )
    )
    val syntactic = SyntacticDocument.fromInput(path.toInput, scalaVersion)
    SemanticDocument.fromPath(
      syntactic,
      path.semanticdbPath,
      classLoader,
      symtab
    )
  }
}
