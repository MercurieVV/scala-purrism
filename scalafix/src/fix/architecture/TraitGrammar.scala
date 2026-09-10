package fix.architecture

import scala.meta._
import scalafix.v1._

/** Grammar checks for `trait`/abstract `class` bodies. Grows across later tasks
  * (slot form 2, monomorphic-member check, supertype conformance, the
  * ArrowConvert special case).
  */
object TraitGrammar {

  def findings(
      defn: Defn.Trait
  )(implicit doc: SemanticDocument): List[ArchitectureFinding] = {
    val slots = ArrowSlot.declaredOn(defn.tparamClause)
    defn.templ.body.stats.collect {
      case decl: Decl.Def => checkMember(decl.decltpe, decl, slots)
      case decl: Decl.Val => checkMember(decl.decltpe, decl, slots)
      case _: Decl.Type   => None // abstract type members are always allowed
    }.flatten
  }

  private def checkMember(
      decltpe: Type,
      member: Tree,
      slots: List[ArrowSlot]
  )(implicit doc: SemanticDocument): Option[ArchitectureFinding] =
    if (ArrowSlot.isSlotApplication(decltpe, slots)) None
    else if (ArrowSlot.namesConcreteArrow(decltpe))
      Some(
        ArchitectureFinding(
          member,
          s"member names a concrete type `${decltpe.syntax}`; only an abstract " +
            "arrow slot (a type parameter or type member bounded by " +
            "Arrow/Compose/Category) applied to two types is allowed here"
        )
      )
    else
      Some(
        ArchitectureFinding(
          member,
          s"member has non-arrow type `${decltpe.syntax}`; only an abstract " +
            "arrow slot applied to two types, or an abstract type member, is " +
            "allowed here"
        )
      )
}
