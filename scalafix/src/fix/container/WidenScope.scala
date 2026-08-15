package fix

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import scala.meta._
import scala.meta.internal.{semanticdb => s}

import metaconfig.ConfDecoder

import fix.opaque.SemanticdbIndex

/** Whether a widening rule reads the whole project before it rewrites, and
  * where the sources are.
  *
  * Separate from each rule's own configuration case class, and read from the
  * same configuration key, so that adding it does not change the published
  * shape of `PreferPolymorphicCollectionsConfig` and friends.
  */
final case class CrossFileConfig(
    crossFile: Boolean = false,
    crossFileRoot: Option[String] = None,
    /** Extra directories to search for SemanticDB payloads.
      *
      * A build tool hands scalafix one module's payload at a time -- under Mill
      * a dependency contributes its `classes` directory, and its SemanticDB
      * lives somewhere else entirely -- so a call site in a *sibling* module is
      * invisible, and the call-site repair never happens. Naming the build's
      * output directory here (`["out"]` for Mill, `["target"]` for sbt) lets
      * the scan find every module's payload. Anything stale in there is read as
      * if it were current, so a full compile before the run matters.
      */
    crossFileTargetroots: List[String] = Nil
)

object CrossFileConfig {
  val default: CrossFileConfig = CrossFileConfig()

  implicit val decoder: ConfDecoder[CrossFileConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("crossFile")(default.crossFile)
        .product(conf.getOrElse("crossFileRoot")(""))
        .product(
          conf.getOrElse("crossFileTargetroots")(default.crossFileTargetroots)
        )
        .map { case ((crossFile, root), targetroots) =>
          CrossFileConfig(
            crossFile,
            Option(root).filter(_.nonEmpty),
            targetroots
          )
        }
    }
}

/** What a project-wide view says about adding a type parameter to a def.
  *
  * Widening a signature is source-compatible at an ordinary call site --
  * `f(myList)` still infers the new parameter from the argument it was already
  * passing. It is *not* compatible with a call that applies types explicitly:
  * `fromRatios[V](ratios, idBase)` names one type argument, and a def that has
  * grown a second one no longer accepts that call. The definition and the call
  * usually live in different files, and often in different modules, so no
  * per-file decision can see the problem -- which is exactly how a rewrite that
  * compiles `signals` breaks `score`.
  *
  * This is the shared answer, computed once from the whole source tree the way
  * [[KleisliLiftScope]] is, so the file holding the definition and the file
  * holding the call reach the same conclusion:
  *
  *   - `repairable` -- the def may be widened, and every explicit type
  *     application of it must have its type-argument list dropped, letting
  *     inference supply both the old arguments and the new one;
  *   - `vetoed` -- the def has a type parameter that inference cannot recover
  *     (it appears in no value parameter), so dropping the list would not
  *     compile either. Nothing is widened and nothing is patched.
  *
  * Everything is keyed on the symbols SemanticDB resolved, never on spellings,
  * so an unrelated method of the same name neither vetoes a widening nor has
  * its own call sites rewritten.
  */
final class WidenScope(
    val repairable: Set[String],
    val appendable: Map[String, Int],
    val vetoed: Set[String]
) {
  def isEmpty: Boolean =
    repairable.isEmpty && appendable.isEmpty && vetoed.isEmpty

  /** Whether this def must not grow a type parameter. */
  def vetoes(symbol: String): Boolean = vetoed.contains(symbol)

  /** Whether a call site of this def has to drop its explicit type arguments.
    *
    * A prediction, made before either half is rewritten and identical in every
    * file of the run, which is what keeps the two halves consistent. It is
    * deliberately *not* re-derived from the definition afterwards: "the def
    * declares more type parameters than this call passes" reads as a repair to
    * make, but SemanticDB gives an extension method's own type parameters and
    * its extension's in one list, so that test rewrites `xs.at[Store[V]](id)`
    * for no reason.
    */
  def repairs(symbol: String): Boolean = repairable.contains(symbol)

  /** The value-parameter position whose container this def's call sites must
    * name, when dropping their type arguments is not open to them.
    *
    * The repair of first resort is to drop the list and let inference redo the
    * work, but that needs every type parameter to be inferable from the
    * arguments -- and one that arrives through evidence (`[A: Show]`) is not.
    * Rather than refuse the widening there, the call site is told the answer:
    * `describe[Int](rows)` becomes `describe[Int, List](rows)`, reading `List`
    * off the argument it already passes.
    */
  def appends(symbol: String): Option[Int] = appendable.get(symbol)
}

