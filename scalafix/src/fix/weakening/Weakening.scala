package fix.weakening

import java.nio.file.Path

import scala.meta.*

import fix.FindingDiagnostic
import fix.findings.WeakeningFindings
import fix.hkt.CatsIndex
import fix.hkt.tasty.RuntimeIndex
import scalafix.v1.*

/** `TypeclassWeakening`'s rewrite: every context bound the index resolves is
  * solved against what its owner needs (see [[Requirements]] and
  * [[BoundSolver]]) and replaced by the single weakest covering typeclass.
  *
  * Only the bound's own type is replaced, so `Sync as S` keeps its `as S`. The
  * target's import is added; the original's import is removed only when no
  * other reference to it is left in the file. An ambiguous choice keeps the
  * bound and reports `TypeclassWeakening.ambiguous-weakening`.
  */
object Weakening {

  def patch(
      classpath: List[Path],
      config: TypeclassWeakeningConfig
  )(using doc: SemanticDocument): Patch = {
    val seeds = boundSymbols(doc.tree)
    if (seeds.isEmpty) Patch.empty
    else if (classpath.isEmpty) {
      debug(config, "no scalac classpath; every bound is kept")
      Patch.empty
    } else
      loadIndex(classpath, seeds, config) match {
        case Left(error) =>
          debug(config, s"index unavailable ($error); every bound is kept")
          Patch.empty
        case Right(index) => patchWith(index)
      }
  }

  /** Every context-bound type symbol as written, e.g.
    * `cats/effect/package.Sync#` for `import cats.effect.Sync`. An alias the
    * scan resolves to a target in a jar the seeds did not reach triggers a
    * second load including the targets, so their hierarchy is indexed too.
    */
  private def loadIndex(
      classpath: List[Path],
      seeds: Set[Symbol],
      config: TypeclassWeakeningConfig
  ): Either[String, CatsIndex] =
    RuntimeIndex.load(classpath, seeds, config.cacheConfig).flatMap { index =>
      val missingTargets = seeds
        .flatMap(index.aliasTarget(_).map(_.target))
        .filterNot(index.typeclasses.contains)
      if (missingTargets.isEmpty) Right(index)
      else
        RuntimeIndex.load(
          classpath,
          seeds ++ missingTargets,
          config.cacheConfig
        )
    }

  private def boundSymbols(tree: Tree)(using SemanticDocument): Set[Symbol] =
    tree
      .collect {
        case defn: Defn.Def =>
          defn.paramClauseGroups.flatMap(_.tparamClause.values)
        case cls: Defn.Class => cls.tparamClause.values
        case tr: Defn.Trait  => tr.tparamClause.values
      }
      .flatten
      .flatMap(_.bounds.context)
      .map(unaliased(_).symbol)
      .filterNot(_.isNone)
      .toSet

  private def unaliased(bound: Type): Type =
    bound match {
      case Type.BoundsAlias(_, inner) => inner
      case other                      => other
    }

  private def patchWith(
      index: CatsIndex
  )(using doc: SemanticDocument): Patch = {
    val sites = Requirements.sites(doc.tree, index).filterNot(overrides)
    val helperSites = localHelperSites(sites)
    val memo = helperSites.keys.toList
      .sortBy(_.value)
      .foldLeft(Map.empty[Symbol, Set[Symbol]]) { (memo, method) =>
        memo.updated(
          method,
          helperBounds(method, helperSites, index, Set.empty, memo)
        )
      }
    val decisions = sites.map { site =>
      site -> decide(
        site,
        helperSites,
        index,
        ownerSymbol(site.owner).toSet,
        memo
      )
    }
    val replaced = decisions.collect { case (site, Decision.Replace(target)) =>
      site -> target
    }
    val lints = decisions.collect { case (site, Decision.Ambiguous(options)) =>
      Patch.lint(
        FindingDiagnostic(
          WeakeningFindings.AmbiguousWeakening,
          site.bound.pos,
          s"`${site.bound.syntax}` could weaken to any of ${options.map(_.renderName).mkString(", ")}"
        )
      )
    }
    val imported = importedPaths(doc.tree)
    replaced.map { case (site, target) =>
      Patch.replaceTree(site.bound, target.renderName) +
        (if (imported.contains(target.importPath)) Patch.empty
         else addImport(target))
    }.asPatch + lints.asPatch + removedImports(replaced)
  }

  /** `a.b.C` for every `import a.b.C` / `import a.b.{C, D}` in the file. */
  private def importedPaths(tree: Tree): Set[String] =
    tree
      .collect { case importer: Importer =>
        importer.importees.collect { case Importee.Name(name) =>
          s"${importer.ref.syntax}.${name.value}"
        }
      }
      .flatten
      .toSet

