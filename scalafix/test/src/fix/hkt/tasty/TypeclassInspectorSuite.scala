package fix.hkt.tasty

import java.nio.file.Path
import java.nio.file.Paths

import munit.FunSuite

final class TypeclassInspectorSuite extends FunSuite {

  private lazy val classpath: List[Path] =
    System
      .getProperty("java.class.path")
      .split(java.io.File.pathSeparator)
      .toList
      .filter(_.nonEmpty)
      .map(Paths.get(_))

  private def jar(prefix: String): Path =
    classpath
      .find(path =>
        path.getFileName.toString.startsWith(prefix) &&
          path.getFileName.toString.endsWith(".jar")
      )
      .getOrElse(fail(s"no jar on test classpath with prefix $prefix"))

  test("CatsScope reads the Cats hierarchy from cats-core TASTy") {
    val rows = TypeclassInspector
      .inspectJar(jar("cats-core_3-"), classpath, TypeclassInspector.CatsScope)
      .fold(fail(_), identity)
    val monad = rows.typeclasses
      .find(_.headOption.contains("cats/Monad#"))
      .getOrElse(fail("cats/Monad# missing"))
    assertEquals(monad.lift(1), Some("cats/Applicative#,cats/FlatMap#"))
    assert(rows.capabilities.exists(_.headOption.contains("cats/Functor#")))
  }

  test("AnyScope finds cats-effect typeclasses") {
    val rows = TypeclassInspector
      .inspectJar(
        jar("cats-effect-kernel_3-"),
        classpath,
        TypeclassInspector.AnyScope
      )
      .fold(fail(_), identity)
    assert(rows.typeclasses.map(_.head).contains("cats/effect/kernel/Sync#"))
  }

  test("aliases: MonadCancelThrow is MonadCancel fixed to Throwable") {
    val rows = TypeclassInspector
      .inspectJar(
        jar("cats-effect-kernel_3-"),
        classpath,
        TypeclassInspector.AnyScope
      )
      .fold(fail(_), identity)
    val alias = rows.aliases
      .find(_.headOption.exists(_.endsWith(".MonadCancelThrow#")))
      .getOrElse(fail(s"no MonadCancelThrow in ${rows.aliases}"))
    assertEquals(alias(1), "MonadCancelThrow")
    assertEquals(alias(3), "cats/effect/kernel/MonadCancel#")
    assertEquals(alias(4), "java/lang/Throwable#")
  }

  test("evidence: Traverse#traverse needs Applicative (cats)") {
    val rows = TypeclassInspector
      .inspectJar(jar("cats-core_3-"), classpath, TypeclassInspector.CatsScope)
      .fold(fail(_), identity)
    val row = rows.evidence
      .find(_.headOption.contains("cats/Traverse#traverse()."))
      .getOrElse(fail("traverse evidence missing"))
    assert(row(1).split(",").contains("cats/Applicative#"))
  }

  test("a LinkageError (non-NonFatal) becomes Left naming the class") {
    val result = TypeclassInspector.catching[Int](
      throw new NoClassDefFoundError("missing/Dep")
    )
    assertEquals(
      result,
      Left(
        "TASTy inspection failed: java.lang.NoClassDefFoundError: missing/Dep"
      )
    )
  }

  test("an unreadable jar is a Left, not an exception") {
    val result = TypeclassInspector.inspectJar(
      Paths.get("/nonexistent/none.jar"),
      Nil,
      TypeclassInspector.AnyScope
    )
    assert(result.isLeft)
  }
}