object WidenScope {
  val empty: WidenScope = new WidenScope(Set.empty, Map.empty, Set.empty)

  /** Reads every source SemanticDB recorded, and pairs each widening candidate
    * with the call sites that apply types to it explicitly.
    *
    * `containers` is the rule's own list, so the scope answers for the same
    * constructors the rule widens.
    */
  def build(
      root: Path,
      index: SemanticdbIndex,
      containers: List[String],
      widenPublic: Boolean
  ): WidenScope = build(root, index, containers, widenPublic, _ => true)

  def build(
      root: Path,
      index: SemanticdbIndex,
      containers: List[String],
      widenPublic: Boolean,
      knows: String => Boolean
  ): WidenScope = {
    // Signatures come from the payload, not from the sources: a def's type
    // parameters and the types of its value parameters are what SemanticDB
    // records, and reading them there survives a source file whose line
    // numbers have moved since it was compiled. Only the syntactic question --
    // "was this call written with type arguments?" -- needs a parse.
    val claims = claimsBy(containers, knows)
    // A head this cannot resolve is treated as concrete: an unknown symbol is
    // not evidence that its arguments are the widening target.
    val abstractHead: String => Boolean = symbol =>
      index.symbolInfo
        .get(symbol)
        .exists(_.kind == s.SymbolInformation.Kind.TYPE_PARAMETER)

    val methods: Map[String, MethodShape] =
      index.symbolInfo.iterator.flatMap { case (symbol, info) =>
        info.signature match {
          case method: s.MethodSignature
              if method.typeParameters.nonEmpty &&
                !symbol.startsWith("local") =>
            Some(
              symbol -> shapeOf(
                method,
                index,
                claims,
                abstractHead,
                widenPublic,
                info
              )
            )
          case _ => None
        }
      }.toMap

    val analysed = analyse(root, index)
    val applied = typeAppliedReferences(analysed)

    val (repairable, rest) = methods
      .filter { case (symbol, shape) =>
        shape.candidate && applied.contains(symbol)
      }
      .partition { case (_, shape) => shape.inferable }

    // The rest can still be widened if every call site can be *told* the new
    // type argument, which means reading the container off the argument that
    // call already passes. Where even one call site's argument does not resolve
    // to a container, nothing is widened: the two halves have to agree, and a
    // call site left behind is a build that does not compile.
    val appendable = rest.flatMap { case (symbol, shape) =>
      shape.containerParameter
        .filter(_ => callSitesResolve(symbol, shape, analysed, index, claims))
        .map(symbol -> _)
    }

    new WidenScope(
      repairable.keySet,
      appendable,
      rest.keySet.diff(appendable.keySet)
    )
  }

  /** What the scope needs to know about one method. */
  private final case class MethodShape(
      candidate: Boolean,
      inferable: Boolean,
      containerParameter: Option[Int]
  )

  /** A method's shape, read from its SemanticDB signature.
    *
    * `candidate` is deliberately an over-approximation: whether a definition is
    * really widened depends on what its body does, which is the business of the
    * rule that owns that file. Over-approximating costs a call site the
    * explicit type arguments it did not need to lose, which still compiles --
    * `inferable` is what put it in the set.
    */
  /** Whether a constructor symbol is one the configured rule widens.
    *
    * A spelled-out list answers by name. The wildcard answers by asking the
    * rule's own index, so that the scope claims exactly what the rule claims --
    * see [[ContainerNames]] for why an unfiltered wildcard is not that.
    */
  private def claimsBy(
      containers: List[String],
      knows: String => Boolean
  ): String => Boolean =
    symbol =>
      ContainerNames.matches(containers, displayName(symbol)) &&
        (!ContainerNames.isWildcard(containers) || knows(symbol))

