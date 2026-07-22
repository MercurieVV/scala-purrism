package fix

import metaconfig.Conf
import metaconfig.Configured

final class PreferArrowConfigSuite extends munit.FunSuite {

  private def decode(conf: Conf): PreferArrowConfig =
    conf.getOrElse("PreferArrow")(PreferArrowConfig.default) match {
      case Configured.Ok(c)    => c
      case Configured.NotOk(e) => fail(s"config decode failed: $e")
    }

  test("default is conservative") {
    assertEquals(PreferArrowConfig.default.aggressive, false)
  }

  test("reads aggressive = true from a nested PreferArrow object") {
    val conf =
      Conf.Obj("PreferArrow" -> Conf.Obj("aggressive" -> Conf.Bool(true)))
    assertEquals(decode(conf).aggressive, true)
  }

  test("absent PreferArrow key falls back to the conservative default") {
    val conf = Conf.Obj("SomethingElse" -> Conf.Bool(true))
    assertEquals(decode(conf).aggressive, false)
  }
}
