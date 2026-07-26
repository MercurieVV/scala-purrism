package fix.prefercats

import scala.meta._

/** `Tree => IR`, shared by the Cats-side index generator (this phase) and,
  * later, project-side candidate extraction (out of scope here). Cats source
  * ships no SemanticDB (see docs/PREFER_CATS_FUNCTIONS.md / #96), so free-name
  * resolution here is a purpose-built local resolver: bound names come from the
  * enclosing lambda/def's own params, and free names resolve against a
  * caller-supplied member table (the owner trait's own methods plus whatever it
  * inherits) -- never guessed. A free name absent from that table fails
  * normalization rather than being silently kept as text, because an unresolved
  * name has no symbol identity and could alias anything (contract E1/structural
  * safety in #96).
  */
object Normalizer:

  final case class UnresolvedName(name: String)
      extends Exception(s"cannot resolve free name: $name")

  /** name -> resolved symbol, e.g. "as" -> "cats/Functor#as()." */
  type MemberTable = Map[String, String]

  /** Innermost-first list of names currently bound by enclosing lambdas. */
  private type Scope = List[String]

  /** `initialScope` seeds the def's own value-parameter names (innermost of the
    * def frame, not a nested lambda) so references to them resolve to `Bound`
    * slots rather than failing as unresolved free names.
    */
  def normalize(
      term: Term,
      initialScope: List[String],
      members: MemberTable
  ): IR =
    go(term, initialScope, members)

  private def go(term: Term, scope: Scope, members: MemberTable): IR =
    term match
      case Term.Block((stat: Term) :: Nil) =>
        go(stat, scope, members)

      case Term.Name(value) =>
        scope.indexOf(value) match
          case -1 =>
            members.get(value) match
              case Some(sym) => IR.Ref(Slot.Free(sym))
              case None      => throw UnresolvedName(value)
          case idx => IR.Ref(Slot.Bound(idx))

      case Term.Select(qual, name) =>
        IR.Sel(go(qual, scope, members), name.value)

      case Term.Apply.After_4_6_0(fn, args) =>
        // The callee's own by-name-ness is a property of its resolved
        // signature, not of the argument syntax at the call site; none of
        // the milestone-1 bodies call through a by-name parameter position,
        // so this is a safe default rather than a guess for this subset.
        val fnIr = go(fn, scope, members)
        IR.App(fnIr, args.map(go(_, scope, members)), args.map(_ => false))

      case Term.Function.After_4_6_0(params, body) =>
        val names = params.map(_.name.value)
        IR.Lam(params.length, go(body, names ::: scope, members))

      case _: Lit =>
        IR.Lit

      case other =>
        throw new IllegalArgumentException(
          s"Normalizer: unsupported node ${other.productPrefix}: $other"
        )

  /** Collects `name -> "$owner/$name()."` for every top-level `def` directly
    * declared in a trait's own template body (not its companion object's
    * `Ops`/`AllOps` boilerplate) -- both concrete (`Defn.Def`) and abstract
    * (`Decl.Def`, e.g. `Foldable#foldLeft`/`Traverse#traverse`, which have no
    * body of their own but must still resolve as free names in callers). Used
    * to build the free-name resolution table for a set of Cats owner traits.
    */
  def ownDefMembers(
      owner: String,
      traitTemplateStats: List[Stat]
  ): MemberTable =
    traitTemplateStats.collect {
      case d: Defn.Def => d.name.value -> s"cats/$owner#${d.name.value}()."
      case d: Decl.Def => d.name.value -> s"cats/$owner#${d.name.value}()."
    }.toMap