  private def shapeOf(
      method: s.MethodSignature,
      index: SemanticdbIndex,
      claims: String => Boolean,
      abstractHead: String => Boolean,
      widenPublic: Boolean,
      info: s.SymbolInformation
  ): MethodShape = {
    val typeParameters = method.typeParameters.toList.flatMap(_.symlinks)
    // Evidence does not count. `def recording[F[_]: Sync](dir: Path)` desugars
    // to a `(using Sync[F])` parameter whose type mentions `F`, but no argument
    // at the call site does, so dropping `recording[IO](dir)`'s type argument
    // leaves `F` to be guessed from the expected type -- which is not inference
    // from the argument list, and not something this can promise.
    val parameterTypes = method.parameterLists.toList
      .flatMap(_.symlinks)
      .filterNot(isEvidence(index, _))
      .flatMap(parameter =>
        index.symbolInfo.get(parameter).map(_.signature).collect {
          case s.ValueSignature(tpe) => tpe
        }
      )

    MethodShape(
      candidate = (widenPublic || isRestricted(info)) &&
        parameterTypes.exists(applies(_, claims, abstractHead)),
      inferable = typeParameters.forall(parameter =>
        parameterTypes.exists(mentions(_, parameter))
      ),
      containerParameter =
        Option(parameterTypes.indexWhere(applies(_, claims, abstractHead)))
          .filter(_ >= 0)
    )
  }

  private def isEvidence(index: SemanticdbIndex, parameter: String): Boolean =
    index.symbolInfo.get(parameter).exists { info =>
      val implicitBit = s.SymbolInformation.Property.IMPLICIT.value
      val givenBit = s.SymbolInformation.Property.GIVEN.value
      (info.properties & (implicitBit | givenBit)) != 0
    }

  /** Whether a parameter's type applies one of the containers to one argument.
    */
  private def applies(
      tpe: s.Type,
      claims: String => Boolean,
      abstractHead: String => Boolean
  ): Boolean =
    tpe match {
      case s.TypeRef(_, symbol, arguments) =>
        if (arguments.isEmpty) false
        else if (arguments.size == 1 && claims(symbol)) true
        // Descend only through a head that is *not* itself a concrete
        // constructor, which is what `UsageAnalyzer.outerConcreteTargets` does:
        // in `Ref[F, Option[Mixed]]` the target is `Ref`, declined for being
        // binary, and the `Option` inside it is never widened by any rule. A
        // scope that descended anyway made every such def a candidate and
        // stripped the type arguments off its call sites for nothing.
        else if (abstractHead(symbol))
          arguments.exists(applies(_, claims, abstractHead))
        else false
      case _ => false
    }

  /** Whether a type mentions a type parameter, which is what makes that
    * parameter inferable from the argument at a call site.
    */
  private def mentions(tpe: s.Type, parameter: String): Boolean =
    tpe match {
      case s.TypeRef(prefix, symbol, arguments) =>
        symbol == parameter || mentions(prefix, parameter) ||
        arguments.exists(mentions(_, parameter))
      case s.SingleType(prefix, symbol) =>
        symbol == parameter || mentions(prefix, parameter)
      case s.ByNameType(underlying)       => mentions(underlying, parameter)
      case s.RepeatedType(underlying)     => mentions(underlying, parameter)
      case s.AnnotatedType(_, underlying) => mentions(underlying, parameter)
      case s.WithType(types)         => types.exists(mentions(_, parameter))
      case s.UnionType(types)        => types.exists(mentions(_, parameter))
      case s.IntersectionType(types) => types.exists(mentions(_, parameter))
      case s.StructuralType(underlying, _) => mentions(underlying, parameter)
      case _                               => false
    }

  private def displayName(symbol: String): String =
    symbol.stripSuffix("#").split('/').last.split('.').last

