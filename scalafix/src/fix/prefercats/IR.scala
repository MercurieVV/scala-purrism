package fix.prefercats

/** Normalized representation shared by the Cats-side index (task #99) and
  * project-side candidate extraction (this phase). See
  * docs/PREFER_CATS_FUNCTIONS.md §1-3 for the erasure/preservation contract
  * this shape encodes structurally: binders are de Bruijn indices
  * (alpha-equivalence is representational, not checked), free names carry
  * resolved symbols (capture cannot be introduced), and argument order/count is
  * positional (evaluation order and count are preserved by construction, never
  * reordered or deduped).
  */
enum Slot:
  case Bound(deBruijn: Int)
  case Free(sym: String)

enum IR:
  case Ref(slot: Slot)
  case Sel(recv: IR, sym: String)
  case App(fn: IR, args: List[IR], byName: List[Boolean])
  case Lam(arity: Int, body: IR)
  case Lit

object IR:

  /** Deterministic prefix-form string used both as the hash preimage and as a
    * human-reviewable diff artifact (gunzipped NDJSON stays greppable).
    */
  def canonical(ir: IR): String = ir match
    case Ref(Slot.Bound(i))  => s"#$i"
    case Ref(Slot.Free(sym)) => s"@$sym"
    case Sel(recv, sym)      => s"(.${sym} ${canonical(recv)})"
    case App(fn, args, byName) =>
      val rendered = args
        .zipAll(byName, IR.Lit, false)
        .map { case (a, bn) => (if bn then "~" else "") + canonical(a) }
        .mkString(" ")
      s"(${canonical(fn)} $rendered)"
    case Lam(arity, body) => s"(lam$arity ${canonical(body)})"
    case Lit              => "_"

  /** Rewrites a typeclass's internal call style into the call style project
    * code is written in.
    *
    * Inside `cats.Functor`, `void` is `as(fa)(())` -- an unqualified call to a
    * sibling member, which normalizes to `App(Ref(Free("cats/Functor#as().")),
    * ...)`. A project writes the same thing as `fa.as(())`, which normalizes to
    * `App(Sel(fa, "as"), ...)`, because [[Normalizer]] keeps member names as
    * text rather than resolving them to a symbol. Without this conversion the
    * two forms never match and the index can only ever fire on code that
    * happens to be written in typeclass-summoner style, which is the opposite
    * of the code this rule exists to find.
    *
    * The first explicit argument becomes the receiver, matching how
    * `cats.syntax` exposes every one of these methods.
    */
  def receiverize(ir: IR): IR =
    def memberName(sym: String): Option[String] =
      // "cats/Functor#as()." -> "as"
      val hash = sym.indexOf('#')
      if hash < 0 || !sym.startsWith("cats/") then None
      else
        val rest = sym.drop(hash + 1).stripSuffix(".").stripSuffix("()")
        if rest.isEmpty then None else Some(rest)

    ir match
      case App(Ref(Slot.Free(sym)), args, byName) if args.nonEmpty =>
        memberName(sym) match
          case Some(name) =>
            val recv :: rest = args.map(receiverize): @unchecked
            App(Sel(recv, name), rest, byName.drop(1))
          case None =>
            App(Ref(Slot.Free(sym)), args.map(receiverize), byName)

      // Curried: `foldM(fa, z)(f)` normalizes as an application of an
      // application, and only the innermost one carries the symbol.
      case App(inner @ App(Ref(Slot.Free(_)), _, _), outer, byName) =>
        receiverize(inner) match
          case App(sel @ Sel(_, _), innerArgs, innerByName) =>
            App(sel, innerArgs ++ outer.map(receiverize), innerByName ++ byName)
          case other => App(other, outer.map(receiverize), byName)

      case App(fn, args, byName) =>
        App(receiverize(fn), args.map(receiverize), byName)
      case Sel(recv, sym)   => Sel(receiverize(recv), sym)
      case Lam(arity, body) => Lam(arity, receiverize(body))
      case other            => other

  /** A body with no call structure of its own: a literal, a bare reference, or
    * a lambda around one.
    *
    * `Reducible#isEmpty` is `false` and normalizes to `_`, which is the same IR
    * as every `()`-returning stub in a project. Neither side of a match is
    * meaningful when one of them carries no structure, so both the index
    * generator and the rule drop these outright.
    */
  def isTrivial(ir: IR): Boolean = ir match
    case Lit          => true
    case Ref(_)       => true
    case Lam(_, body) => isTrivial(body)
    case Sel(recv, _) => isTrivial(recv)
    case App(_, _, _) => false

  /** Whether a body mentions a literal anywhere.
    *
    * [[Lit]] deliberately erases the literal's *value*, so `as(fa)(())` and
    * `as(fa)(false)` normalize identically. That is harmless when both sides
    * are being compared for shape, and unsound when one side is an index entry
    * driving a rewrite: `Functor#void` would match
    * `logger.trace(...).as(false)` and rewrite it to `.void`, changing
    * `F[Boolean]` into `F[Unit]`. Until literals carry their value, a Cats body
    * containing one cannot be matched safely. (`SimplifyCatsExpressions` still
    * handles `.as(())` -> `.void`; it inspects the literal directly rather than
    * through the IR.)
    */
  def containsLiteral(ir: IR): Boolean = ir match
    case Lit          => true
    case Ref(_)       => false
    case Sel(recv, _) => containsLiteral(recv)
    case Lam(_, body) => containsLiteral(body)
    case App(fn, args, _) =>
      containsLiteral(fn) || args.exists(containsLiteral)

  /** One call, passing its own parameters straight through in order -- a
    * rename, not a simplification.
    *
    * `Functor#fmap` is `map(fa)(f)`; indexing it means rewriting `fa.map(f)`
    * into `fa.fmap(f)`, which is not an improvement, and the same shape covers
    * the operator aliases (`<*>` for `ap`) and the deprecated spellings
    * (`unorderedFold` for `fold`). A call that drops, reorders, duplicates or
    * synthesizes an argument is not an alias and stays.
    */
  def isAlias(ir: IR): Boolean =
    def passthrough(args: List[IR]): Boolean =
      val bound = args.collect { case Ref(Slot.Bound(i)) => i }
      bound.length == args.length && bound == bound.sorted && bound.distinct == bound

    ir match
      case App(Ref(Slot.Free(_)), args, _) => passthrough(args)
      case App(App(Ref(Slot.Free(_)), inner, _), outer, _) =>
        passthrough(inner ++ outer)
      // Receiver form (see `receiverize`): the receiver is argument zero.
      case App(Sel(recv, _), args, _) => passthrough(recv :: args)
      case App(App(Sel(recv, _), inner, _), outer, _) =>
        passthrough((recv :: inner) ++ outer)
      case Sel(Ref(Slot.Bound(_)), _) => true
      case _                          => false

  def hash(ir: IR): Long =
    val text = canonical(ir)
    val h1 = scala.util.hashing.MurmurHash3.stringHash(text, 0xcafebabe)
    val h2 = scala.util.hashing.MurmurHash3.stringHash(text, 0x1badd00d)
    (h1.toLong << 32) | (h2.toLong & 0xffffffffL)

  /** Splits the content of a canonical-form parenthesized group into its
    * top-level whitespace-separated tokens, treating nested `(...)` groups as
    * opaque (their internal spaces do not split).
    */
  private def splitTopLevel(s: String): List[String] =
    val tokens = List.newBuilder[String]
    val current = new StringBuilder
    var depth = 0
    for (c <- s)
      c match
        case '(' =>
          depth += 1
          current += c
        case ')' =>
          depth -= 1
          current += c
        case ' ' if depth == 0 =>
          if current.nonEmpty then
            tokens += current.toString
            current.clear()
        case other => current += other
    if current.nonEmpty then tokens += current.toString
    tokens.result()

  /** Inverse of [[canonical]]. Total over every string [[canonical]] can
    * produce; throws on anything else.
    */
  def parse(text: String): IR =
    val s = text.trim
    if s == "_" then Lit
    else if s.startsWith("#") then Ref(Slot.Bound(s.drop(1).toInt))
    else if s.startsWith("@") then Ref(Slot.Free(s.drop(1)))
    else if s.startsWith("(") && s.endsWith(")") then
      val inner = s.substring(1, s.length - 1)
      splitTopLevel(inner) match
        case head :: rest if head.startsWith(".") =>
          rest match
            case recv :: Nil => Sel(parse(recv), head.drop(1))
            case _ =>
              throw new IllegalArgumentException(s"malformed Sel: $text")
        case head :: rest if head.matches("lam\\d+") =>
          rest match
            case body :: Nil => Lam(head.drop(3).toInt, parse(body))
            case _ =>
              throw new IllegalArgumentException(s"malformed Lam: $text")
        case fnToken :: argTokens =>
          val byName = argTokens.map(_.startsWith("~"))
          val args = argTokens.map(t =>
            parse(if t.startsWith("~") then t.drop(1) else t)
          )
          App(parse(fnToken), args, byName)
        case Nil =>
          throw new IllegalArgumentException(s"malformed IR: $text")
    else throw new IllegalArgumentException(s"malformed IR: $text")
