package fix

import metaconfig.Conf
import metaconfig.Configured

import scalafix.v1.Configuration

/** `TypelevelPurrism` runs `PreferTypeParameters`, so the member rules'
  * configuration has to reach it through the umbrella too.
  */
final class TypelevelPurrismMembershipSuite extends munit.FunSuite {

  private def configure(conf: Conf): Configured[?] =
    new TypelevelPurrism().withConfiguration(Configuration().withConf(conf))

  test("the umbrella accepts a member rule's configuration") {
    val conf = Conf.Obj(
      "PreferPolymorphicCollections" -> Conf.Obj(
        "maxConstraints" -> Conf.Num(3),
        "containers" -> Conf.Lst(Conf.Str("Chain"))
      ),
      "PreferPolymorphicCollectionOps" -> Conf.Obj(
        "elements" -> Conf.Lst(Conf.Str("Money"))
      )
    )
    assert(configure(conf).isOk, "member configuration was rejected")
  }

  /** The discriminating half: an umbrella that never read
    * `PreferPolymorphicCollections` would accept a block that cannot decode,
    * because an unread key is not an invalid one.
    */
  test("a member rule's configuration is actually decoded") {
    val conf = Conf.Obj(
      "PreferPolymorphicCollections" -> Conf.Obj(
        "maxConstraints" -> Conf.Str("not a number")
      )
    )
    assert(
      configure(conf).isNotOk,
      "the umbrella did not read PreferPolymorphicCollections"
    )
  }

  test("PreferPolymorphicTypeclasses configuration still reaches it") {
    val conf = Conf.Obj(
      "PreferPolymorphicTypeclasses" -> Conf.Obj(
        "widenPublic" -> Conf.Bool(true)
      )
    )
    assert(configure(conf).isOk)
  }
}
