package fix

import metaconfig.Conf
import metaconfig.Configured

final class PreferPolymorphicTypeclassesConfigSuite extends munit.FunSuite {

  private def decode(conf: Conf): PreferPolymorphicTypeclassesConfig =
    conf.getOrElse("PreferPolymorphicTypeclasses")(
      PreferPolymorphicTypeclassesConfig.default
    ) match {
      case Configured.Ok(c)    => c
      case Configured.NotOk(e) => fail(s"config decode failed: $e")
    }

  test("defaults rewrite private definitions only, at most two constraints") {
    val default = PreferPolymorphicTypeclassesConfig.default
    assertEquals(default.rewrite, true)
    assertEquals(default.widenPublic, false)
    assertEquals(default.maxConstraints, 2)
  }

  test("each key reads independently of the others") {
    val conf = Conf.Obj(
      "PreferPolymorphicTypeclasses" -> Conf.Obj(
        "widenPublic" -> Conf.Bool(true),
        "maxConstraints" -> Conf.Num(3)
      )
    )
    val decoded = decode(conf)
    assertEquals(decoded.widenPublic, true)
    assertEquals(decoded.maxConstraints, 3)
    assertEquals(decoded.rewrite, true)
  }

  test("rewrite = false leaves the rule reporting only") {
    val conf = Conf.Obj(
      "PreferPolymorphicTypeclasses" -> Conf.Obj("rewrite" -> Conf.Bool(false))
    )
    assertEquals(decode(conf).rewrite, false)
  }

  test("absent key falls back to the default") {
    assertEquals(
      decode(Conf.Obj("SomethingElse" -> Conf.Bool(true))),
      PreferPolymorphicTypeclassesConfig.default
    )
  }

  /** The pre-wiring configuration type is still the one a published
    * `PreferPolymorphicTypeclasses(PreferHKTConfig)` call passes, so it has to
    * keep meaning what it meant.
    */
  test("the legacy config carries widenPublic into the new one") {
    val rule =
      new PreferPolymorphicTypeclasses(PreferHKTConfig(widenPublic = true))
    assertEquals(rule.name.value, "PreferPolymorphicTypeclasses")
  }
}