  private def isRestricted(info: s.SymbolInformation): Boolean =
    info.access match {
      case _: s.PrivateAccess         => true
      case _: s.PrivateThisAccess     => true
      case _: s.PrivateWithinAccess   => true
      case _: s.ProtectedAccess       => true
      case _: s.ProtectedThisAccess   => true
      case _: s.ProtectedWithinAccess => true
      case _                          => false
    }

  /** Whether this def is the shape a widening rule takes an interest in.
    *
    * Deliberately syntactic. The real decision needs the body's capabilities
    * and belongs to the rule that owns the definition's file;
    * over-approximating here costs a call site the explicit type arguments it
    * did not need to lose, which still compiles because `inferable` is what put
    * it in this set.
    */
  private def isCandidate(
      defn: Defn.Def,
      containers: List[String],
      widenPublic: Boolean
  ): Boolean =
    (widenPublic || isRestricted(defn)) &&
      valueParameterTypes(defn).exists(named(_, containers)) &&
      !hasStructuralBlocker(defn)

  private def isRestricted(defn: Defn.Def): Boolean =
    defn.mods.exists {
      case _: Mod.Private                  => true
      case Mod.Protected(Name.Anonymous()) => false
      case _: Mod.Protected                => true
      case _                               => false
    }

  private def hasStructuralBlocker(defn: Defn.Def): Boolean =
    defn.body.collect {
      case _: Defn.Var    => ()
      case _: Term.Throw  => ()
      case _: Term.Return => ()
    }.nonEmpty

  /** Whether the container appears applied anywhere in a parameter's type. */
  private def named(tpe: Type, containers: List[String]): Boolean =
    (tpe :: tpe.collect { case inner: Type => inner }).exists {
      case applied: Type.Apply =>
        ContainerNames.matches(containers, applied.tpe.syntax.split('.').last)
      case _ => false
    }

  /** Whether every declared type parameter can be recovered by inference from
    * the value parameters, which is what makes dropping an explicit type
    * argument list at a call site safe.
    */
  private def inferable(defn: Defn.Def): Boolean = {
    val mentioned = valueParameterTypes(defn)
      .flatMap(tpe => tpe :: tpe.collect { case inner: Type => inner })
      .map(_.syntax)
      .toSet
    typeParameters(defn).forall(param =>
      mentioned.exists(syntax => mentionsName(syntax, param.name.value))
    )
  }

  private def mentionsName(syntax: String, name: String): Boolean =
    syntax == name ||
      syntax
        .split("[^A-Za-z0-9_$]+")
        .contains(name)

  private def typeParameters(defn: Defn.Def): List[Type.Param] =
    defn.paramClauseGroups.flatMap(_.tparamClause.values)

  private def valueParameterTypes(defn: Defn.Def): List[Type] =
    defn.paramClauseGroups
      .flatMap(_.paramClauses)
      .flatMap(_.values)
      .flatMap(_.decltpe)

  /** Whether every explicit type application of this def passes an argument
    * whose container the call site can name.
    *
    * Read from the payload, not from the argument's spelling: `values` is a
    * `List` because its symbol says so, and `List(1, 2)` is one because the
    * companion it applies says so.
    */
  private def callSitesResolve(
      symbol: String,
      shape: MethodShape,
      analysed: List[(s.TextDocument, Tree)],
      index: SemanticdbIndex,
      claims: String => Boolean
  ): Boolean =
    shape.containerParameter.exists { position =>
      val sites = analysed.flatMap { case (document, tree) =>
        document.occurrences.collect {
          case occurrence
              if occurrence.role.isReference && occurrence.range.isDefined &&
                SemanticdbIndex.qualify(document.uri, occurrence.symbol) ==
                symbol =>
            argumentAt(
              tree,
              occurrence.range.get.startLine,
              occurrence.range.get.startCharacter,
              position
            ).map(
              _.exists(argument =>
                namesContainer(document, argument, index, claims)
              )
            )
        }.flatten
      }
      sites.nonEmpty && sites.forall(identity)
    }

