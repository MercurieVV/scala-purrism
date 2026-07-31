package fix.hkt.gen

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.lang.reflect.Modifier

import scala.collection.mutable.ListBuffer
import scala.quoted.Quotes
import scala.reflect.NameTransformer
import scala.tasty.inspector.Inspector
import scala.tasty.inspector.Tasty
import scala.tasty.inspector.TastyInspector
import scala.util.Try

object CatsIndexGen {
  private val TypeclassHeader =
    List(
      "symbol",
      "parents",
      "kind",
      "typeParams",
      "depth",
      "renderName",
      "importPath",
      "public"
    )
  private val CapabilityHeader =
    List("typeclass", "method", "owner", "kind", "derived", "arity")
  private val SyntaxHeader =
    List("syntaxMethod", "owner", "method", "importPath")
  private val StdlibHeader =
    List("concreteMethod", "owner", "method", "note")
  private val GapsHeader = List("typeclass", "reason", "tracked")

  private val StdlibSeed: List[List[String]] = List(
    List(
      "cats/Eval#defer().",
      "cats/Defer#defer().",
      "cats/Defer#defer().",
      "Eval recursion is Defer"
    ),
    List(
      "cats/Eval#flatMap().",
      "cats/FlatMap#flatMap().",
      "cats/FlatMap#flatMap().",
      "Eval sequencing is FlatMap"
    ),
    List(
      "cats/Eval#map().",
      "cats/Functor#map().",
      "cats/Functor#map().",
      "Eval transformation is Functor"
    ),
    List(
      "cats/data/NonEmptyList#flatMap().",
      "cats/FlatMap#flatMap().",
      "cats/FlatMap#flatMap().",
      "NonEmptyList sequencing is FlatMap"
    ),
    List(
      "cats/data/NonEmptyList#map().",
      "cats/Functor#map().",
      "cats/Functor#map().",
      "NonEmptyList transformation is Functor"
    ),
    List(
      "scala/Option#flatMap().",
      "cats/FlatMap#flatMap().",
      "cats/FlatMap#flatMap().",
      "Option sequencing is FlatMap"
    ),
    List(
      "scala/Option#map().",
      "cats/Functor#map().",
      "cats/Functor#map().",
      "Option transformation is Functor"
    ),
    List(
      "scala/collection/IndexedSeqOps#foldRight().",
      "cats/Foldable#foldRight().",
      "cats/Foldable#foldRight().",
      "Vector right fold is Foldable"
    ),
    List(
      "scala/collection/IterableOnceOps#foldLeft().",
      "cats/Foldable#foldLeft().",
      "cats/Foldable#foldLeft().",
      "Vector and Seq left folds are Foldable"
    ),
    List(
      "scala/collection/IterableOnceOps#foldRight().",
      "cats/Foldable#foldRight().",
      "cats/Foldable#foldRight().",
      "Seq right fold is Foldable"
    ),
    List(
      "scala/collection/IterableOnceOps#reduce().",
      "cats/Reducible#reduceLeft().",
      "cats/Reducible#reduceLeft().",
      "partial reduction may require Reducible"
    ),
    List(
      "scala/collection/IterableOnceOps#reduce().",
      "cats/kernel/Semigroup#combine().",
      "cats/kernel/Semigroup#combine().",
      "partial reduction may require Semigroup"
    ),
    List(
      "scala/collection/IterableOps#`++`().",
      "cats/SemigroupK#combineK().",
      "cats/SemigroupK#combineK().",
      "collection concatenation is SemigroupK"
    ),
    List(
      "scala/collection/IterableOps#collect().",
      "cats/FunctorFilter#mapFilter().",
      "cats/FunctorFilter#mapFilter().",
      "Seq collect is FunctorFilter"
    ),
    List(
      "scala/collection/IterableOps#flatMap().",
      "cats/FlatMap#flatMap().",
      "cats/FlatMap#flatMap().",
      "Seq sequencing is FlatMap"
    ),
    List(
      "scala/collection/IterableOps#map().",
      "cats/Functor#map().",
      "cats/Functor#map().",
      "Seq transformation is Functor"
    ),
    List(
      "scala/collection/LinearSeqOps#foldLeft().",
      "cats/Foldable#foldLeft().",
      "cats/Foldable#foldLeft().",
      "List left fold is Foldable"
    ),
    List(
      "scala/collection/SeqOps#concat().",
      "cats/SemigroupK#combineK().",
      "cats/SemigroupK#combineK().",
      "sequence concat is SemigroupK"
    ),
    List(
      "scala/collection/StrictOptimizedIterableOps#collect().",
      "cats/FunctorFilter#mapFilter().",
      "cats/FunctorFilter#mapFilter().",
      "Vector collect is FunctorFilter"
    ),
    List(
      "scala/collection/StrictOptimizedIterableOps#flatMap().",
      "cats/FlatMap#flatMap().",
      "cats/FlatMap#flatMap().",
      "Vector sequencing is FlatMap"
    ),
    List(
      "scala/collection/StrictOptimizedIterableOps#map().",
      "cats/Functor#map().",
      "cats/Functor#map().",
      "Vector transformation is Functor"
    ),
    List(
      "scala/collection/immutable/List#collect().",
      "cats/FunctorFilter#mapFilter().",
      "cats/FunctorFilter#mapFilter().",
      "List collect is FunctorFilter"
    ),
    List(
      "scala/collection/immutable/List#flatMap().",
      "cats/FlatMap#flatMap().",
      "cats/FlatMap#flatMap().",
      "List sequencing is FlatMap"
    ),
    List(
      "scala/collection/immutable/List#foldRight().",
      "cats/Foldable#foldRight().",
      "cats/Foldable#foldRight().",
      "List right fold is Foldable"
    ),
    List(
      "scala/collection/immutable/List#map().",
      "cats/Functor#map().",
      "cats/Functor#map().",
      "List transformation is Functor"
    ),
    List(
      "scala/util/Try#flatMap().",
      "cats/FlatMap#flatMap().",
      "cats/FlatMap#flatMap().",
      "Try sequencing is FlatMap"
    ),
    List(
      "scala/util/Try#map().",
      "cats/Functor#map().",
      "cats/Functor#map().",
      "Try transformation is Functor"
    ),
    List(
      "scala/util/Try#recoverWith().",
      "cats/ApplicativeError#handleErrorWith().",
      "cats/ApplicativeError#handleErrorWith().",
      "Try recovery is ApplicativeError"
    )
  )