  /** The decision for one site, with `stack` holding the methods whose decision
    * is in progress (a call back into one of them contributes its original
    * bounds).
    */
  private def decide(
      site: BoundSite,
      helperSites: Map[Symbol, List[BoundSite]],
      index: CatsIndex,
      stack: Set[Symbol],
      memo: Map[Symbol, Set[Symbol]]
  )(using SemanticDocument): Decision = {
    val helpers: Symbol => Option[Set[Symbol]] = method =>
      Option.when(helperSites.contains(method))(
        helperBounds(method, helperSites, index, stack, memo)
      )
    Requirements
      .collect(site, index, helpers)
      .fold(Decision.Keep)(required =>
        BoundSolver.solve(
          site.boundSymbol,
          required,
          index,
          site.boundFixedArgs
        )
      )
  }

  /** The post-decision bounds of every site of local method `method`. */
  private def helperBounds(
      method: Symbol,
      helperSites: Map[Symbol, List[BoundSite]],
      index: CatsIndex,
      stack: Set[Symbol],
      memo: Map[Symbol, Set[Symbol]]
  )(using SemanticDocument): Set[Symbol] = {
    val sites = helperSites.getOrElse(method, Nil)
    memo.getOrElse(
      method,
      if (stack.contains(method)) sites.map(_.boundSymbol).toSet
      else
        sites
          .map(site =>
            decide(site, helperSites, index, stack + method, memo) match {
              case Decision.Replace(target) => target.symbol
              case _                        => site.boundSymbol
            }
          )
          .toSet
    )
  }

  /** Defs of this document whose every context bound is a site and that take no
    * explicit `using`/`implicit` parameters: their evidence needs are exactly
    * their sites' bounds, so a weakened set can stand in for their signature.
    */
  private def localHelperSites(sites: List[BoundSite])(using
      SemanticDocument
  ): Map[Symbol, List[BoundSite]] =
    sites
      .collect { case site @ BoundSite(defn: Defn.Def, _, _, _, _) =>
        defn -> site
      }
      .groupMap(_._1)(_._2)
      .collect {
        case (defn, defnSites)
            if !hasExplicitEvidence(defn) &&
              defn.paramClauseGroups
                .flatMap(_.tparamClause.values)
                .flatMap(_.bounds.context)
                .size == defnSites.size =>
          defn.name.symbol -> defnSites
      }
      .filterNot(_._1.isNone)

  /** A def overriding or implementing another member must keep its signature: a
    * weaker bound would no longer override it.
    */
  private def overrides(site: BoundSite)(using SemanticDocument): Boolean =
    site.owner match {
      case defn: Defn.Def =>
        defn.mods.exists {
          case _: Mod.Override => true
          case _               => false
        } || defn.name.symbol.info.exists(_.overriddenSymbols.nonEmpty)
      case _ => false
    }

  private def hasExplicitEvidence(defn: Defn.Def): Boolean =
    defn.paramClauseGroups.exists(
      _.paramClauses.exists(clause =>
        clause.mod.exists {
          case _: Mod.Using | _: Mod.Implicit => true
          case _                              => false
        }
      )
    )

  private def ownerSymbol(owner: Tree)(using SemanticDocument): Option[Symbol] =
    owner match {
      case defn: Defn.Def  => Some(defn.name.symbol)
      case cls: Defn.Class => Some(cls.name.symbol)
      case tr: Defn.Trait  => Some(tr.name.symbol)
      case _               => None
    }

  private def addImport(target: Rendered): Patch =
    importer(target.importPath).fold(Patch.empty)(Patch.addGlobalImport)

  private def importer(path: String): Option[Importer] =
    s"import $path".parse[Stat].toOption.collect { case Import(List(single)) =>
      single
    }

  /** Removes the import of each replaced bound's original typeclass when
    * nothing else in the file refers to it and no replacement re-adds it.
    */
  private def removedImports(
      replaced: List[(BoundSite, Rendered)]
  )(using doc: SemanticDocument): Patch = {
    val spans = replaced.map(_._1.bound.pos)
    val added = replaced.map(_._2.renderName).toSet
    val references = doc.tree.collect {
      case name: Name
          if !spans.exists(span =>
            span.start <= name.pos.start && name.pos.end <= span.end
          ) && !insideImport(name) =>
        stem(name.symbol)
    }.toSet
    replaced
      .map(_._1.bound.symbol)
      .distinct
      .filterNot(original =>
        references.contains(stem(original)) ||
          added.contains(original.displayName)
      )
      .map(original =>
        Patch.removeGlobalImport(original) +
          Patch.removeGlobalImport(Symbol(stem(original) + "."))
      )
      .asPatch
  }

  /** A type and its companion term share a stem: `a/B#` and `a/B.`. */
  private def stem(symbol: Symbol): String = symbol.value.dropRight(1)

  private def insideImport(tree: Tree): Boolean =
    tree.parent.exists {
      case _: Import => true
      case other     => insideImport(other)
    }

  private def debug(config: TypeclassWeakeningConfig, message: String): Unit =
    if (config.debug) System.err.println(s"[TypeclassWeakening] $message")
}
