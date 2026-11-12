package fix.hkt.tasty

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

import scala.quoted.Quotes
import scala.tasty.inspector.Inspector
import scala.tasty.inspector.Tasty
import scala.tasty.inspector.TastyInspector

/** Rows extracted from library TASTy, in the column order of the Cats index
  * tables (`typeclasses`, `capabilities`, `syntax`).
  *
  * `evidence` rows are `method, typeclasses(comma)`; `aliases` rows are
  * `alias, renderName, importPath, target, fixedArgs(comma)`.
  */
private[fix] final case class InspectedRows(
    typeclasses: List[List[String]],
    capabilities: List[List[String]],
    syntax: List[List[String]],
    evidence: List[List[String]],
    aliases: List[List[String]]
)

/** Reads typeclass facts (hierarchy, method owners, syntax) out of TASTy. */
private[fix] object TypeclassInspector {

  /** Which packages count as typeclass homes and as syntax homes.
    *
    * Both predicates receive a symbol's dotted full name.
    */
  final case class Scope(
      typeclassPackages: String => Boolean,
      syntaxPackages: String => Boolean
  )

  /** Exactly the filters the checked-in Cats index was generated with. */
  val CatsScope: Scope = Scope(
    typeclassPackages = fullName =>
      fullName.startsWith("cats.kernel.") ||
        (fullName.startsWith("cats.") &&
          !fullName.stripPrefix("cats.").contains(".")),
    syntaxPackages = _.startsWith("cats.syntax.")
  )

  /** Every package. */
  val AnyScope: Scope = Scope(_ => true, _ => true)

  private final case class RawTypeclass(
      symbol: String,
      parents: List[String],
      kind: String,
      typeParams: Int,
      renderName: String,
      importPath: String
  )

  private final case class RawCapability(
      typeclass: String,
      method: String,
      owner: String,
      kind: String,
      derived: Boolean,
      arity: Int,
      exits: Boolean
  )

  private final case class RawSyntax(
      syntaxMethod: String,
      owner: String,
      method: String,
      importPath: String
  )

  private final case class Raw(
      typeclasses: List[RawTypeclass],
      capabilities: List[RawCapability],
      syntax: List[RawSyntax],
      evidence: List[List[String]],
      aliases: List[List[String]]
  ) {
    def ++(other: Raw): Raw =
      Raw(
        typeclasses ++ other.typeclasses,
        capabilities ++ other.capabilities,
        syntax ++ other.syntax,
        evidence ++ other.evidence,
        aliases ++ other.aliases
      )
  }

  private val EmptyRaw = Raw(Nil, Nil, Nil, Nil, Nil)

  /** Inspect a single jar. Any failure while reading TASTy becomes `Left`. */
  def inspectJar(
      jar: Path,
      dependencies: List[Path],
      scope: Scope
  ): Either[String, InspectedRows] =
    inspectJars(List(jar), dependencies, scope)

  /** Inspect several jars as one body of TASTy, so that a typeclass in one jar
    * can name a parent that lives in another.
    */
  def inspectJars(
      jars: List[Path],
      dependencies: List[Path],
      scope: Scope
  ): Either[String, InspectedRows] =
    scanJars(jars, dependencies, scope).map(finish)

  /** Rows straight out of TASTy, before [[finish]].
    *
    * Self-contained per scan: a typeclass row names every typeclass parent
    * (also one that lives in a jar outside `jars`), its depth column is `0`,
    * and capability/syntax rows are not yet restricted to known owners. That is
    * what makes rows of separately scanned jars mergeable.
    */
  def scanJars(
      jars: List[Path],
      dependencies: List[Path],
      scope: Scope
  ): Either[String, InspectedRows] = {
    val sink = new AtomicReference[Raw](EmptyRaw)
    catching(
      TastyInspector.inspectAllTastyFiles(
        Nil,
        jars.map(_.toString),
        dependencies.map(_.toString)
      )(new Collector(scope, sink))
    ).flatMap { inspected =>
      if (inspected) Right(rawRows(sink.get()))
      else Left(s"TASTy inspection failed for $jars")
    }
  }

  /** Run `body`, turning any throwable (including `LinkageError`, which is not
    * NonFatal and is what a missing TASTy dependency produces) into Left.
    */
  private[tasty] def catching[A](body: => A): Either[String, A] =
    try Right(body)
    catch {
      case error: Throwable =>
        Left(
          s"TASTy inspection failed: ${error.getClass.getName}: ${error.getMessage}"
        )
    }

  private val TypeclassColumns = 8

  private def rawRows(raw: Raw): InspectedRows =
    InspectedRows(
      raw.typeclasses.map { tc =>
        List(
          tc.symbol,
          tc.parents.mkString(","),
          tc.kind,
          tc.typeParams.toString,
          "0",
          tc.renderName,
          tc.importPath,
          "true"
        )
      },
      raw.capabilities.map { row =>
        List(
          row.typeclass,
          row.method,
          row.owner,
          row.kind,
          row.derived.toString,
          row.arity.toString,
          row.exits.toString
        )
      },
      raw.syntax.map(row =>
        List(row.syntaxMethod, row.owner, row.method, row.importPath)
      ),
      raw.evidence,
      raw.aliases
    )

  /** Post-processing over (possibly merged) [[scanJars]] rows, shared by the
    * index generator and the runtime loader: one row per typeclass, parents
    * restricted to typeclasses present in `rows`, depth recomputed over that
    * hierarchy, capabilities kept only when both typeclass and owner are
    * present, syntax kept only when it targets a kept capability.
    *
    * Idempotent: finished rows pass through unchanged (up to duplicates).
    */
  def finish(rows: InspectedRows): InspectedRows = {
    val uniqueTypeclasses = rows.typeclasses
      .collect {
        case row @ (symbol :: _) if row.size == TypeclassColumns =>
          symbol -> row
      }
      .groupBy(_._1)
      .values
      .flatMap(_.headOption)
      .toList
    val symbols = uniqueTypeclasses.iterator.map(_._1).toSet
    val parentsBySymbol = uniqueTypeclasses.iterator.map { case (symbol, row) =>
      symbol -> row
        .lift(1)
        .toList
        .flatMap(splitList)
        .filter(symbols.contains)
        .distinct
        .sorted
    }.toMap

    def ancestors(symbol: String, seen: Set[String]): Set[String] =
      parentsBySymbol.getOrElse(symbol, Nil).foldLeft(Set.empty[String]) {
        case (acc, parent) if seen(parent) => acc
        case (acc, parent) =>
          acc + parent ++ ancestors(parent, seen + parent)
      }

    val typeclassRows = uniqueTypeclasses.map { case (symbol, row) =>
      row
        .updated(1, parentsBySymbol.getOrElse(symbol, Nil).mkString(","))
        .updated(4, ancestors(symbol, Set(symbol)).size.toString)
    }

    val capabilityRows = rows.capabilities.filter { row =>
      row.headOption.exists(symbols.contains) &&
      row.lift(2).exists(owner => symbols.contains(ownerTypeSymbol(owner)))
    }.distinct

    val capabilityMethods =
      capabilityRows.iterator.flatMap(row => row.lift(2).zip(row.lift(1))).toSet
    val syntaxRows = rows.syntax
      .filter(row => row.lift(1).zip(row.lift(2)).exists(capabilityMethods))
      .distinct

    InspectedRows(
      typeclassRows,
      capabilityRows,
      syntaxRows,
      rows.evidence.distinct,
      rows.aliases.distinct
    )
  }

  /** Distinct rows in the canonical (column-wise lexicographic) order. */
  def sortRows(rows: List[List[String]]): List[List[String]] =
    rows.distinct.sortWith(compareRows(_, _) < 0)

  private def compareRows(left: List[String], right: List[String]): Int =
    left.iterator
      .zip(right.iterator)
      .map { case (a, b) => a.compareTo(b) }
      .find(_ != 0)
      .getOrElse(Integer.compare(left.size, right.size))

  private def splitList(cell: String): List[String] =
    if (cell.isEmpty) Nil else cell.split(",", -1).toList

  private def ownerTypeSymbol(method: String): String = {
    val methodStart = method.lastIndexOf('#')
    if (methodStart < 0) "" else method.substring(0, methodStart + 1)
  }

  private final class Collector(scope: Scope, sink: AtomicReference[Raw])
      extends Inspector {
    override def inspect(using
        quotes: Quotes
    )(
        tastys: List[Tasty[quotes.type]]
    ): Unit = {
      import quotes.reflect.*

      val collector = new TreeAccumulator[List[ClassDef]] {
        override def foldTree(definitions: List[ClassDef], tree: Tree)(
            owner: Symbol
        ): List[ClassDef] = {
          val next = tree match {
            case definition: ClassDef => definition :: definitions
            case _                    => definitions
          }
          foldOverTree(next, tree)(owner)
        }
      }
      val definitions = tastys
        .foldLeft(List.empty[ClassDef])((acc, tasty) =>
          collector.foldTree(acc, tasty.ast)(Symbol.noSymbol)
        )
        .reverse

      val candidates = definitions.filter(d => isTypeclass(d.symbol))
      val distinctCandidates = candidates.distinctBy(_.symbol)

      val aliasCollector = new TreeAccumulator[List[TypeDef]] {
        override def foldTree(definitions: List[TypeDef], tree: Tree)(
            owner: Symbol
        ): List[TypeDef] = {
          val next = tree match {
            case definition: TypeDef if definition.symbol.isAliasType =>
              definition :: definitions
            case _ => definitions
          }
          foldOverTree(next, tree)(owner)
        }
      }
      val aliasDefinitions = tastys.foldLeft(List.empty[TypeDef])(
        (acc, tasty) => aliasCollector.foldTree(acc, tasty.ast)(Symbol.noSymbol)
      )
      val aliasRows = aliasDefinitions.flatMap(aliasRow(_).toList)

      val typeclassEvidence = distinctCandidates.flatMap { definition =>
        definition.symbol.declaredMethods
          .filter(isPublicMethod)
          .flatMap(method =>
            evidenceRow(method, evidenceTypeclasses(method.paramSymss.flatten))
          )
      }
      val syntaxEvidence = definitions
        .filter(definition => scope.syntaxPackages(definition.symbol.fullName))
        .flatMap { definition =>
          val constructorEvidence = evidenceTypeclasses(
            definition.symbol.primaryConstructor.paramSymss.flatten
          )
          definition.symbol.declaredMethods
            .filter(isPublicMethod)
            .flatMap(method =>
              evidenceRow(
                method,
                (constructorEvidence ++ evidenceTypeclasses(
                  method.paramSymss.flatten
                )).distinct
              )
            )
        }

      val perTypeclass = distinctCandidates.map { definition =>
        val tc = definition.symbol
        val typeParameters =
          tc.primaryConstructor.paramSymss.flatten.filter(_.isType)
        val kind = typeParameters.headOption.map(kindOf).getOrElse("Star")
        val parents = definition.parents
          .flatMap {
            case term: Term     => Some(term.tpe.dealias.typeSymbol)
            case tree: TypeTree => Some(tree.tpe.dealias.typeSymbol)
            case _              => None
          }
          // Kept even when the parent lives in a jar outside this scan, so
          // per-jar rows stay mergeable; `finish` drops unknown parents.
          .filter(parent => parent != Symbol.noSymbol && isTypeclass(parent))
          .map(semanticSymbol(_))
          .distinct
        val renderName = tc.name
        val importPath =
          if (tc.fullName.startsWith("cats.kernel.")) s"cats.$renderName"
          else tc.fullName
        val raw = RawTypeclass(
          semanticSymbol(tc),
          parents,
          kind,
          typeParameters.size,
          renderName,
          importPath
        )

        val caps = tc.methodMembers
          .filter(isPublicMethod)
          .filter(method => isTypeclass(methodRoot(method).owner))
          .map { method =>
            val root = methodRoot(method)
            RawCapability(
              semanticSymbol(tc),
              semanticSymbol(method),
              semanticSymbol(root),
              kind,
              root.owner != tc || !method.flags.is(Flags.Deferred),
              method.paramSymss.flatten.count(_.isTerm),
              leavesConstructor(tc, method)
            )
          }
        raw -> caps
      }

      val syntax = definitions
        .filter(definition => scope.syntaxPackages(definition.symbol.fullName))
        .flatMap { definition =>
          val constructorEvidence =
            evidenceTypeclasses(
              definition.symbol.primaryConstructor.paramSymss.flatten
            )
          definition.symbol.declaredMethods
            .filter(isPublicMethod)
            .flatMap { method =>
              val evidence =
                (constructorEvidence ++ evidenceTypeclasses(
                  method.paramSymss.flatten
                )).distinct
              val targets = evidence.flatMap { tc =>
                tc.methodMembers.filter(candidate =>
                  candidate.name == method.name && isPublicMethod(candidate)
                )
              }
              bestTarget(method, targets).map { target =>
                val root = methodRoot(target)
                RawSyntax(
                  semanticSymbol(method),
                  semanticSymbol(root),
                  semanticSymbol(target),
                  syntaxImport(method)
                )
              }.toList
            }
        }

      val _ = sink.updateAndGet(previous =>
        previous ++ Raw(
          perTypeclass.map(_._1),
          perTypeclass.flatMap(_._2),
          syntax,
          typeclassEvidence ++ syntaxEvidence,
          aliasRows
        )
      )
    }

    private def evidenceRow(using
        quotes: Quotes
    )(
        method: quotes.reflect.Symbol,
        typeclasses: List[quotes.reflect.Symbol]
    ): Option[List[String]] =
      Option.when(typeclasses.nonEmpty)(
        List(
          semanticSymbol(method),
          typeclasses.map(semanticSymbol(_)).distinct.mkString(",")
        )
      )

    /** `type A[F[_]] = Typeclass[F, Concrete...]` as an alias row. */
    private def aliasRow(using
        quotes: Quotes
    )(
        definition: quotes.reflect.TypeDef
    ): Option[List[String]] = {
      import quotes.reflect.*

      def concrete(parameter: String)(tpe: TypeRepr): Boolean =
        tpe.dealias match {
          case _: ParamRef    => false
          case _: AppliedType => false
          case _: TypeLambda  => false
          case other =>
            other.typeSymbol != Symbol.noSymbol &&
            other.typeSymbol.name != parameter
        }

      definition.rhs match {
        case lambda: LambdaTypeTree
            if lambda.tparams.size == 1 &&
              (lambda.tparams.head.rhs match {
                case _: LambdaTypeTree => true
                case _                 => false
              }) =>
          (lambda.body match {
            case body: TypeTree => Some(body.tpe.dealias)
            case _              => None
          }) match {
            case Some(AppliedType(constructor, first :: rest))
                if first.typeSymbol.name == lambda.tparams.head.name &&
                  isTypeclass(constructor.typeSymbol) &&
                  rest.forall(concrete(lambda.tparams.head.name)) =>
              val symbol = definition.symbol
              Some(
                List(
                  semanticSymbol(symbol),
                  symbol.name,
                  symbol.fullName
                    .replace(".package$.", ".")
                    .replace(".package.", "."),
                  semanticSymbol(constructor.typeSymbol),
                  rest
                    .map(arg => semanticSymbol(arg.dealias.typeSymbol))
                    .mkString(",")
                )
              )
            case _ => None
          }
        case _ => None
      }
    }

    /** Whether a capability's result leaves the abstracted type constructor.
      *
      * `Functor#map` returns `F[B]` and stays inside it; `Foldable#toList`
      * returns `List[A]` and does not. That difference decides whether the
      * operations *after* a call are still questions about `F`: everything
      * chained onto `xs.toList` is chained onto a genuine `List`, and a rule
      * that widened `xs` must not read them as constraints on the widening.
      *
      * A typeclass with no type parameter (there are none in v1's index) has
      * nothing to leave, and is reported as not leaving.
      */
    private def leavesConstructor(using
        quotes: Quotes
    )(
        typeclass: quotes.reflect.Symbol,
        method: quotes.reflect.Symbol
    ): Boolean = {
      import quotes.reflect.*

      def result(tpe: TypeRepr): TypeRepr =
        tpe match {
          case MethodType(_, _, res) => result(res)
          case PolyType(_, _, res)   => result(res)
          case ByNameType(res)       => result(res)
          case other                 => other
        }

      def mentions(tpe: TypeRepr, parameter: Symbol): Boolean =
        tpe match {
          case AppliedType(constructor, arguments) =>
            mentions(constructor, parameter) ||
            arguments.exists(mentions(_, parameter))
          case AnnotatedType(underlying, _) => mentions(underlying, parameter)
          case Refinement(parent, _, info) =>
            mentions(parent, parameter) || mentions(info, parameter)
          case AndType(left, right) =>
            mentions(left, parameter) || mentions(right, parameter)
          case OrType(left, right) =>
            mentions(left, parameter) || mentions(right, parameter)
          case ByNameType(underlying) => mentions(underlying, parameter)
          case TypeBounds(low, high) =>
            mentions(low, parameter) || mentions(high, parameter)
          case MethodType(_, parameters, res) =>
            parameters.exists(mentions(_, parameter)) ||
            mentions(res, parameter)
          case PolyType(_, bounds, res) =>
            bounds.exists(mentions(_, parameter)) || mentions(res, parameter)
          case TypeLambda(_, bounds, res) =>
            bounds.exists(mentions(_, parameter)) || mentions(res, parameter)
          // By name, not by symbol: the type parameter reached through the
          // primary constructor and the one a member type refers to are two
          // symbols for the same `F`, and only the name survives both.
          case other =>
            other.typeSymbol == parameter ||
            (other.typeSymbol != Symbol.noSymbol &&
              other.typeSymbol.name == parameter.name)
        }

      typeclass.primaryConstructor.paramSymss.flatten
        .filter(_.isType)
        .headOption
        .exists(parameter =>
          !mentions(
            result(typeclass.typeRef.memberType(method)),
            parameter
          )
        )
    }

    private def isTypeclass(using
        quotes: Quotes
    )(
        symbol: quotes.reflect.Symbol
    ): Boolean = {
      import quotes.reflect.*
      symbol != Symbol.noSymbol &&
      scope.typeclassPackages(symbol.fullName) &&
      !symbol.flags.is(Flags.Private) &&
      !symbol.flags.is(Flags.Protected) &&
      symbol.flags.is(Flags.Trait) &&
      symbol.primaryConstructor.paramSymss.flatten.exists(_.isType) &&
      hasSummoner(symbol)
    }

    private def hasSummoner(using
        quotes: Quotes
    )(
        symbol: quotes.reflect.Symbol
    ): Boolean = {
      import quotes.reflect.*
      val companion = symbol.companionModule
      companion != Symbol.noSymbol &&
      companion.declaredMethods
        .filter(_.name == "apply")
        .exists(
          _.paramSymss.flatten
            .exists(parameter => containsType(parameterType(parameter), symbol))
        )
    }

    private def parameterType(using
        quotes: Quotes
    )(
        parameter: quotes.reflect.Symbol
    ): quotes.reflect.TypeRepr = {
      import quotes.reflect.*
      parameter.tree match {
        case value: ValDef => value.tpt.tpe
        case _             => parameter.termRef.widen
      }
    }

    private def containsType(using
        quotes: Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        target: quotes.reflect.Symbol
    ): Boolean = {
      import quotes.reflect.*
      val normalized = tpe.dealias
      normalized.typeSymbol == target ||
      (normalized match {
        case AppliedType(tycon, arguments) =>
          containsType(tycon, target) || arguments.exists(
            containsType(_, target)
          )
        case AnnotatedType(underlying, _) =>
          containsType(underlying, target)
        case ByNameType(underlying) =>
          containsType(underlying, target)
        case _ =>
          false
      })
    }

    private def evidenceTypeclasses(using
        quotes: Quotes
    )(
        parameters: List[quotes.reflect.Symbol]
    ): List[quotes.reflect.Symbol] =
      parameters
        .filter(_.isTerm)
        .flatMap(parameter => referencedTypes(parameterType(parameter)))
        .filter(isTypeclass)
        .distinct

    private def referencedTypes(using
        quotes: Quotes
    )(
        tpe: quotes.reflect.TypeRepr
    ): List[quotes.reflect.Symbol] = {
      import quotes.reflect.*
      val normalized = tpe.dealias
      val own =
        if (normalized.typeSymbol == Symbol.noSymbol) Nil
        else List(normalized.typeSymbol)
      own ++ (normalized match {
        case AppliedType(tycon, arguments) =>
          referencedTypes(tycon) ++ arguments.flatMap(referencedTypes)
        case AnnotatedType(underlying, _) =>
          referencedTypes(underlying)
        case ByNameType(underlying) =>
          referencedTypes(underlying)
        case _ =>
          Nil
      })
    }

    private def kindOf(using
        quotes: Quotes
    )(
        parameter: quotes.reflect.Symbol
    ): String = {
      import quotes.reflect.*
      parameter.tree match {
        case definition: TypeDef =>
          definition.rhs match {
            case lambda: LambdaTypeTree =>
              lambda.tparams.size match {
                case 1 => "Unary"
                case 2 => "Binary"
                case _ => "Star"
              }
            case _ => "Star"
          }
        case _ => "Star"
      }
    }

    private def isPublicMethod(using
        quotes: Quotes
    )(
        method: quotes.reflect.Symbol
    ): Boolean = {
      import quotes.reflect.*
      method.isDefDef &&
      method.name != "<init>" &&
      !method.name.startsWith("$") &&
      !method.flags.is(Flags.Private) &&
      !method.flags.is(Flags.Protected) &&
      !method.flags.is(Flags.Synthetic)
    }

    private def methodRoot(using
        quotes: Quotes
    )(
        method: quotes.reflect.Symbol
    ): quotes.reflect.Symbol =
      if (isConcreteEvidenceAccessor(method)) method
      else method.allOverriddenSymbols.toList.lastOption.getOrElse(method)

    private def isConcreteEvidenceAccessor(using
        quotes: Quotes
    )(
        method: quotes.reflect.Symbol
    ): Boolean = {
      import quotes.reflect.*
      method.paramSymss.flatten.count(_.isTerm) == 0 &&
      !method.flags.is(Flags.Deferred) &&
      (method.tree match {
        case DefDef(_, _, returnType, Some(_)) =>
          referencedTypes(returnType.tpe).exists(isTypeclass)
        case _ =>
          false
      })
    }

    private def bestTarget(using
        quotes: Quotes
    )(
        syntaxMethod: quotes.reflect.Symbol,
        candidates: List[quotes.reflect.Symbol]
    ): Option[quotes.reflect.Symbol] = {
      val syntaxArity = syntaxMethod.paramSymss.flatten.count(_.isTerm)
      candidates.sortBy { candidate =>
        val targetArity = candidate.paramSymss.flatten.count(_.isTerm)
        (math.abs(targetArity - syntaxArity - 1), semanticSymbol(candidate))
      }.headOption
    }

    private def syntaxImport(using
        quotes: Quotes
    )(
        method: quotes.reflect.Symbol
    ): String = {
      val sourceName =
        method.pos.map(_.sourceFile.name).getOrElse(typeclassSource(method))
      val stem =
        if (sourceName.endsWith(".scala")) sourceName.dropRight(6)
        else sourceName
      val module = stem match {
        case "SemigroupalBuilder" | "TupleSemigroupalSyntax" => "apply"
        case other =>
          val withoutSuffix =
            List("Syntax", "Ops").find(other.endsWith) match {
              case Some(suffix) => other.dropRight(suffix.length)
              case None         => other
            }
          withoutSuffix.headOption
            .map(first => s"${first.toLower}${withoutSuffix.tail}")
            .getOrElse("all")
      }
      s"cats.syntax.$module.*"
    }

    private def typeclassSource(using
        quotes: Quotes
    )(
        method: quotes.reflect.Symbol
    ): String =
      method.owner.name.headOption
        .map(first => s"${first.toLower}${method.owner.name.tail}.scala")
        .getOrElse("all.scala")

    private def semanticSymbol(using
        quotes: Quotes
    )(
        symbol: quotes.reflect.Symbol
    ): String = {
      import quotes.reflect.*
      if (symbol == Symbol.noSymbol) ""
      else if (symbol.flags.is(Flags.Package)) {
        if (symbol.name == "<root>" || symbol.name == "<empty>") ""
        else {
          val prefix =
            if (symbol.owner == Symbol.noSymbol) ""
            else semanticSymbol(symbol.owner)
          prefix + escapeName(symbol.name) + "/"
        }
      } else if (symbol.flags.is(Flags.Module) && symbol.isClassDef) {
        semanticSymbol(symbol.owner) + escapeName(
          symbol.name.stripSuffix("$")
        ) + "."
      } else if (symbol.isDefDef) {
        val overloads =
          symbol.owner.declaredMethods.filter(_.name == symbol.name)
        val index = overloads.indexOf(symbol)
        val disambiguator = if (index <= 0) "()" else s"(+$index)"
        semanticSymbol(symbol.owner) + escapeName(
          symbol.name
        ) + disambiguator + "."
      } else if (symbol.isType) {
        semanticSymbol(symbol.owner) + escapeName(symbol.name) + "#"
      } else {
        semanticSymbol(symbol.owner) + escapeName(symbol.name) + "."
      }
    }

    private def escapeName(name: String): String =
      if (name.exists(ch => ch == '/' || ch == '.' || ch == ';' || ch == '['))
        s"`$name`"
      else name
  }
}