  private final case class Options(
      output: Path,
      jars: List[Path],
      catsVersion: String,
      scalaVersion: String
  )

  private final case class RawTypeclass(
      fullName: String,
      symbol: String,
      parents: List[String],
      kind: String,
      typeParams: Int,
      renderName: String,
      importPath: String
  )

  private final case class RawCapability(
      typeclassName: String,
      typeclass: String,
      method: String,
      owner: String,
      kind: String,
      derived: Boolean,
      arity: Int
  )

  private final case class RawSyntax(
      syntaxMethod: String,
      owner: String,
      method: String,
      importPath: String
  )

  def main(args: Array[String]): Unit = {
    val options = parseOptions(args.toList)
    val typeclasses = ListBuffer.empty[RawTypeclass]
    val capabilities = ListBuffer.empty[RawCapability]
    val syntax = ListBuffer.empty[RawSyntax]

    options.jars.foreach { jar =>
      val inspected = TastyInspector.inspectTastyFilesInJar(jar.toString)(
        new CatsInspector(typeclasses, capabilities, syntax)
      )
      require(inspected, s"TASTy inspection failed for $jar")
    }

    val uniqueTypeclasses =
      typeclasses.toList.groupBy(_.symbol).values.map(_.head).toList
    val names = uniqueTypeclasses.iterator.map(tc => tc.fullName -> tc).toMap
    val symbols = uniqueTypeclasses.iterator.map(tc => tc.symbol -> tc).toMap
    val parentsBySymbol = uniqueTypeclasses.iterator.map { tc =>
      tc.symbol -> tc.parents.flatMap(names.get).map(_.symbol).distinct.sorted
    }.toMap

    def ancestors(symbol: String, seen: Set[String]): Set[String] =
      parentsBySymbol.getOrElse(symbol, Nil).foldLeft(Set.empty[String]) {
        case (acc, parent) if seen(parent) => acc
        case (acc, parent) =>
          acc + parent ++ ancestors(parent, seen + parent)
      }

    val typeclassRows = uniqueTypeclasses.map { tc =>
      List(
        tc.symbol,
        parentsBySymbol.getOrElse(tc.symbol, Nil).mkString(","),
        tc.kind,
        tc.typeParams.toString,
        ancestors(tc.symbol, Set(tc.symbol)).size.toString,
        tc.renderName,
        tc.importPath,
        "true"
      )
    }

    val capabilityRows = capabilities.toList
      .filter(row => names.contains(row.typeclassName))
      .filter(row => symbols.contains(ownerTypeSymbol(row.owner)))
      .map { row =>
        List(
          row.typeclass,
          row.method,
          row.owner,
          row.kind,
          row.derived.toString,
          row.arity.toString
        )
      }

    val capabilityMethods =
      capabilityRows.iterator.map(row => row(2) -> row(1)).toSet
    val missingStdlibCapabilities =
      StdlibSeed.filterNot(row => capabilityMethods.contains(row(1) -> row(2)))
    require(
      missingStdlibCapabilities.isEmpty,
      s"stdlib mappings absent from capabilities: $missingStdlibCapabilities"
    )
    val syntaxRows = (syntax.toList ++ legacyOpsSyntax(capabilities.toList))
      .filter(row => capabilityMethods.contains(row.owner -> row.method))
      .map(row => List(row.syntaxMethod, row.owner, row.method, row.importPath))

    val generatedHeader =
      s"# generated by CatsIndexGen; do not edit by hand; " +
        s"source: org.typelevel::cats-core:${options.catsVersion} for Scala " +
        s"${options.scalaVersion}; extraction: TASTy"
    val binaryKindGaps = List("Bifoldable", "Bifunctor", "Bitraverse").map {
      name =>
        List(
          s"cats/$name#",
          "binary kind F[_, _] not solved in v1; needs type-lambda rendering",
          "#33"
        )
    }
    val starKindGaps = List(
      "cats/Show#",
      "cats/kernel/Band#",
      "cats/kernel/BoundedEnumerable#",
      "cats/kernel/BoundedSemilattice#",
      "cats/kernel/CommutativeGroup#",
      "cats/kernel/CommutativeMonoid#",
      "cats/kernel/CommutativeSemigroup#",
      "cats/kernel/Eq#",
      "cats/kernel/Group#",
      "cats/kernel/Hash#",
      "cats/kernel/LowerBounded#",
      "cats/kernel/Monoid#",
      "cats/kernel/Order#",
      "cats/kernel/PartialOrder#",
      "cats/kernel/Semigroup#",
      "cats/kernel/Semilattice#",
      "cats/kernel/UpperBounded#"
    ).map { symbol =>
      List(
        symbol,
        "Star kind: typeclass over a plain type, not a type constructor; out of PreferHKT's F[_] scope",
        "none"
      )
    }
    val gapRows = binaryKindGaps ++ starKindGaps

    Files.createDirectories(options.output)
    writeTsv(
      options.output.resolve("typeclasses.tsv"),
      TypeclassHeader,
      generatedHeader,
      typeclassRows
    )
    writeTsv(
      options.output.resolve("capabilities.tsv"),
      CapabilityHeader,
      generatedHeader,
      capabilityRows
    )
    writeTsv(
      options.output.resolve("syntax.tsv"),
      SyntaxHeader,
      generatedHeader,
      syntaxRows
    )
    writeTsv(
      options.output.resolve("stdlib.tsv"),
      StdlibHeader,
      generatedHeader,
      StdlibSeed
    )
    writeTsv(
      options.output.resolve("gaps.tsv"),
      GapsHeader,
      generatedHeader,
      gapRows
    )

    println(
      s"wrote ${typeclassRows.distinct.size} typeclasses, " +
        s"${capabilityRows.distinct.size} capabilities, and " +
        s"${syntaxRows.distinct.size} syntax mappings; " +
        s"wrote ${StdlibSeed.distinct.size} stdlib mappings and " +
        s"${gapRows.size} tracked gaps to " +
        options.output
    )
  }

