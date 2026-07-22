package fix

import scala.meta._

import metaconfig.ConfDecoder
import metaconfig.Configured
import scalafix.v1._

import fix.arrow.ArrowIR
import fix.arrow.ArrowNormalize
import fix.arrow.ArrowParser
import fix.arrow.ArrowRender
import fix.arrow.KleisliScope
import fix.arrow.KleisliType
import fix.arrow.ReadabilityBudget

/** A fan-out near-miss where both `.run`/`.apply` arguments are spelled like
  * the arrow input but the second resolves to a different binding -- an inner
  * scope shadowed the input first. Reported rather than silently skipped so a
  * reader knows the near-miss was seen and rejected, not simply never
  * recognised.
  *
  * Stays `Warning` for the same reason as [[ArrowBudgetDiagnostic]]: a lint
  * *error* makes scalafix withhold every patch in the file.
  */
final case class FanOutShadowedInputDiagnostic(
    override val position: scala.meta.inputs.Position
) extends Diagnostic {
  override def message: String =
    "Both branches call .run/.apply with an argument spelled like this arrow's " +
      "input, but the second resolves to a different binding (likely shadowed " +
      "in an inner scope). Not rewriting to `&&&`, since the two Kleislis would " +
      "no longer run on the same input."
  override def severity: scalafix.lint.LintSeverity =
    scalafix.lint.LintSeverity.Warning
}

/** Emitted when a body was recognised as composable but declined by the
  * readability budget -- so a reader knows the shape *was* seen and rejected on
  * purpose, not merely never matched.
  *
  * `Diagnostic` defaults to `LintSeverity.Error`, and scalafix withholds a
  * rule's patches for a whole file that reports a lint *error*, which would
  * silently turn every other rewrite in that file into a no-op. It therefore
  * stays a `Warning`.
  */
final case class ArrowBudgetDiagnostic(
    override val position: scala.meta.inputs.Position,
    reason: String
) extends Diagnostic {
  override def message: String =
    s"This Kleisli body could be written point-free, but the rule declined: " +
      s"$reason."
  override def severity: scalafix.lint.LintSeverity =
    scalafix.lint.LintSeverity.Warning
}

/** Rewrites hand-threaded Kleisli code into point-free `Arrow` composition.
  *
  * The body of a Kleisli-typed value --
  * `Kleisli { x => for { ... } yield ... }` -- is parsed into an
  * [[fix.arrow.ArrowIR]], normalized, checked against a readability budget, and
  * rendered as `>>>` / `&&&` / `|||` / `.map`. Two entry points feed the same
  * engine:
  *
  *   - **body-only:** any `Kleisli { x => ... }` expression is rewritten in
  *     place, leaving the enclosing signature untouched. This is what reaches
  *     the idiomatic corpus, where composition lives inside Kleisli lambdas of
  *     defs already typed `Kleisli`/`-->`.
  *   - **signature-lifting:** `def m(x: A): F[B] = <monadic body>` becomes
  *     `def m: Kleisli[F, A, B] = <point-free>`, for code that has not yet
  *     adopted the Kleisli return type.
  *
  * The two never overlap: the parser only recognises monadic spines
  * (`for`/`flatMap`), so a def whose body is itself a `Kleisli { ... }` is not
  * a signature-lifting candidate -- the body-only entry owns its interior.
  *
  * Kleisli identity is decided by [[fix.arrow.KleisliType]] via SemanticDB,
  * never by matching the token `Kleisli` or an alias spelling, per
  * `docs/RULES.md`.
  */
/** `PreferArrow.aggressive = true` opts a codebase into lifting plain effectful
  * `for` generators into Kleislis and fanning them out, accepting busier
  * point-free output for wider coverage. Off by default, so the conservative
  * budget governs unless a project asks otherwise.
  */
final case class PreferArrowConfig(aggressive: Boolean = false)

object PreferArrowConfig {
  val default: PreferArrowConfig = PreferArrowConfig()
  implicit val decoder: ConfDecoder[PreferArrowConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("aggressive")(default.aggressive)
        .map(PreferArrowConfig(_))
    }
}

