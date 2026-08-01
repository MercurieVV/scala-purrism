package fix.prefercats

import scala.meta._

import scalafix.v1._

/** `Tree => IR`, shared by the Cats-side index generator (task #99, no
  * SemanticDB available for Cats source -- see docs/PREFER_CATS_FUNCTIONS.md /
  * #96) and project-side candidate extraction (this phase, SemanticDB
  * available). Both public entry points below funnel into the same recursive
  * core (`go`): tree-shape handling -- let-desugaring, for-desugaring,
  * eta-expansion, alpha-equivalence via de Bruijn indices -- is written once,
  * and only free-name/by-name resolution differs between the two paths (the
  * [[Resolver]] below).
  *
  * Method-call names (`.map`, `.flatMap`, ...) are kept as plain text in
  * [[IR.Sel]] rather than resolved to a SemanticDB symbol. That keeps
  * `for`-desugaring (E2) simple -- desugared calls are synthesized, not real
  * source positions, so they carry no symbol of their own -- at the cost of not
  * erasing import-spelling differences (E5) for arbitrary method calls; E5 is
  * deferred to the matching/ranking phase, which is out of scope here.
  */
object Normalizer:

  final case class UnresolvedName(name: String)
      extends Exception(s"cannot resolve free name: $name")

  final case class UnsupportedShape(description: String)
      extends Exception(s"Normalizer: unsupported node $description")

  /** name -> resolved symbol, e.g. "as" -> "cats/Functor#as()." Used by the
    * Cats-index path, which has no SemanticDB to resolve names against.
    */
  type MemberTable = Map[String, String]

  /** One de Bruijn slot.
    *
    * A slot usually carries one name, but `val (oa, ob) = ior.pad` binds two
    * names to one value: the pair occupies a single slot, and each name is
    * reached through it as `._1`/`._2`. That is exactly how Scala compiles the
    * destructuring, and it cannot be expressed by a scope of bare names.
    */
  private sealed trait Binder
  private object Binder:
    final case class Plain(name: String) extends Binder
    final case class Destructured(names: List[String]) extends Binder

  /** Innermost-first list of slots currently bound by enclosing
    * lambdas/lets/case patterns.
    */
  private type Scope = List[Binder]

  private def plain(names: List[String]): Scope = names.map(Binder.Plain(_))

  private sealed trait Resolver:
    /** Resolved symbol for a bare (non-member-select) free name. Throws
      * [[UnresolvedName]] if the name cannot be resolved -- an unresolved free
      * name has no symbol identity and could alias anything (E1 / structural
      * safety, per #96).
      */
    def freeSymbol(name: Term.Name): String

    /** By-name flags for each of `argCount` positional arguments to a call
      * resolving to `symbol`, per the callee's own first explicit parameter
      * list. Defaults to all-strict when the signature can't be inspected.
      */
    def byNameFlags(symbol: String, argCount: Int): List[Boolean]

    /** Arity of `symbol`'s first explicit parameter list, if `symbol` resolves
      * to a method. Used to eta-expand a bare method reference (E3) into the
      * `Lam` form a direct application would normalize to.
      */
    def methodArity(symbol: String): Option[Int]

  private final class TableResolver(members: MemberTable) extends Resolver:
    def freeSymbol(name: Term.Name): String =
      members.getOrElse(name.value, throw UnresolvedName(name.value))

    // The callee's own by-name-ness and arity are properties of its resolved
    // signature, not of the call-site syntax; the Cats-index path has no
    // SemanticDB to look either up in, so both default conservatively
    // (strict, non-eta-expandable) rather than guessing.
    def byNameFlags(symbol: String, argCount: Int): List[Boolean] =
      List.fill(argCount)(false)

    def methodArity(symbol: String): Option[Int] = None

  private final class SemanticResolver(doc: SemanticDocument) extends Resolver:
    def freeSymbol(name: Term.Name): String =
      name.symbol(using doc) match
        case Symbol.None => throw UnresolvedName(name.value)
        case sym         => sym.value

    private def firstParamList(symbol: String): List[SymbolInformation] =
      Symbol(symbol).info(using doc) match
        case Some(info) =>
          info.signature match
            case MethodSignature(_, paramLists, _) =>
              paramLists.headOption.getOrElse(Nil)
            case _ => Nil
        case None => Nil

    def byNameFlags(symbol: String, argCount: Int): List[Boolean] =
      val flags = firstParamList(symbol).map(_.signature match
        case ValueSignature(ByNameType(_)) => true
        case _                             => false)
      if flags.length >= argCount then flags.take(argCount)
      else flags ++ List.fill(argCount - flags.length)(false)

    def methodArity(symbol: String): Option[Int] =
      Symbol(symbol)
        .info(using doc)
        .flatMap(_.signature match
          case MethodSignature(_, paramLists, _) =>
            Some(paramLists.headOption.getOrElse(Nil).length)
          case _ => None)

  // ---------------------------------------------------------------------
  // Public entry points
  // ---------------------------------------------------------------------

  /** Cats-index path (task #99): no SemanticDB, free names resolved against a
    * caller-supplied member table.
    */
  def normalize(
      term: Term,
      initialScope: List[String],
      members: MemberTable
  ): IR =
    go(term, plain(initialScope), new TableResolver(members))

  /** Project-side path (this phase): SemanticDB available, free names and
    * by-name parameter shapes resolved from the compiler's own signatures.
    */
  def normalize(term: Term, initialScope: List[String])(implicit
      doc: SemanticDocument
  ): IR =
    go(term, plain(initialScope), new SemanticResolver(doc))

  // ---------------------------------------------------------------------
  // Shared core
  // ---------------------------------------------------------------------

  private val ShortCircuitOps = Set("&&", "||")

  private def go(term: Term, scope: Scope, r: Resolver): IR = term match
    case Term.Block(stats) => goBlock(stats, scope, r)

    case name: Term.Name => resolveRef(name, scope, r, calleePosition = false)

    case Term.Select(qual, name) =>
      IR.Sel(go(qual, scope, r), name.value)

    case Term.ApplyInfix.After_4_6_0(lhs, op, _, args) =>
      val fnIr = IR.Sel(go(lhs, scope, r), op.value)
      val flags =
        if ShortCircuitOps(op.value) then args.map(_ => true)
        else args.map(_ => false)
      IR.App(fnIr, args.map(go(_, scope, r)), flags)

    case Term.ApplyUnary(op, operand) =>
      IR.App(IR.Sel(go(operand, scope, r), "unary_" + op.value), Nil, Nil)

    case Term.Apply.After_4_6_0(fn, args) =>
      val fnIr = resolveCallee(fn, scope, r)
      val flags = calleeSymbolOpt(fn, scope, r) match
        case Some(symbol) => r.byNameFlags(symbol, args.length)
        case None         => args.map(_ => false)
      IR.App(fnIr, args.map(go(_, scope, r)), flags)

    case Term.Eta(expr) => go(expr, scope, r)

    case Term.Function.After_4_6_0(params, body) =>
      val names = params.map(_.name.value)
      IR.Lam(params.length, go(body, plain(names) ::: scope, r))

    case Term.If.After_4_4_0(cond, thenp, elsep, _) =>
      IR.App(
        IR.Ref(Slot.Free("scala/`if`")),
        List(go(cond, scope, r), go(thenp, scope, r), go(elsep, scope, r)),
        List(false, true, true)
      )

    case Term.ForYield.After_4_9_9(enums, body) =>
      desugarFor(enums, body, isYield = true, scope, r)

    case Term.For.After_4_9_9(enums, body) =>
      desugarFor(enums, body, isYield = false, scope, r)

    case Term.Match.After_4_9_9(expr, cases, _) =>
      val scrutIr = go(expr, scope, r)
      val caseIrs = cases.map(goCase(_, scope, r))
      IR.App(
        IR.Ref(Slot.Free("scala/`match`")),
        scrutIr :: caseIrs,
        false :: caseIrs.map(_ => true)
      )

    // A type ascription is erased at runtime and cannot change what a term
    // evaluates to, so it must not change its normal form either -- otherwise
    // `f0: F[A0]` and `f0` are different bodies. cats-core's arity boilerplate
    // ascribes almost every argument.
    case Term.Ascribe(expr, _) => go(expr, scope, r)

    // `{ case ... }` in argument position is a one-argument function whose body
    // matches on that argument, which is exactly how Scala compiles it -- so it
    // normalizes to the same IR as the `x => x match { case ... }` a different
    // author would write for the same thing.
    case Term.PartialFunction(cases) =>
      val scrutName = "$pfScrutinee"
      val innerScope = Binder.Plain(scrutName) :: scope
      val caseIrs = cases.map(goCase(_, innerScope, r))
      IR.Lam(
        1,
        IR.App(
          IR.Ref(Slot.Free("scala/`match`")),
          IR.Ref(Slot.Bound(0)) :: caseIrs,
          false :: caseIrs.map(_ => true)
        )
      )

    case _: Lit => IR.Lit

    case other =>
      throw UnsupportedShape(s"${other.productPrefix}: $other")

  /** A bare name used in a non-callee position: if it resolves to a method
    * (arity > 0), it is eta-expanded into the `Lam` a direct application of it
    * would normalize to (E3). In callee position (`calleePosition = true`) it
    * is left as a bare reference, since it is about to be applied anyway.
    */
  private def resolveRef(
      name: Term.Name,
      scope: Scope,
      r: Resolver,
      calleePosition: Boolean
  ): IR =
    lookup(name.value, scope) match
      case None =>
        val symbol = r.freeSymbol(name)
        val ref = IR.Ref(Slot.Free(symbol))
        if calleePosition then ref
        else
          r.methodArity(symbol) match
            case Some(n) if n > 0 =>
              val args = (0 until n).toList.map(i => IR.Ref(Slot.Bound(i)))
              IR.Lam(n, IR.App(ref, args, r.byNameFlags(symbol, n)))
            case _ => ref
      case Some(ir) => ir

  /** The IR a bound name resolves to, or `None` if it is free.
    *
    * A `Destructured` slot holds the whole value, so a name bound by it
    * resolves to a projection out of that slot rather than to the slot itself.
    */
  private def lookup(name: String, scope: Scope): Option[IR] =
    scope.zipWithIndex.collectFirst {
      case (Binder.Plain(`name`), slot) => IR.Ref(Slot.Bound(slot))
      case (Binder.Destructured(names), slot) if names.contains(name) =>
        IR.Sel(IR.Ref(Slot.Bound(slot)), s"_${names.indexOf(name) + 1}")
    }

  private def resolveCallee(fn: Term, scope: Scope, r: Resolver): IR =
    fn match
      case name: Term.Name =>
        resolveRef(name, scope, r, calleePosition = true)
      case other => go(other, scope, r)

  private def calleeSymbolOpt(
      fn: Term,
      scope: Scope,
      r: Resolver
  ): Option[String] =
    fn match
      case name: Term.Name if scope.contains(name.value) => None
      case name: Term.Name => scala.util.Try(r.freeSymbol(name)).toOption
      case _               => None

  /** Every non-final block statement -- a `val` binding or a bare
    * side-effecting expression -- is a let, and a let is an immediately applied
    * lambda: `{ val x = e1; rest }` normalizes the same as `((x) => rest)(e1)`,
    * which both preserves evaluation order/count (P1/P2) for free (the value is
    * normalized exactly once, in argument position) and reuses
    * [[IR.App]]/[[IR.Lam]] without a dedicated `Let` case.
    */
  private def goBlock(stats: List[Stat], scope: Scope, r: Resolver): IR =
    stats match
      case Nil              => IR.Lit
      case (t: Term) :: Nil => go(t, scope, r)
      case Defn.Val(_, List(Pat.Var(name)), _, rhs) :: rest =>
        val valueIr = go(rhs, scope, r)
        val bodyIr = goBlock(rest, Binder.Plain(name.value) :: scope, r)
        IR.App(IR.Lam(1, bodyIr), List(valueIr), List(false))

      // `val (oa, ob) = ior.pad` -- one value, one slot, both names reached
      // through it (see `Binder.Destructured`).
      case Defn.Val(_, List(Pat.Tuple(elements)), _, rhs) :: rest
          if elements.forall(e => e.is[Pat.Var] || e.is[Pat.Wildcard]) =>
        // A discarded element still occupies its position in the tuple, so it
        // gets a name nothing can reference rather than being skipped -- that
        // keeps `._2` meaning the second element in `val (a, _, c) = ...`.
        val names = elements.zipWithIndex.map {
          case (Pat.Var(name), _) => name.value
          case (_, position)      => s"$$discarded$position"
        }
        val valueIr = go(rhs, scope, r)
        val bodyIr = goBlock(rest, Binder.Destructured(names) :: scope, r)
        IR.App(IR.Lam(1, bodyIr), List(valueIr), List(false))

      // A local `def` is a let bound to a lambda; the name is in scope for the
      // rest of the block, and for the def's own body, so it can recurse.
      case (d: Defn.Def) :: rest =>
        val paramNames =
          d.paramClauses.flatMap(_.values.map(_.name.value)).toList
        val selfScope = Binder.Plain(d.name.value) :: scope
        val defIr =
          if paramNames.isEmpty then go(d.body, selfScope, r)
          else
            IR.Lam(
              paramNames.length,
              go(d.body, plain(paramNames) ::: selfScope, r)
            )
        val bodyIr = goBlock(rest, selfScope, r)
        IR.App(IR.Lam(1, bodyIr), List(defIr), List(false))

      // An import has no runtime effect and binds no value, so it occupies no
      // slot -- dropping it keeps `{ import cats.syntax.all.*; expr }` the same
      // body as `expr`.
      case (_: Import) :: rest => goBlock(rest, scope, r)

      case (t: Term) :: rest =>
        // A discarded statement still shifts de Bruijn depth by one, so a
        // placeholder is pushed even though nothing ever references it by
        // name.
        val valueIr = go(t, scope, r)
        val placeholder = s"$$discarded${rest.length}"
        val bodyIr = goBlock(rest, Binder.Plain(placeholder) :: scope, r)
        IR.App(IR.Lam(1, bodyIr), List(valueIr), List(false))
      case other :: _ =>
        throw UnsupportedShape(s"block statement ${other.productPrefix}")

  /** Standard `for`/`flatMap`-`map` desugaring (E2), built directly as IR
    * rather than as a synthetic `Term` tree: a synthesized `.flatMap`/`.map`
    * `Term.Select` would have no source position and thus no SemanticDB symbol,
    * which would make it normalize differently from a hand-written call to the
    * same method. Guards and `case`/`val` enumerators are not supported yet --
    * narrower than the full desugaring, but sufficient for the plain-generator
    * shapes this phase's fixtures pin.
    */
  private def desugarFor(
      enums: List[Enumerator],
      body: Term,
      isYield: Boolean,
      scope: Scope,
      r: Resolver
  ): IR =
    enums match
      case Enumerator.Generator(pat, rhs) :: Nil =>
        val paramName = patVarName(pat)
        val methodName = if isYield then "map" else "foreach"
        val lamBody = go(body, Binder.Plain(paramName) :: scope, r)
        IR.App(
          IR.Sel(go(rhs, scope, r), methodName),
          List(IR.Lam(1, lamBody)),
          List(false)
        )
      case Enumerator.Generator(pat, rhs) :: rest =>
        val paramName = patVarName(pat)
        val innerIr =
          desugarFor(rest, body, isYield, Binder.Plain(paramName) :: scope, r)
        IR.App(
          IR.Sel(go(rhs, scope, r), "flatMap"),
          List(IR.Lam(1, innerIr)),
          List(false)
        )
      case other :: _ =>
        throw UnsupportedShape(s"for-enumerator ${other.productPrefix}")
      case Nil => go(body, scope, r)

  private def patVarName(pat: Pat): String = pat match
    case Pat.Var(name) => name.value
    case other => throw UnsupportedShape(s"for-pattern ${other.productPrefix}")

  private def goCase(c: Case, scope: Scope, r: Resolver): IR =
    val binders = patternBinders(c.pat)
    val newScope = plain(binders) ::: scope
    val bodyIr = go(c.body, newScope, r)
    val arity = binders.length
    c.cond match
      case Some(cond) =>
        val guardIr = go(cond, newScope, r)
        IR.Lam(
          arity,
          IR.App(
            IR.Ref(Slot.Free("scala/`case-guarded`")),
            List(guardIr, bodyIr),
            List(false, false)
          )
        )
      case None => IR.Lam(arity, bodyIr)

  /** Names bound by a pattern, in occurrence order. Unrecognised pattern shapes
    * conservatively contribute no binders rather than failing the whole
    * candidate -- best-effort, matching this phase's `Match` support.
    */
  private def patternBinders(pat: Pat): List[String] = pat match
    case Pat.Var(name)                    => List(name.value)
    case Pat.Bind(Pat.Var(name), sub)     => name.value :: patternBinders(sub)
    case Pat.Typed(p, _)                  => patternBinders(p)
    case Pat.Tuple(args)                  => args.flatMap(patternBinders)
    case Pat.Extract.After_4_6_0(_, args) => args.flatMap(patternBinders)
    case Pat.ExtractInfix.After_4_6_0(lhs, _, rhs) =>
      patternBinders(lhs) ::: rhs.flatMap(patternBinders)
    case Pat.Alternative(lhs, rhs) =>
      patternBinders(lhs) ::: patternBinders(rhs)
    case _ => Nil

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