  private def parseOptions(args: List[String]): Options = {
    def loop(
        remaining: List[String],
        output: Option[Path],
        jars: List[Path],
        catsVersion: Option[String],
        scalaVersion: Option[String]
    ): Options =
      remaining match {
        case "--output" :: value :: tail =>
          loop(tail, Some(Paths.get(value)), jars, catsVersion, scalaVersion)
        case "--jar" :: value :: tail =>
          loop(
            tail,
            output,
            jars :+ Paths.get(value),
            catsVersion,
            scalaVersion
          )
        case "--cats-version" :: value :: tail =>
          loop(tail, output, jars, Some(value), scalaVersion)
        case "--scala-version" :: value :: tail =>
          loop(tail, output, jars, catsVersion, Some(value))
        case value :: tail if !value.startsWith("-") =>
          loop(
            tail,
            output,
            jars :+ Paths.get(value),
            catsVersion,
            scalaVersion
          )
        case flag :: _ =>
          throw new IllegalArgumentException(
            s"unknown or incomplete argument: $flag"
          )
        case Nil =>
          val target = output.getOrElse(
            Paths.get("scalafix", "resources", "cats-index")
          )
          val targets =
            if (jars.nonEmpty) jars
            else
              List(
                jarOf(classOf[cats.Functor[?]]),
                jarOf(classOf[cats.kernel.Monoid[?]])
              ).distinct
          Options(
            target,
            targets,
            catsVersion.getOrElse(
              versionFromJar(targets, "cats-core_3-")
            ),
            scalaVersion.getOrElse(
              versionFromJar(List(jarOf(classOf[Quotes])), "scala3-library_3-")
            )
          )
      }

    loop(args, None, Nil, None, None)
  }

