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