final class PreferArrow(
    config: PreferArrowConfig,
    classpath: List[java.nio.file.Path]
) extends SemanticRule("PreferArrow") {

  def this() = this(PreferArrowConfig.default, Nil)

  private val aggressive: Boolean = config.aggressive

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferArrow")(PreferArrowConfig.default)
      // `--semanticdb-targetroots` is prepended to the scalac classpath by the
      // CLI, so the payloads arrive here without a second configuration key --
      // the same route `PreferKleisli` takes to its cross-file scope.
      .map(new PreferArrow(_, configuration.scalacClasspath.map(_.toNIO)))

  /** Built once per rule instance, not per file: the scan parses every payload
    * in the project, and a rule instance outlives the documents it is applied
    * to.
    */
  private lazy val scope: KleisliScope = KleisliScope.load(classpath)

  /** Defs the project hands over unapplied somewhere, which therefore cannot
    * become Kleislis -- see [[KleisliLiftScope.valueReferences]]. Signature
    * lifting changes a declaration other files call, so the veto has to be
    * computed from the whole project; a per-file view cannot see the reference.
    *
    * The body-only entry needs no such check: it rewrites inside an expression
    * and leaves every signature exactly as it was.
    */
  private lazy val liftVetoed: Set[String] =
    if (classpath.isEmpty) Set.empty
    else {
      val index = _root_.fix.opaque.SemanticdbIndex.load(classpath)
      KleisliLiftScope.valueReferences(
        PropagateOpaqueType.inferSourceroot(index, classpath),
        index
      )
    }

  override def fix(implicit doc: SemanticDocument): Patch = {
    KleisliScope.install(scope)
    doc.tree.collect {
      case applyTerm: Term.Apply if KleisliType.isKleisliApply(applyTerm) =>
        PreferArrow.rewriteBody(applyTerm, aggressive)
      case defn: Defn.Def if !liftVetoed.contains(defn.name.symbol.value) =>
        PreferArrow.rewriteSignature(defn, aggressive)
    }.asPatch
  }
}

object PreferArrow {

  /** `import cats.syntax.<pkg>._`, built as an AST rather than via the
    * `importer"..."` quasiquote macro, which Scala 2 macros -- and this project
    * targets Scala 3 -- cannot run. Which packages a given rewrite needs is
    * decided per operator in [[syntaxImports]].
    */
  private def catsSyntaxImporter(pkg: String): Importer =
    Importer(
      Term.Select(
        Term.Select(Term.Name("cats"), Term.Name("syntax")),
        Term.Name(pkg)
      ),
      List(Importee.Wildcard())
    )

  private val ComposeSyntaxImporter: Importer = catsSyntaxImporter("compose")
  private val ArrowSyntaxImporter: Importer = catsSyntaxImporter("arrow")
  private val ChoiceSyntaxImporter: Importer = catsSyntaxImporter("choice")
  private val ApplySyntaxImporter: Importer = catsSyntaxImporter("apply")
  private val FlatMapSyntaxImporter: Importer = catsSyntaxImporter("flatMap")

  private def renderModifiers(mods: List[Mod]): String =
    mods.map(_.syntax + " ").mkString

  /** The lambda of a `Kleisli { x => body }`, when it has exactly one plain
    * parameter -- the arrow's input.
    */
  private def kleisliLambda(applyTerm: Term.Apply): Option[Term.Function] =
    applyTerm.argClause.values match {
      case List(fn: Term.Function)                   => singleParam(fn)
      case List(Term.Block(List(fn: Term.Function))) => singleParam(fn)
      case _                                         => None
    }

  private def singleParam(fn: Term.Function): Option[Term.Function] =
    fn.paramClause.values match {
      case List(param) if param.name.is[Term.Name] => Some(fn)
      case _                                       => None
    }

  // ---- body-only entry ----------------------------------------------------

  def rewriteBody(
      applyTerm: Term.Apply,
      aggressive: Boolean
  )(implicit doc: SemanticDocument): Patch =
    kleisliLambda(applyTerm) match {
      case None => Patch.empty
      case Some(fn) =>
        val param = fn.paramClause.values.head
        val inputParam = param.name
        val inputSymbol = inputParam.symbol
        val input = param.decltpe
          .map(_.syntax)
          .orElse(enclosingKleisliInputType(applyTerm))
          .map(tpe =>
            ArrowParser.Input(
              inputParam.value,
              tpe,
              enclosingKleisliEffectType(applyTerm)
            )
          )
        compile(fn.body, inputSymbol, input, aggressive) match {
          case Compiled.Emit(rendered, imports) =>
            Patch.replaceTree(applyTerm, rendered) +
              imports.map(Patch.addGlobalImport).asPatch
          case Compiled.Warn(reason) =>
            Patch.lint(ArrowBudgetDiagnostic(applyTerm.pos, reason))
          case Compiled.Skip =>
            ArrowParser
              .shadowedInput(fn.body, inputParam.value, inputSymbol)
              .map(pos => Patch.lint(FanOutShadowedInputDiagnostic(pos)))
              .getOrElse(Patch.empty)
        }
    }

  // ---- signature-lifting entry --------------------------------------------