  private def versionFromJar(jars: List[Path], prefix: String): String =
    jars.iterator
      .map(_.getFileName.toString)
      .find(name => name.startsWith(prefix) && name.endsWith(".jar"))
      .map(_.stripPrefix(prefix).stripSuffix(".jar"))
      .getOrElse(
        throw new IllegalArgumentException(
          s"cannot determine version from resolved jar with prefix $prefix"
        )
      )

  private def jarOf(clazz: Class[?]): Path = {
    val uri: URI = clazz.getProtectionDomain.getCodeSource.getLocation.toURI
    Paths.get(uri)
  }

  private def ownerTypeSymbol(method: String): String = {
    val methodStart = method.lastIndexOf('#')
    if (methodStart < 0) "" else method.substring(0, methodStart + 1)
  }

  private def legacyOpsSyntax(
      capabilities: List[RawCapability]
  ): List[RawSyntax] = {
    val loader = Thread.currentThread().getContextClassLoader
    val syntaxModules = Try(
      Class.forName("cats.syntax.package$", false, loader)
    ).toOption.toList
      .flatMap(
        _.getFields.iterator
          .filter(field => Modifier.isStatic(field.getModifiers))
          .map(_.getName)
      )
      .toSet

    capabilities.iterator
      .map(_.owner)
      .toSet
      .toList
      .flatMap { owner =>
        val typeSymbol = ownerTypeSymbol(owner)
        val typeName =
          typeSymbol.stripSuffix("#").replace('/', '.')
        val methodName = owner
          .stripPrefix(typeSymbol)
          .takeWhile(_ != '(')
        val opsMethods = Try(
          Class.forName(s"${typeName}$$Ops", false, loader)
        ).toOption.toList
          .flatMap(
            _.getMethods.iterator
              .map(method => NameTransformer.decode(method.getName))
          )
          .toSet
        val module = syntaxModule(typeName.split('.').lastOption.getOrElse(""))
        if (opsMethods(methodName) && syntaxModules(module))
          List(
            RawSyntax(
              typeSymbol.stripSuffix("#") + ".Ops#" + owner
                .stripPrefix(typeSymbol),
              owner,
              owner,
              s"cats.syntax.$module.*"
            )
          )
        else Nil
      }
  }

  private def syntaxModule(typeclassName: String): String =
    typeclassName match {
      case "MonoidK"               => "semigroupk"
      case "NonEmptyParallel"      => "parallel"
      case other if other.nonEmpty => other.head.toLower + other.tail
      case _                       => "all"
    }

  private def writeTsv(
      path: Path,
      header: List[String],
      generatedHeader: String,
      unsortedRows: List[List[String]]
  ): Unit = {
    val rows = unsortedRows.distinct.sortWith(compareRows(_, _) < 0)
    rows.foreach { row =>
      require(
        row.size == header.size,
        s"wrong column count for $path: $row"
      )
      row.foreach { cell =>
        require(
          !cell.exists(ch => ch == '\t' || ch == '\n' || ch == '\r'),
          s"invalid TSV cell for $path: $cell"
        )
        require(
          cell == cell.stripTrailing,
          s"trailing whitespace in $path: $cell"
        )
      }
    }
    val text =
      ("#" + header.mkString("\t")) + "\n" + generatedHeader + "\n" +
        rows
          .map(_.mkString("\t"))
          .mkString("", "\n", if (rows.isEmpty) "" else "\n")
    Files.writeString(path, text, StandardCharsets.UTF_8)
  }

