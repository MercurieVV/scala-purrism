package fix.vocabulary

import metaconfig.{Conf, Configured}
import munit.FunSuite

class VocabularyConfigSuite extends FunSuite {

  private def decode(conf: Conf): VocabularyConfig =
    conf.as[VocabularyConfig](using VocabularyConfig.decoder) match {
      case Configured.Ok(c)    => c
      case Configured.NotOk(e) => fail(s"config decode failed: $e")
    }

  test("default config has empty scope, defaults maxInstantiations to 1") {
    val default = VocabularyConfig.default
    assertEquals(default.scope, Nil)
    assertEquals(default.maxInstantiations, 1)
    assertEquals(default.lintSeverity, scalafix.lint.LintSeverity.Warning)
  }

  test("decodes a full config block") {
    val conf = Conf.Obj(
      "severity" -> Conf.Str("error"),
      "scope" -> Conf.Lst(Conf.Str("com\\.foo\\.wiring\\..*")),
      "classes" -> Conf
        .Lst(Conf.Str("cats\\.arrow\\..*"), Conf.Str("scala\\.Either")),
      "bannedConstructs" -> Conf.Lst(Conf.Str("scala.meta.Term.If")),
      "budgetedTypeclasses" -> Conf.Lst(Conf.Str("ArrowConvert")),
      "maxInstantiations" -> Conf.Num(2)
    )

    val decoded = decode(conf)
    assertEquals(decoded.severity, "error")
    assertEquals(decoded.scope, List("com\\.foo\\.wiring\\..*"))
    assertEquals(decoded.classes, List("cats\\.arrow\\..*", "scala\\.Either"))
    assertEquals(decoded.bannedConstructs, List("scala.meta.Term.If"))
    assertEquals(decoded.budgetedTypeclasses, List("ArrowConvert"))
    assertEquals(decoded.maxInstantiations, 2)
    assertEquals(decoded.lintSeverity, scalafix.lint.LintSeverity.Error)
  }
}