  /** Whether the container of this argument expression can be read from the
    * payload -- which is what the call site would have to name.
    *
    * `values` resolves through the type of the symbol it refers to, and
    * `List(1, 2)` through the companion it applies. An expression that is
    * neither -- `if (flag) List(1, 2) else Nil` -- resolves to nothing, and a
    * call site that cannot be told the new type argument is one the widening
    * must not leave behind.
    */
  private def namesContainer(
      document: s.TextDocument,
      argument: Term,
      index: SemanticdbIndex,
      claims: String => Boolean
  ): Boolean = {
    def head(term: Term): Option[Term.Name] =
      term match {
        case name: Term.Name                    => Some(name)
        case select: Term.Select                => Some(select.name)
        case Term.Apply.After_4_6_0(fun, _)     => head(fun)
        case Term.ApplyType.After_4_6_0(fun, _) => head(fun)
        case _                                  => None
      }

    head(argument).exists { name =>
      document.occurrences
        .filter(occurrence =>
          occurrence.range.exists(range =>
            range.startLine == name.pos.startLine &&
              range.startCharacter == name.pos.startColumn
          )
        )
        .exists { occurrence =>
          val symbol = SemanticdbIndex.qualify(document.uri, occurrence.symbol)
          claims(symbol) ||
          index
            .valueType(symbol)
            .exists {
              case s.TypeRef(_, constructor, _) =>
                claims(constructor)
              case _ => false
            }
        }
    }
  }

  /** The argument in `position` of the call whose callee is named here, when
    * that call applies types explicitly. `None` for a reference that is not
    * such a call; `Some(None)` for one whose argument this cannot read.
    */
  private def argumentAt(
      tree: Tree,
      line: Int,
      column: Int,
      position: Int
  ): Option[Option[Term]] =
    tree.collect {
      case name: Term.Name
          if name.pos.startLine == line && name.pos.startColumn == column &&
            appliedTypesTo(name) =>
        applicationOf(name)
          .flatMap(_.argClause.values.lift(position))
    }.headOption

  /** The `f[A](args)` application a callee name belongs to. */
  private def applicationOf(name: Term.Name): Option[Term.Apply] = {
    val callee = name.parent.collect { case select: Term.Select => select }
    val applyType = (callee.getOrElse(name)).parent.collect {
      case applyType: Term.ApplyType => applyType
    }
    applyType.flatMap(_.parent).collect { case apply: Term.Apply => apply }
  }

  /** Symbols the project applies types to explicitly, somewhere. */
  private def typeAppliedReferences(
      analysed: List[(s.TextDocument, Tree)]
  ): Set[String] =
    analysed.flatMap { case (document, tree) =>
      document.occurrences.collect {
        case occurrence
            if occurrence.role.isReference && occurrence.range.isDefined &&
              isTypeAppliedAt(
                tree,
                occurrence.range.get.startLine,
                occurrence.range.get.startCharacter
              ) =>
          SemanticdbIndex.qualify(document.uri, occurrence.symbol)
      }
    }.toSet

  /** Whether the name at this position is the callee of a `f[A](...)`. */
  def isTypeAppliedAt(tree: Tree, line: Int, column: Int): Boolean =
    tree.collect {
      case name: Term.Name
          if name.pos.startLine == line && name.pos.startColumn == column &&
            appliedTypesTo(name) =>
        ()
    }.nonEmpty

  private def appliedTypesTo(name: Term.Name): Boolean =
    name.parent.exists {
      case applyType: Term.ApplyType => applyType.fun eq name
      case select: Term.Select if select.name eq name =>
        select.parent.exists {
          case applyType: Term.ApplyType => applyType.fun eq select
          case _                         => false
        }
      case _ => false
    }

  /** The scope for one rule invocation, or the empty one when the rule was not
    * asked to read the project.
    */
  def forRun(
      crossFile: CrossFileConfig,
      classpath: List[Path],
      containers: List[String],
      widenPublic: Boolean
  ): WidenScope =
    forRun(crossFile, classpath, containers, widenPublic, _ => true)