  private def compareRows(left: List[String], right: List[String]): Int =
    left.iterator
      .zip(right.iterator)
      .map { case (a, b) => a.compareTo(b) }
      .find(_ != 0)
      .getOrElse(Integer.compare(left.size, right.size))

  private final class CatsInspector(
      typeclasses: ListBuffer[RawTypeclass],
      capabilities: ListBuffer[RawCapability],
      syntax: ListBuffer[RawSyntax]
  ) extends Inspector {
    override def inspect(using
        quotes: Quotes
    )(
        tastys: List[Tasty[quotes.type]]
    ): Unit = {
      import quotes.reflect.*

      val definitions = ListBuffer.empty[ClassDef]
      val traverser = new TreeTraverser {
        override def traverseTree(tree: Tree)(owner: Symbol): Unit = {
          tree match {
            case definition: ClassDef =>
              definitions += definition
            case _ =>
          }
          super.traverseTree(tree)(owner)
        }
      }
      tastys.foreach(tasty =>
        traverser.traverseTree(tasty.ast)(Symbol.noSymbol)
      )

      val candidates = definitions.iterator
        .map(_.symbol)
        .filter(isTypeclass)
        .toList
        .distinct
      val candidateNames = candidates.iterator.map(_.fullName).toSet

      candidates.foreach { tc =>
        val typeParameters =
          tc.primaryConstructor.paramSymss.flatten.filter(_.isType)
        val kind = typeParameters.headOption.map(kindOf).getOrElse("Star")
        val definition = definitions.find(_.symbol == tc).get
        val parents = definition.parents
          .flatMap {
            case term: Term     => Some(term.tpe.dealias.typeSymbol)
            case tree: TypeTree => Some(tree.tpe.dealias.typeSymbol)
            case _              => None
          }
          .filter(_ != Symbol.noSymbol)
          .map(_.fullName)
          .distinct
        val renderName = tc.name
        val importPath =
          if (tc.fullName.startsWith("cats.kernel.")) s"cats.$renderName"
          else tc.fullName
        typeclasses += RawTypeclass(
          tc.fullName,
          semanticSymbol(tc),
          parents,
          kind,
          typeParameters.size,
          renderName,
          importPath
        )

        tc.methodMembers
          .filter(isPublicMethod)
          .filter(method => isTypeclass(methodRoot(method).owner))
          .foreach { method =>
            val root = methodRoot(method)
            capabilities += RawCapability(
              tc.fullName,
              semanticSymbol(tc),
              semanticSymbol(method),
              semanticSymbol(root),
              kind,
              root.owner != tc || !method.flags.is(Flags.Deferred),
              method.paramSymss.flatten.count(_.isTerm)
            )
          }
      }

      definitions.iterator
        .filter(definition =>
          definition.symbol.fullName.startsWith("cats.syntax.")
        )
        .foreach { definition =>
          val constructorEvidence =
            evidenceTypeclasses(
              definition.symbol.primaryConstructor.paramSymss.flatten
            )
          definition.symbol.declaredMethods
            .filter(isPublicMethod)
            .foreach { method =>
              val evidence =
                (constructorEvidence ++ evidenceTypeclasses(
                  method.paramSymss.flatten
                )).distinct
              val targets = evidence.flatMap { tc =>
                tc.methodMembers.filter(candidate =>
                  candidate.name == method.name && isPublicMethod(candidate)
                )
              }
              bestTarget(method, targets).foreach { target =>
                val root = methodRoot(target)
                syntax += RawSyntax(
                  semanticSymbol(method),
                  semanticSymbol(root),
                  semanticSymbol(target),
                  syntaxImport(method)
                )
              }
            }
        }

    }

    private def isTypeclass(using
        quotes: Quotes
    )(
        symbol: quotes.reflect.Symbol
    ): Boolean = {
      import quotes.reflect.*
      val inInventoryPackage =
        symbol.fullName.startsWith("cats.kernel.") ||
          (symbol.fullName.startsWith("cats.") &&
            !symbol.fullName.stripPrefix("cats.").contains("."))
      symbol != Symbol.noSymbol &&
      inInventoryPackage &&
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
            .map(first => first.toLower + withoutSuffix.tail)
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
        .map(first => first.toLower + method.owner.name.tail + ".scala")
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
