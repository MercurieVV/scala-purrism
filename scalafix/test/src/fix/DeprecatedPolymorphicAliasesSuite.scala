package fix

import scala.annotation.nowarn

import metaconfig.Conf

import scalafix.v1.Configuration

/** The pre-0.9.0 rule names are kept resolvable by name and by old config
  * block, so an existing `.scalafix.conf` upgrading past the rename does not
  * break outright. See `scalafix/src/fix/DeprecatedPolymorphicAliases.scala`.
  */
@nowarn("cat=deprecation")
final class DeprecatedPolymorphicAliasesSuite extends munit.FunSuite {

  test("PreferHKTTypeclasses still resolves under its old name") {
    val rule: PreferHKTTypeclasses = new PreferHKTTypeclasses()
    assertEquals(rule.name.value, "PreferHKTTypeclasses")
  }

  test("PreferContainerTypeclasses still resolves under its old name") {
    val rule: PreferContainerTypeclasses = new PreferContainerTypeclasses()
    assertEquals(rule.name.value, "PreferContainerTypeclasses")
  }

  test("PreferElementTypeclasses still resolves under its old name") {
    val rule: PreferElementTypeclasses = new PreferElementTypeclasses()
    assertEquals(rule.name.value, "PreferElementTypeclasses")
  }

  test("the old PreferHKTTypeclasses config block still decodes") {
    val conf = Conf.Obj(
      "PreferHKTTypeclasses" -> Conf.Obj("widenPublic" -> Conf.Bool(true))
    )
    val configured =
      new PreferHKTTypeclasses().withConfiguration(
        Configuration().withConf(conf)
      )
    assert(configured.isOk, s"expected Ok, got $configured")
  }

  test("the old PreferContainerTypeclasses config block still decodes") {
    val conf = Conf.Obj(
      "PreferContainerTypeclasses" -> Conf.Obj(
        "containers" -> Conf.Lst(Conf.Str("Chain"))
      )
    )
    val configured = new PreferContainerTypeclasses()
      .withConfiguration(Configuration().withConf(conf))
    assert(configured.isOk, s"expected Ok, got $configured")
  }

  test("the old PreferElementTypeclasses config block still decodes") {
    val conf = Conf.Obj(
      "PreferElementTypeclasses" -> Conf.Obj(
        "elements" -> Conf.Lst(Conf.Str("Money"))
      )
    )
    val configured = new PreferElementTypeclasses()
      .withConfiguration(Configuration().withConf(conf))
    assert(configured.isOk, s"expected Ok, got $configured")
  }
}
