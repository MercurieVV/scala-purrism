package fix.prefercats

import scala.meta._

/** A subtree of project source that is shaped like something the equivalence
  * engine (out of scope here) might later match against the Cats index.
  *
  * @param term
  *   the candidate body, normalizable via [[Normalizer.normalize]]
  * @param kind
  *   which extraction rule produced this candidate, for diagnostics
  * @param usable
  *   false if [[CandidateExtractor.unusableReason]] found mutation, `throw`,
  *   `return`, or an unsafe cast -- callers must skip normalizing/matching an
  *   unusable candidate rather than silently equating away the very effects
  *   docs/PREFER_CATS_FUNCTIONS.md §2 requires preserving
  * @param unusableReason
  *   a short tag for why, when `usable` is false
  */
final case class Candidate(
    term: Term,
    kind: String,
    usable: Boolean,
    unusableReason: Option[String]
)

/** Finds candidate subtrees in project source (`doc.tree`) for later comparison
  * against the Cats index. Matching, ranking, and rewriting are out of scope
  * here -- see docs/PREFER_CATS_FUNCTIONS.md.
  *
  * Suppresses nested candidates once an enclosing one has already matched
  * (largest-wins), the same policy `DuplicationAnalyzer` uses for duplicate
  * subtree discovery: a method body that itself contains a `for` comprehension
  * is reported once, as the method body, not twice.
  */
object CandidateExtractor:

  private val ChainMethodNames =
    Set("map", "flatMap", "fold", "foldLeft", "foldRight")

  /** The candidate `Term` a tree node represents, if it is a candidate root at
    * all. `Defn.Def`/`Term.Function` report their *body*, not the
    * declaration/lambda node itself, since the body is what normalizes.
    */
  private def candidateOf(t: Tree): Option[(Term, String)] = t match
    case d: Defn.Def                        => Some((d.body, "method"))
    case Term.Function.After_4_6_0(_, body) => Some((body, "lambda"))
    case b: Term.Block                      => Some((b, "block"))
    case forT: Term.For                     => Some((forT, "for"))
    case forT: Term.ForYield                => Some((forT, "for"))
    case m: Term.Match                      => Some((m, "match"))
    case a @ Term.Apply.After_4_6_0(Term.Select(_, Term.Name(name)), _)
        if ChainMethodNames(name) =>
      Some((a, "chain"))
    case _ => None

  /** Mutation, `throw`, `return`, or an unsafe cast anywhere within the
    * candidate, in that priority order. `None` if the candidate carries none of
    * these.
    */
  def unusableReason(term: Term): Option[String] =
    term.collect {
      case _: Defn.Var    => "mutation"
      case _: Term.Assign => "mutation"
      case _: Term.Throw  => "throw"
      case _: Term.Return => "return"
      case Term.ApplyType.After_4_6_0(
            Term.Select(_, Term.Name("asInstanceOf")),
            _
          ) =>
        "unsafe-cast"
    }.headOption

  /** All candidates in `tree`, largest-wins: once a node matches, its children
    * are still walked (to find sibling candidates elsewhere in the subtree that
    * are not nested inside *this* match, e.g. a helper method declared
    * alongside another) but any node nested directly inside the matched term is
    * suppressed.
    */
  def extract(tree: Tree): List[Candidate] =
    val results = List.newBuilder[Candidate]

    def walk(t: Tree, suppressed: Boolean): Unit =
      candidateOf(t) match
        case Some((term, kind)) if !suppressed =>
          val reason = unusableReason(term)
          results += Candidate(term, kind, reason.isEmpty, reason)
          t.children.foreach(walk(_, suppressed = true))
        case _ =>
          t.children.foreach(walk(_, suppressed))

    walk(tree, suppressed = false)
    results.result()