  /** As above, with the test a wildcard `containers` needs.
    *
    * `knows` is the rule's own `CatsIndex.knowsConstructor`, threaded in rather
    * than loaded here so the scope and the rule agree on what a candidate is.
    * Without it a wildcard scope predicts a widening for every unary parameter
    * in the project -- `PrometheusRegistry[F]`, `Stream[F, *]` -- and then
    * rewrites the call sites of definitions no rule touched.
    */
  def forRun(
      crossFile: CrossFileConfig,
      classpath: List[Path],
      containers: List[String],
      widenPublic: Boolean,
      knows: String => Boolean
  ): WidenScope =
    if (!crossFile.crossFile) empty
    else {
      // The classpath alone is one module's payload. The sourceroot is settled
      // from it first, because the extra target roots are named relative to the
      // project -- not to whatever directory the build tool happened to start
      // this process in.
      val local = SemanticdbIndex.load(classpath)
      val root = sourceroot(
        crossFile.crossFileRoot,
        PropagateOpaqueType.inferSourceroot(local, classpath),
        local
      )
      val semanticdb = SemanticdbIndex.load(
        classpath ++ semanticdbRoots(root, crossFile.crossFileTargetroots)
      )
      build(root, semanticdb, containers, widenPublic, knows)
    }

  /** The directory the payload's relative source paths are resolved against.
    *
    * Taken from configuration when given, otherwise the inferred one, otherwise
    * the working directory -- and validated, because a root that resolves none
    * of the documents silently turns the whole scan into an empty answer, which
    * reads exactly like "nothing to repair".
    */
  def sourceroot(
      configured: Option[String],
      inferred: => Path,
      index: SemanticdbIndex
  ): Path = {
    val candidates =
      configured.map(Paths.get(_).toAbsolutePath).toList ++
        List(inferred, Paths.get("").toAbsolutePath)
    candidates
      .find(root =>
        index.documents.exists(d => Files.isRegularFile(root.resolve(d.uri)))
      )
      .getOrElse(candidates.head)
  }

  /** The SemanticDB roots under the configured directories.
    *
    * A root is a directory that holds `META-INF/semanticdb`, which is what
    * `SemanticdbIndex.load` expects. `classes` directories are skipped: they
    * hold one file per class and never a payload, and walking them is most of
    * the cost of scanning a build output tree.
    */
  def semanticdbRoots(base: Path, configured: List[String]): List[Path] =
    configured
      .map(entry => base.resolve(entry).toAbsolutePath.normalize)
      .flatMap(path => search(path, depth = 8))
      .distinct

  private def search(directory: Path, depth: Int): List[Path] =
    if (depth < 0 || !Files.isDirectory(directory)) Nil
    else if (Files.isDirectory(directory.resolve("META-INF/semanticdb")))
      List(directory)
    else {
      val stream = Files.list(directory)
      try
        stream
          .iterator()
          .asScala
          .filter(Files.isDirectory(_))
          .filterNot(child => skipped(child.getFileName.toString))
          // A `classes` directory is one file per class and never holds a
          // payload nested any deeper -- except that a build tool may put the
          // payload *in* one, so it is still tested, just not descended into.
          .flatMap(child =>
            search(
              child,
              if (child.getFileName.toString == "classes") 0 else depth - 1
            )
          )
          .toList
      catch { case _: java.io.IOException => Nil }
      finally stream.close()
    }

  private def skipped(name: String): Boolean =
    name == "META-INF" || name.startsWith(".")

  private def analyse(
      root: Path,
      index: SemanticdbIndex
  ): List[(s.TextDocument, Tree)] =
    index.documents.toList.flatMap { document =>
      val path = root.resolve(document.uri)
      if (!Files.isRegularFile(path)) None
      else parseSource(path).map(tree => (document, tree))
    }

  private def parseSource(path: Path): Option[Tree] = {
    val input = Input.VirtualFile(
      path.toString,
      new String(Files.readAllBytes(path), "UTF-8")
    )
    dialects.Scala3(input).parse[Source].toOption
  }
}