  def rewriteSignature(
      defn: Defn.Def,
      aggressive: Boolean
  )(implicit doc: SemanticDocument): Patch =
    (for {
      param <- singlePlainParameter(defn)
      returnType <- defn.decltpe.collect { case tpe: Type.Apply => tpe }
      effect <- effectAndResult(returnType)
      body <- defn.body match {
        // A def whose body is itself a `Kleisli { ... }` belongs to the
        // body-only entry; do not also lift its signature.
        case applyTerm: Term.Apply if KleisliType.isKleisliApply(applyTerm) =>
          None
        case other => Some(other)
      }
    } yield (param, effect._1, effect._2)) match {
      case None => Patch.empty
      case Some((param, effectType, resultType)) =>
        compile(
          defn.body,
          param.name.symbol,
          inputOf(param, effectType),
          aggressive
        ) match {
          case Compiled.Emit(rendered, imports) =>
            val parameterType = param.decltpe.map(_.syntax).getOrElse("")
            val signature =
              s"""${renderModifiers(
                  defn.mods
                )}def ${defn.name.syntax}${typeParamSyntax(
                  defn
                )}${implicitClauseSyntax(
                  defn
                )}: Kleisli[$effectType, $parameterType, $resultType] =
                 |  $rendered""".stripMargin
            Patch.replaceTree(defn, signature) +
              Patch.addGlobalImport(Symbol("cats/data/Kleisli#")) +
              imports.map(Patch.addGlobalImport).asPatch
          case Compiled.Warn(reason) =>
            Patch.lint(ArrowBudgetDiagnostic(defn.body.pos, reason))
          case Compiled.Skip =>
            ArrowParser
              .shadowedInput(defn.body, param.name.value, param.name.symbol)
              .map(pos => Patch.lint(FanOutShadowedInputDiagnostic(pos)))
              .getOrElse(Patch.empty)
        }
    }

  /** The def's own type parameters, kept verbatim.
    *
    * Dropping them is not cosmetic: the effect constructor of the new
    * `Kleisli[F, A, B]` return type is almost always one of them, so a lifted
    * `def progress[F[_]: Sync](m: String): F[Unit]` that loses its clause
    * becomes `def progress: Kleisli[F, String, Unit]` with `F` unbound, and the
    * project stops compiling.
    */
  private def typeParamSyntax(defn: Defn.Def): String =
    defn.paramClauseGroups.headOption
      .map(_.tparamClause)
      .filter(_.values.nonEmpty)
      .map(_.syntax)
      .getOrElse("")

  /** The `implicit`/`using` clauses, kept after the rewritten def's now-absent
    * explicit clause. A context bound desugars into one of these, so the same
    * unbound-`F` failure follows from dropping them.
    */
  private def implicitClauseSyntax(defn: Defn.Def): String =
    defn.paramClauseGroups.headOption.toList
      .flatMap(_.paramClauses)
      .filter(_.mod.nonEmpty)
      .map(_.syntax)
      .mkString

  /** One plain value parameter and nothing else in the clause. Type parameters
    * on the def are allowed -- `def m[F[_]: Sync](x: A): F[B]` is the norm, not
    * a disqualifier, which the shipped rule got backwards.
    */
  private def singlePlainParameter(defn: Defn.Def): Option[Term.Param] =
    defn.paramClauseGroups match {
      case List(group) if group.paramClauses.length == 1 =>
        group.paramClauses.head.values match {
          case List(param)
              if param.mods.isEmpty && param.default.isEmpty &&
                param.decltpe.exists(!_.is[Type.Repeated]) =>
            Some(param)
          case _ => None
        }
      case _ => None
    }

  /** The effect constructor and result type of a `F[B]`-shaped return, as
    * rendered strings. Any single-argument type application qualifies -- the
    * shipped rule required the constructor to be spelled literally `F`, which
    * excluded every real return type.
    */
  private def effectAndResult(
      returnType: Type.Apply
  ): Option[(String, String)] =
    returnType.argClause.values match {
      case List(result) => Some((returnType.tpe.syntax, result.syntax))
      case _            => None
    }

  // ---- shared engine ------------------------------------------------------

  private sealed trait Compiled
  private object Compiled {
    final case class Emit(rendered: String, imports: List[Importer])
        extends Compiled
    final case class Warn(reason: String) extends Compiled
    case object Skip extends Compiled
  }

  /** The input type for a `def m(x: A): F[B]` signature-lifting candidate --
    * the parameter's own declared type.
    */
  private def inputOf(
      param: Term.Param,
      effectType: String
  ): Option[ArrowParser.Input] =
    param.decltpe.map(tpe =>
      ArrowParser.Input(param.name.value, tpe.syntax, Some(effectType))
    )

  /** The input type of a `Kleisli { x => ... }` body, read from the second type
    * argument of the enclosing declaration's `Kleisli[F, A, B]` return type
    * (through the `-->`/`Flow` aliases too). Used to annotate a projection
    * lambda; a projected fan-out is only offered when this resolves.
    */
  private def enclosingKleisliInputType(applyTerm: Term.Apply): Option[String] =
    applyTerm.parent
      .flatMap(enclosingDeclType)
      .flatMap(kleisliInputArg)

