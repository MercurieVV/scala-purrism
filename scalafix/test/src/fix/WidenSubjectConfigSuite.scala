package fix

import metaconfig.Conf
import metaconfig.Configured

/** What the widening rules are configured to *widen*: which constructors, and
  * which concrete element types they are willing to assume an instance for.
  */
final class WidenSubjectConfigSuite extends munit.FunSuite {

  private def decodeElements(conf: Conf): ElementTypesConfig =
    conf.getOrElse("PreferPolymorphicCollectionOps")(
      ElementTypesConfig.default
    ) match {
      case Configured.Ok(c)    => c
      case Configured.NotOk(e) => fail(s"config decode failed: $e")
    }

  test("the element list defaults to what Cats itself ships") {
    assertEquals(
      ElementTypesConfig.default.elements,
      ElementTypesConfig.catsProvided
    )
    assert(ElementTypesConfig.catsProvided.contains("Double"))
  }

  test("the element list is configurable, and reads from the rule's block") {
    val conf = Conf.Obj(
      "PreferPolymorphicCollectionOps" -> Conf.Obj(
        "elements" -> Conf.Lst(Conf.Str("Money"), Conf.Str("VarId"))
      )
    )
    assertEquals(decodeElements(conf).elements, List("Money", "VarId"))
  }

  /** The key shares a block with `PreferPolymorphicCollectionOpsConfig`, so
    * reading one must not disturb the other.
    */
  test("elements and the rest of the block decode independently") {
    val conf = Conf.Obj(
      "PreferPolymorphicCollectionOps" -> Conf.Obj(
        "elements" -> Conf.Lst(Conf.Str("Money")),
        "widenPublic" -> Conf.Bool(true)
      )
    )
    assertEquals(decodeElements(conf).elements, List("Money"))
    val rule = conf.getOrElse("PreferPolymorphicCollectionOps")(
      PreferPolymorphicCollectionOpsConfig.default
    )
    assertEquals(rule.get.widenPublic, true)
    assertEquals(
      rule.get.containers,
      PreferPolymorphicCollectionOpsConfig.default.containers
    )
  }

  test("a spelled-out container list matches by name and nothing else") {
    val containers = List("List", "Vector")
    assert(ContainerNames.matches(containers, "List"))
    assert(!ContainerNames.matches(containers, "Chain"))
    assert(!ContainerNames.isWildcard(containers))
  }

  test("the wildcard matches any named constructor") {
    val containers = List(ContainerNames.Wildcard)
    assert(ContainerNames.isWildcard(containers))
    assert(ContainerNames.matches(containers, "Chain"))
    assert(ContainerNames.matches(containers, "NonEmptyList"))
  }

  /** The last segment of a symbol is not always a type name, and under the
    * wildcard every one of these was appended to a call site's type arguments:
    * `recording[F, Delegate#empty()](outDir, ...)`.
    */
  test("the wildcard matches only names a type argument can be written with") {
    val containers = List(ContainerNames.Wildcard)
    assert(!ContainerNames.matches(containers, ""))
    assert(!ContainerNames.matches(containers, "Delegate#empty()"))
    assert(!ContainerNames.matches(containers, "[F]"))
    assert(!ContainerNames.matches(containers, "Stream[F, *]"))
  }

  test("the empty list still means nothing, not everything") {
    assert(!ContainerNames.matches(Nil, "List"))
    assert(!ContainerNames.isWildcard(Nil))
  }
}
