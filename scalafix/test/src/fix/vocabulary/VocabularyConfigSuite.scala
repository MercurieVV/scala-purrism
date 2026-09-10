package fix.vocabulary

import metaconfig.{Conf, Configured}
import munit.FunSuite

class VocabularyConfigSuite extends FunSuite {

  private def decode(conf: Conf): VocabularyConfig =
    conf.as[VocabularyConfig](using VocabularyConfig.decoder) match {
      case Configured.Ok(c)    => c
      case Configured.NotOk(e) => fail(s"config decode failed: $e")
    }

  test("default config has empty scope, profile 'default', no profiles") {
    val default = VocabularyConfig.default
    assertEquals(default.scope, Nil)
    assertEquals(default.profile, "default")
    assertEquals(default.profiles, Map.empty[String, VocabularyProfile])
    assertEquals(default.lintSeverity, scalafix.lint.LintSeverity.Warning)
  }

  test("resolveProfile fails loudly when the named profile is missing") {
    val config = VocabularyConfig(profile = "arrow", profiles = Map.empty)
    assert(config.resolveProfile.isLeft)
  }

  test("resolveProfile finds a declared profile by name") {
    val arrow = VocabularyProfile(classes = List("cats\\.arrow\\..*"))
    val config =
      VocabularyConfig(profile = "arrow", profiles = Map("arrow" -> arrow))
    assertEquals(config.resolveProfile, Right(arrow))
  }

  test("decodes severity, scope, profile, and a profiles map") {
    val conf = Conf.Obj(
      "severity" -> Conf.Str("error"),
      "scope" -> Conf.Lst(Conf.Str("com\\.foo\\.wiring\\..*")),
      "profile" -> Conf.Str("arrow"),
      "profiles" -> Conf.Obj(
        "arrow" -> Conf.Obj(
          "classes" -> Conf.Lst(Conf.Str("cats\\.arrow\\..*")),
          "bannedConstructs" -> Conf.Lst(Conf.Str("scala.meta.Term.If")),
          "budgetedTypeclasses" -> Conf.Lst(Conf.Str("ArrowConvert")),
          "maxInstantiations" -> Conf.Num(2)
        )
      )
    )

    val decoded = decode(conf)
    assertEquals(decoded.severity, "error")
    assertEquals(decoded.scope, List("com\\.foo\\.wiring\\..*"))
    assertEquals(decoded.profile, "arrow")
    assertEquals(decoded.lintSeverity, scalafix.lint.LintSeverity.Error)

    val arrow =
      decoded.resolveProfile.getOrElse(fail("expected the 'arrow' profile"))
    assertEquals(arrow.classes, List("cats\\.arrow\\..*"))
    assertEquals(arrow.bannedConstructs, List("scala.meta.Term.If"))
    assertEquals(arrow.budgetedTypeclasses, List("ArrowConvert"))
    assertEquals(arrow.maxInstantiations, 2)
  }

  test("decodes multiple profiles in one config") {
    val conf = Conf.Obj(
      "profiles" -> Conf.Obj(
        "arrow" -> Conf.Obj(
          "classes" -> Conf.Lst(Conf.Str("cats\\.arrow\\..*"))
        ),
        "otherLayer" -> Conf.Obj("classes" -> Conf.Lst(Conf.Str("scala\\.Int")))
      )
    )
    val decoded = decode(conf)
    assertEquals(decoded.profiles.keySet, Set("arrow", "otherLayer"))
  }
}