  /** The `F` in a written `Kleisli[F, A, B]` / `-->[F, A, B]` / `Flow[F][A, B]`
    * enclosing return type -- the effect constructor, needed to emit a typed
    * `Kleisli.ask[F, A]` in aggressive mode.
    */
  private def enclosingKleisliEffectType(
      applyTerm: Term.Apply
  ): Option[String] =
    applyTerm.parent
      .flatMap(enclosingDeclType)
      .flatMap(kleisliEffectArg)

  private def kleisliEffectArg(tpe: Type): Option[String] =
    tpe match {
      case applied: Type.Apply =>
        (applied.tpe, applied.argClause.values) match {
          // `Kleisli[F, A, B]` / `-->[F, A, B]` -- the effect is the first arg.
          case (_, List(f, _, _)) => Some(f.syntax)
          // `Flow[F][A, B]` -- the effect is the alias's own argument.
          case (inner: Type.Apply, List(_, _)) =>
            inner.argClause.values match {
              case List(f) => Some(f.syntax)
              case _       => None
            }
          case _ => None
        }
      case _ => None
    }

  private def enclosingDeclType(tree: Tree): Option[Type] =
    tree match {
      case defn: Defn.Def   => defn.decltpe.orElse(ascend(defn))
      case valDef: Defn.Val => valDef.decltpe.orElse(ascend(valDef))
      case other            => ascend(other)
    }

  private def ascend(tree: Tree): Option[Type] =
    tree.parent.flatMap(enclosingDeclType)

  /** The `A` in a written `Kleisli[F, A, B]`, `-->[F, A, B]` or `Flow[F][A, B]`
    * return type -- the second type argument in the first two, the first in the
    * curried alias.
    */
  private def kleisliInputArg(tpe: Type): Option[String] =
    tpe match {
      case applied: Type.Apply =>
        (applied.tpe, applied.argClause.values) match {
          // `Kleisli[F, A, B]` / `-->[F, A, B]` -- the input is the middle arg.
          case (_, List(_, a, _)) => Some(a.syntax)
          // `Flow[F][A, B]` -- the curried alias, input is the first of two.
          case (_: Type.Apply, List(a, _)) => Some(a.syntax)
          case _                           => None
        }
      case _ => None
    }

  private def compile(
      body: Term,
      inputSymbol: Symbol,
      input: Option[ArrowParser.Input],
      aggressive: Boolean
  )(implicit doc: SemanticDocument): Compiled =
    ArrowParser.parse(body, inputSymbol, input, aggressive) match {
      case None => Compiled.Skip
      case Some(rawIr) =>
        val ir = ArrowNormalize(rawIr)
        val rendered = ArrowRender.render(ir)
        ReadabilityBudget.verdict(
          ir,
          rendered.length,
          body.syntax.length,
          aggressive
        ) match {
          case ReadabilityBudget.Accept =>
            Compiled.Emit(rendered, syntaxImports(ir))
          case ReadabilityBudget.Decline(reason) =>
            Compiled.Warn(reason)
        }
    }

  /** The cats syntax imports the rendered arrow needs, one per operator family,
    * since the packages are independent: `>>>` is `cats.syntax.compose._`,
    * `&&&` is `cats.syntax.arrow._`, and `|||` is `cats.syntax.choice._`. A
    * tree that mixes them needs each: `*>` is `cats.syntax.apply._` and
    * `flatTap` is `cats.syntax.flatMap._`.
    *
    * Already-present imports are dropped. `Patch.addGlobalImport` deduplicates
    * the `Symbol` overload but not the `Importer` one, and these packages are
    * routinely imported by the very `for`-comprehension being rewritten --
    * `flatMap` in particular -- so without this the rewrite emits the same
    * wildcard import twice.
    */
  private def syntaxImports(ir: ArrowIR)(implicit
      doc: SemanticDocument
  ): List[Importer] = {
    def has(pred: PartialFunction[ArrowIR, Unit]): Boolean =
      ArrowIR.fold(ir)(false)((acc, node) => acc || pred.isDefinedAt(node))
    val present =
      doc.tree.collect { case importer: Importer => importer.syntax }.toSet
    List(
      Option.when(has { case _: ArrowIR.AndThen => })(ComposeSyntaxImporter),
      Option.when(has { case _: ArrowIR.Merge => })(ArrowSyntaxImporter),
      Option.when(has { case _: ArrowIR.Choice => })(ChoiceSyntaxImporter),
      Option.when(has { case _: ArrowIR.ProductR => })(ApplySyntaxImporter),
      Option.when(has { case _: ArrowIR.FlatTap => })(FlatMapSyntaxImporter)
    ).flatten.filterNot(importer => present.contains(importer.syntax))
  }
}
