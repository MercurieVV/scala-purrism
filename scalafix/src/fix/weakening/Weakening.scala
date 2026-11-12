package fix.weakening

import java.nio.file.Path

import scala.annotation.tailrec
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
  *
  * Whenever the rewrite could produce code that no longer compiles, the bound
  * is kept instead: a type parameter holding more than one evidence (see
  * [[Evidences.several]]), a def one of whose call sites in this file would
  * find two candidate instances for the weakened typeclass (see
  * [[Evidences.ambiguousAtCallSite]]), a target whose simple name already means
  * something else in this file, and an owner overlapping a tree another rule
  * rewrites in the same run (`rewritten`).
  */
private[fix] object Weakening {

  /** @param rewritten
    *   trees other rules of the same run replace wholesale (the umbrella's
    *   `PreferKleisli` and `PreferArrow` rewrites): a bound inside or around
    *   one is kept, since both patches would edit the same tokens.
    * @param keptParams
    *   type parameters another rule of the same run builds on (the umbrella's
    *   `PreferTypeParameters` reuses one, relying on its bound as written, or
    *   appends a parameter after it): a bound inside one is kept.
    */
  def patch(
      classpath: List[Path],
      config: TypeclassWeakeningConfig,
      rewritten: List[Tree] = Nil,
      keptParams: List[Tree] = Nil
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
        case Right(index) => patchWith(index, rewritten, keptParams)
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

  /** The bounds of unary higher-kinded type parameters only -- the same filter
    * as [[Requirements.sites]]: `A: Ordering` or `A: ClassTag` can never be a
    * site, so scanning their jars would be wasted.
    */
  private[weakening] def boundSymbols(tree: Tree)(using
      SemanticDocument
  ): Set[Symbol] =
    tree
      .collect {
        case defn: Defn.Def =>
          defn.paramClauseGroups.flatMap(_.tparamClause.values)
        case cls: Defn.Class => cls.tparamClause.values
        case tr: Defn.Trait  => tr.tparamClause.values
      }
      .flatten
      .filter(_.tparamClause.values.size == 1)
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
      index: CatsIndex,
      rewritten: List[Tree],
      keptParams: List[Tree]
  )(using doc: SemanticDocument): Patch = {
    val rewrittenSpans = rewritten.map(_.pos)
    val keptSpans = keptParams.map(_.pos)
    val candidates = Requirements
      .sites(doc.tree, index)
      .filterNot(overrides)
      .filterNot(site => rewrittenSpans.exists(overlaps(site.owner.pos, _)))
      .filterNot(site => keptSpans.exists(overlaps(site.bound.pos, _)))
      .filterNot(Evidences.several)
    val decisions = settle(candidates, index)
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

  private def overlaps(a: Position, b: Position): Boolean =
    a.start < b.end && b.start < a.end

  /** Decides `sites`, then drops every owner whose replacement would not
    * compile (see [[vetoed]]) and decides again: dropping an owner turns it
    * from a local helper back into a plain signature, which can change its
    * callers' needs. Each round drops at least one owner, so it terminates.
    */
  @tailrec
  private def settle(
      sites: List[BoundSite],
      index: CatsIndex
  )(using SemanticDocument): List[(BoundSite, Decision)] = {
    val decisions = decideAll(sites, index)
    val dropped = decisions.collect {
      case (site, Decision.Replace(target)) if vetoed(site, target, index) =>
        site.owner
    }
    if (dropped.isEmpty) decisions
    else
      settle(
        sites.filterNot(site => dropped.exists(_ eq site.owner)),
        index
      )
  }

  private def vetoed(site: BoundSite, target: Rendered, index: CatsIndex)(using
      SemanticDocument
  ): Boolean =
    nameClash(target, index) ||
      Evidences.ambiguousAtCallSite(
        callees(site.owner),
        target.symbol,
        index
      )

  /** The symbols a call to `owner` resolves to: the def itself, or a class and
    * its primary constructor.
    */
  private def callees(owner: Tree)(using SemanticDocument): Set[Symbol] =
    (owner match {
      case cls: Defn.Class => Set(cls.name.symbol, cls.ctor.name.symbol)
      case other           => ownerSymbol(other).toSet
    }).filterNot(_.isNone)

  /** `target.renderName` already names something else in this file -- a local
    * `trait Functor`, another library's `Functor` imported explicitly -- so the
    * emitted bound would not mean the target. A name the document cannot
    * resolve counts as a clash, except an importee whose path is the target's.
    */
  private def nameClash(target: Rendered, index: CatsIndex)(using
      doc: SemanticDocument
  ): Boolean =
    doc.tree
      .collect {
        case name: Name if name.value == target.renderName => name
      }
      .exists { name =>
        val symbol = name.symbol
        val same = !symbol.isNone && (symbol == target.symbol ||
          stem(symbol) == stem(target.symbol) ||
          index.aliasTarget(symbol).exists(_.target == target.symbol))
        !same && !importeePath(name).contains(target.importPath)
      }

  /** `a.b.C` for the `C` of `import a.b.C` (or of `a.b.{C => D}`). */
  private def importeePath(name: Name): Option[String] =
    name.parent.flatMap {
      case importee @ Importee.Name(imported) if imported eq name =>
        importee.parent.collect { case importer: Importer =>
          s"${importer.ref.syntax}.${imported.value}"
        }
      case _ => None
    }

  private def decideAll(
      sites: List[BoundSite],
      index: CatsIndex
  )(using SemanticDocument): List[(BoundSite, Decision)] = {
    val helperSites = localHelperSites(sites)
    val memo = helperSites.keys.toList
      .sortBy(_.value)
      .foldLeft(Map.empty[Symbol, Set[Symbol]]) { (memo, method) =>
        memo.updated(
          method,
          helperBounds(method, helperSites, index, Set.empty, memo)
        )
      }
    sites.map { site =>
      site -> decide(
        site,
        helperSites,
        index,
        ownerSymbol(site.owner).toSet,
        memo
      )
    }
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
        } || defn.name.symbol.info.exists(_.overriddenSymbols.nonEmpty) ||
        overridable(defn)
      case _ => false
    }

  /** A non-`private`, non-`final` member of a trait or of a non-final class (or
    * enum) can be overridden, possibly in another file, so its signature is
    * shared with overriders the rule cannot see. Members of objects, final
    * classes, givens and anonymous classes, and local defs, cannot be.
    */
  private def overridable(defn: Defn.Def): Boolean = {
    val closed = defn.mods.exists {
      case _: Mod.Final                                  => true
      case Mod.Private(_: Name.Anonymous | _: Term.This) => true
      case _                                             => false
    }
    !closed && enclosingTemplateOwner(defn).exists {
      case _: Defn.Trait => true
      case cls: Defn.Class =>
        !cls.mods.exists {
          case _: Mod.Final => true
          case _            => false
        }
      case _: Defn.Enum => true
      case _            => false
    }
  }

  /** The definition whose template directly holds `defn`, looking through an
    * `extension` group (whose methods are members like any other); `None` for a
    * local def, whose parent is a block or another def's body.
    */
  private def enclosingTemplateOwner(defn: Defn.Def): Option[Tree] = {
    def up(tree: Tree): Option[Tree] =
      tree.parent.flatMap {
        case template: Template         => template.parent
        case body: Template.Body        => up(body)
        case group: Defn.ExtensionGroup => up(group)
        case block: Term.Block =>
          block.parent
            .collect { case group: Defn.ExtensionGroup => group }
            .flatMap(up)
        case _ => None
      }
    up(defn)
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
    *
    * The importee itself is removed: `Patch.removeGlobalImport` leaves an
    * explicit `import cats.Monad` in place.
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
    val stale = replaced
      .map(_._1.bound.symbol)
      .distinct
      .filterNot(original =>
        references.contains(stem(original)) ||
          added.contains(original.displayName)
      )
      .map(stem)
      .toSet
    if (stale.isEmpty) Patch.empty
    else
      doc.tree.collect {
        case importee: Importee.Name if stale(stem(importee.name.symbol)) =>
          Patch.removeImportee(importee)
      }.asPatch
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
