package fix

import scala.meta._

import scalafix.v1._

final class TypelevelPurrism extends SemanticRule("TypelevelPurrism") {
  override def fix(implicit doc: SemanticDocument): Patch =
    new TypeclassWeakening().fix + new PreferKleisli().fix + new OpaqueTypePropagation().fix
}

final class TypeclassWeakening extends SemanticRule("TypeclassWeakening") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val typeclassWeakenings =
      TypeclassWeakening.contextBoundWeakenings(doc.tree, Some(doc))
    val stillUsedTypeclasses =
      TypeclassWeakening.typeclassNamesStillUsed(
        doc.tree,
        typeclassWeakenings
      )

    typeclassWeakenings
      .map(weakening =>
        typeclassWeakeningPatch(
          weakening,
          removeOriginalImport = !stillUsedTypeclasses.contains(
            weakening.originalName
          )
        )
      )
      .asPatch
  }

  private def typeclassWeakeningPatch(
      weakening: TypelevelPurrism.TypeclassWeakening,
      removeOriginalImport: Boolean
  )(implicit doc: SemanticDocument): Patch =
    Patch.addGlobalImport(Symbol("cats/Monad#")) +
      (if (removeOriginalImport)
         Patch.removeGlobalImport(weakening.originalSymbol)
       else Patch.empty) +
      Patch.replaceTree(weakening.original, weakening.replacement)
}

object TypeclassWeakening {
  def contextBoundWeakenings(
      tree: Tree
  ): List[TypelevelPurrism.TypeclassWeakening] =
    TypelevelPurrism.contextBoundWeakenings(tree)

  def contextBoundWeakenings(
      tree: Tree,
      semanticDocument: Option[SemanticDocument]
  ): List[TypelevelPurrism.TypeclassWeakening] =
    TypelevelPurrism.contextBoundWeakenings(tree, semanticDocument)

  def contextBoundWeakenings(
      defn: Defn.Def
  ): List[TypelevelPurrism.TypeclassWeakening] =
    TypelevelPurrism.contextBoundWeakenings(defn)

  def contextBoundWeakenings(
      cls: Defn.Class
  ): List[TypelevelPurrism.TypeclassWeakening] =
    TypelevelPurrism.contextBoundWeakenings(cls)

  def contextBoundWeakenings(
      traitDef: Defn.Trait
  ): List[TypelevelPurrism.TypeclassWeakening] =
    TypelevelPurrism.contextBoundWeakenings(traitDef)

  def typeclassNamesStillUsed(
      tree: Tree,
      weakenings: List[TypelevelPurrism.TypeclassWeakening]
  ): Set[String] =
    TypelevelPurrism.typeclassNamesStillUsed(tree, weakenings)
}

final class PreferKleisli extends SemanticRule("PreferKleisli") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val knownKleislies = PreferKleisli.collectKleisliNames(doc.tree)
    val rewrites = PreferKleisli.rewritePlan(doc.tree, knownKleislies)

    rewrites.map { case (defn, rewritten) =>
      kleisliPatch(defn, rewritten)
    }.asPatch +
      PreferKleisli.sequencedLocalCompositionPatches(
        doc.tree,
        knownKleislies
      )
  }

  private def kleisliPatch(
      defn: Defn.Def,
      rewritten: String
  )(implicit doc: SemanticDocument): Patch =
    Patch.addGlobalImport(Symbol("cats/data/Kleisli#")) +
      Patch.replaceTree(defn, rewritten) +
      PreferKleisli.tupledCallPatches(doc.tree, defn)
}

object PreferKleisli {
  def kleisliRewrite(
      defn: Defn.Def,
      knownKleislies: Set[String] = Set.empty
  ): Option[String] =
    TypelevelPurrism.kleisliRewrite(defn, knownKleislies)

  def kleisliCompositionRewrite(
      defn: Defn.Def,
      knownKleislies: Set[String] = Set.empty
  ): Option[String] =
    TypelevelPurrism.kleisliCompositionRewrite(defn, knownKleislies)

  def collectKleisliNames(tree: Tree): Set[String] =
    TypelevelPurrism.collectKleisliNames(tree)

  def rewriteCandidates(tree: Tree): List[Defn.Def] =
    TypelevelPurrism.rewriteCandidates(tree)

  def rewritePlan(
      tree: Tree,
      knownKleislies: Set[String] = Set.empty
  ): List[(Defn.Def, String)] =
    TypelevelPurrism.kleisliRewritePlan(tree, knownKleislies)

  def tupledCallPatches(tree: Tree, defn: Defn.Def): Patch =
    TypelevelPurrism.tupledCallPatches(tree, defn)

  def sequencedLocalCompositionPatches(
      tree: Tree,
      knownKleislies: Set[String] = Set.empty
  ): Patch =
    TypelevelPurrism.sequencedLocalCompositionPatches(tree, knownKleislies)

  def sequencedLocalCompositionRewrites(
      tree: Tree,
      knownKleislies: Set[String] = Set.empty
  ): List[String] =
    TypelevelPurrism
      .sequencedLocalCompositionRewrites(tree, knownKleislies)
      .map(_.replacement)
}

private[fix] object TypelevelPurrism {
  final case class TypeclassWeakening(
      original: Type,
      originalName: String,
      replacement: String
  ) {
    def originalSymbol: Symbol =
      Symbol(
        originalName match {
          case "Sync"     => "cats/effect/Sync#"
          case "Async"    => "cats/effect/Async#"
          case "Temporal" => "cats/effect/Temporal#"
          case "Concurrent" =>
            "cats/effect/Concurrent#"
          case "MonadThrow" => "cats/MonadThrow#"
          case other        => s"cats/$other#"
        }
      )
  }

  private final case class AppliedCallee(
      term: Term,
      name: String,
      passesEffectType: Boolean
  )

  def kleisliRewrite(
      defn: Defn.Def,
      knownKleislies: Set[String] = Set.empty
  ): Option[String] =
    kleisliAliasRewrite(defn, knownKleislies)
      .orElse(kleisliCompositionRewrite(defn, knownKleislies))
      .orElse(kleisliApplyRewrite(defn))

  private def kleisliAliasRewrite(
      defn: Defn.Def,
      knownKleislies: Set[String]
  ): Option[String] =
    defn.decltpe.flatMap {
      case returnType: Type.Apply if isFResult(returnType) =>
        singlePlainParameter(defn).flatMap { param =>
          for {
            call <- finalKleisliCall(defn.body, knownKleislies)
            if isSameArgument(call.argument, param.name.syntax)
          } yield {
            val modifiers = renderModifiers(defn.mods)
            val parameterType = param.decltpe.map(_.syntax).getOrElse("")
            val result = returnType.argClause.values.head

            s"""${modifiers}def ${defn.name.syntax}: Kleisli[${returnType.tpe.syntax}, $parameterType, ${result.syntax}] =
               |  ${call.callee}""".stripMargin
          }
        }
      case _ =>
        None
    }

  private def kleisliApplyRewrite(defn: Defn.Def): Option[String] =
    plainParameters(defn).flatMap { params =>
      defn.decltpe.collect {
        case returnType: Type.Apply
            if isKleisliCandidate(defn, params, returnType) =>
          val modifiers = renderModifiers(defn.mods)
          val parameterPattern = kleisliInputPattern(defn, params)
          val parameterType = kleisliInputType(params)
          val result = returnType.argClause.values.head
          val body = kleisliBody(defn, params)

          s"""${modifiers}def ${defn.name.syntax}: Kleisli[${returnType.tpe.syntax}, $parameterType, ${result.syntax}] =
             |  Kleisli.apply { $parameterPattern =>
             |    $body
             |  }""".stripMargin
      }
    }

  def kleisliCompositionRewrite(
      defn: Defn.Def,
      knownKleislies: Set[String] = Set.empty
  ): Option[String] =
    defn.decltpe.flatMap {
      case returnType: Type.Apply if isFResult(returnType) =>
        singlePlainParameter(defn).flatMap { param =>
          composition(defn.body, param.name.syntax, knownKleislies).map {
            rewrite =>
              val modifiers = renderModifiers(defn.mods)
              val parameterType = param.decltpe.map(_.syntax).getOrElse("")
              val result = returnType.argClause.values.head

              s"""${modifiers}def ${defn.name.syntax}: Kleisli[${returnType.tpe.syntax}, $parameterType, ${result.syntax}] =
                 |${indent(rewrite.expression, 2)}""".stripMargin
          }
        }
      case returnType: Type.Apply if isKleisliResult(returnType) =>
        compositionBody(defn.body, knownKleislies).map { rewrite =>
          val modifiers = renderModifiers(defn.mods)

          s"""${modifiers}def ${defn.name.syntax}: ${returnType.syntax} =
             |${indent(rewrite.expression, 2)}""".stripMargin
        }
      case _ =>
        None
    }

  def collectKleisliNames(tree: Tree): Set[String] =
    tree
      .collect {
        case param: Term.Param if param.decltpe.exists(isKleisliType) =>
          Set(param.name.syntax)
        case defn: Defn.Def if defn.decltpe.exists(isKleisliType) =>
          Set(defn.name.syntax)
        case valDef: Defn.Val if valDef.decltpe.exists(isKleisliType) =>
          valDef.pats.collect { case name: Pat.Var => name.name.syntax }.toSet
      }
      .foldLeft(Set.empty[String])(_ ++ _)

  def rewriteCandidates(tree: Tree): List[Defn.Def] = {
    def fromStats(stats: List[Stat]): List[Defn.Def] =
      stats.flatMap {
        case defn: Defn.Def =>
          List(defn)
        case cls: Defn.Class =>
          fromStats(templateStats(cls.templ))
        case obj: Defn.Object =>
          fromStats(templateStats(obj.templ))
        case traitDef: Defn.Trait =>
          fromStats(templateStats(traitDef.templ))
        case pkg: Pkg =>
          fromStats(pkg.body.stats)
        case _ =>
          Nil
      }

    tree match {
      case source: Source =>
        fromStats(source.stats)
      case pkg: Pkg =>
        fromStats(pkg.body.stats)
      case cls: Defn.Class =>
        fromStats(templateStats(cls.templ))
      case obj: Defn.Object =>
        fromStats(templateStats(obj.templ))
      case traitDef: Defn.Trait =>
        fromStats(templateStats(traitDef.templ))
      case _ =>
        Nil
    }
  }

  def kleisliRewritePlan(
      tree: Tree,
      knownKleislies: Set[String] = Set.empty
  ): List[(Defn.Def, String)] = {
    val candidateRewrites =
      rewriteCandidates(tree).flatMap { defn =>
        kleisliRewrite(defn, knownKleislies).map(defn -> _)
      }
    val rewrites =
      candidateRewrites.filterNot { case (defn, _) =>
        plainParameters(defn).exists(_.length > 1) &&
        hasPlaceholderCallSite(tree, defn)
      }
    val tupledRewriteNames =
      rewrites.collect {
        case (defn, _) if plainParameters(defn).exists(_.length > 1) =>
          defn.name.value
      }.toSet

    rewrites.filterNot { case (defn, _) =>
      plainParameters(defn).exists(_.length > 1) &&
      callsAnyMethod(defn.body, tupledRewriteNames - defn.name.value)
    }
  }

  def tupledCallPatches(tree: Tree, defn: Defn.Def): Patch =
    plainParameters(defn).filter(_.length > 1).fold(Patch.empty) { params =>
      val methodName = defn.name.value
      val arity = params.length

      tree.collect {
        case applyTerm: Term.Apply
            if callName(applyTerm.fun).contains(methodName) &&
              applyTerm.argClause.values.length == arity &&
              outsideTree(applyTerm, defn) =>
          Patch.replaceTree(
            applyTerm.argClause,
            tupledArguments(applyTerm.argClause.values)
          )
      }.asPatch
    }

  private def templateStats(templ: Template): List[Stat] =
    templ.body.stats

  def contextBoundWeakenings(tree: Tree): List[TypeclassWeakening] =
    contextBoundWeakenings(tree, None)

  def contextBoundWeakenings(
      tree: Tree,
      semanticDocument: Option[SemanticDocument]
  ): List[TypeclassWeakening] = {
    val helperDefinitions = helperDefinitionsByName(tree)
    val helperRequirements = helperRequiredTypeclasses(tree)

    tree.collect {
      case defn: Defn.Def =>
        contextBoundWeakenings(
          defn,
          helperDefinitions,
          helperRequirements,
          semanticDocument
        )
      case cls: Defn.Class =>
        contextBoundWeakenings(
          cls,
          helperDefinitions,
          helperRequirements,
          semanticDocument
        )
      case traitDef: Defn.Trait =>
        contextBoundWeakenings(
          traitDef,
          helperDefinitions,
          helperRequirements,
          semanticDocument
        )
    }.flatten
  }

  def contextBoundWeakenings(defn: Defn.Def): List[TypeclassWeakening] =
    contextBoundWeakenings(defn, Map.empty)

  private def contextBoundWeakenings(
      defn: Defn.Def,
      helperDefinitions: Map[String, List[Defn.Def]],
      helperRequirements: Map[String, Set[String]],
      semanticDocument: Option[SemanticDocument]
  ): List[TypeclassWeakening] =
    defn.paramClauseGroups.flatMap { group =>
      weakenContextBounds(
        group.tparamClause.values,
        defn,
        helperDefinitions,
        helperRequirements,
        semanticDocument
      )
    }

  private def contextBoundWeakenings(
      defn: Defn.Def,
      helperRequirements: Map[String, Set[String]]
  ): List[TypeclassWeakening] =
    defn.paramClauseGroups.flatMap { group =>
      weakenContextBounds(
        group.tparamClause.values,
        defn,
        Map.empty,
        helperRequirements,
        None
      )
    }

  def contextBoundWeakenings(cls: Defn.Class): List[TypeclassWeakening] =
    contextBoundWeakenings(cls, Map.empty)

  private def contextBoundWeakenings(
      cls: Defn.Class,
      helperDefinitions: Map[String, List[Defn.Def]],
      helperRequirements: Map[String, Set[String]],
      semanticDocument: Option[SemanticDocument]
  ): List[TypeclassWeakening] =
    weakenContextBounds(
      cls.tparamClause.values,
      cls,
      helperDefinitions,
      helperRequirements,
      semanticDocument
    )

  private def contextBoundWeakenings(
      cls: Defn.Class,
      helperRequirements: Map[String, Set[String]]
  ): List[TypeclassWeakening] =
    weakenContextBounds(
      cls.tparamClause.values,
      cls,
      Map.empty,
      helperRequirements,
      None
    )

  def contextBoundWeakenings(
      traitDef: Defn.Trait
  ): List[TypeclassWeakening] =
    contextBoundWeakenings(traitDef, Map.empty)

  private def contextBoundWeakenings(
      traitDef: Defn.Trait,
      helperDefinitions: Map[String, List[Defn.Def]],
      helperRequirements: Map[String, Set[String]],
      semanticDocument: Option[SemanticDocument]
  ): List[TypeclassWeakening] =
    weakenContextBounds(
      traitDef.tparamClause.values,
      traitDef,
      helperDefinitions,
      helperRequirements,
      semanticDocument
    )

  private def contextBoundWeakenings(
      traitDef: Defn.Trait,
      helperRequirements: Map[String, Set[String]]
  ): List[TypeclassWeakening] =
    weakenContextBounds(
      traitDef.tparamClause.values,
      traitDef,
      Map.empty,
      helperRequirements,
      None
    )

  def typeclassNamesStillUsed(
      tree: Tree,
      weakenings: List[TypeclassWeakening]
  ): Set[String] =
    tree
      .collect {
        case name: Type.Name
            if MonadBoundCandidates.contains(name.value) &&
              !insideWeakenedBound(name, weakenings) =>
          Set(name.value)
        case name: Term.Name if MonadBoundCandidates.contains(name.value) =>
          Set(name.value)
      }
      .foldLeft(Set.empty[String])(_ ++ _)

  private def singlePlainParameter(defn: Defn.Def): Option[Term.Param] =
    plainParameters(defn).collect { case List(param) => param }

  private def plainParameters(defn: Defn.Def): Option[List[Term.Param]] =
    defn.paramClauseGroups match {
      case List(group)
          if group.tparamClause.values.isEmpty &&
            group.paramClauses.length == 1 &&
            group.paramClauses.head.mod.isEmpty =>
        group.paramClauses.head.values match {
          case params if params.nonEmpty => Some(params)
          case _                         => None
        }
      case _ =>
        None
    }

  private def isKleisliCandidate(
      defn: Defn.Def,
      params: List[Term.Param],
      returnType: Type.Apply
  ): Boolean =
    !defn.mods.exists(_.is[Mod.Override]) &&
      params.forall(param =>
        param.mods.isEmpty &&
          param.decltpe.exists(!_.is[Type.Repeated]) &&
          param.default.isEmpty
      ) &&
      selfRecursionIsSafe(defn, params) &&
      isFResult(returnType)

  private def kleisliInputPattern(
      defn: Defn.Def,
      params: List[Term.Param]
  ): String =
    params match {
      case List(param) => param.name.syntax
      case many if callsMethod(defn.body, defn.name.value) =>
        s"case input @ ${many.map(_.name.syntax).mkString("(", ", ", ")")}"
      case many =>
        s"case ${many.map(_.name.syntax).mkString("(", ", ", ")")}"
    }

  private def kleisliInputType(params: List[Term.Param]): String =
    params match {
      case List(param) => param.decltpe.map(_.syntax).getOrElse("")
      case many =>
        many
          .map(_.decltpe.map(_.syntax).getOrElse(""))
          .mkString("(", ", ", ")")
    }

  private def kleisliBody(defn: Defn.Def, params: List[Term.Param]): String =
    normalizeScala3Varargs {
      val bodyText =
        if (defn.body.pos.text.nonEmpty) defn.body.pos.text
        else defn.body.syntax

      if (params.length > 1 && callsMethod(defn.body, defn.name.value))
        replaceExactSelfCalls(bodyText, defn.name.value, params)
      else bodyText
    }

  private def isFResult(returnType: Type.Apply): Boolean =
    returnType.tpe.syntax == "F" &&
      returnType.argClause.values.length == 1

  private def isKleisliResult(returnType: Type.Apply): Boolean =
    returnType.tpe.syntax == "Kleisli" &&
      returnType.argClause.values.length == 3

  private def isKleisliType(tpe: Type): Boolean =
    tpe match {
      case returnType: Type.Apply => isKleisliResult(returnType)
      case _                      => false
    }

  private val MonadBoundCandidates =
    Set("Async", "Concurrent", "MonadThrow", "Sync", "Temporal")

  private val MonadOperations =
    Set("flatMap", "flatten", "ifM", "map", "product", "pure", "replicateA")

  private val StrongerThanMonadOperations =
    Set(
      "adaptError",
      "async",
      "blocking",
      "cede",
      "defer",
      "delay",
      "fromEither",
      "fromFuture",
      "fromOption",
      "handleError",
      "handleErrorWith",
      "interruptible",
      "raiseError",
      "realTime",
      "ref",
      "sleep",
      "start",
      "uncancelable",
      "unique"
    )

  private def weakenContextBounds(
      tparams: List[Type.Param],
      owner: Tree,
      helperDefinitions: Map[String, List[Defn.Def]],
      helperRequirements: Map[String, Set[String]],
      semanticDocument: Option[SemanticDocument]
  ): List[TypeclassWeakening] =
    tparams.flatMap { tparam =>
      if (typeParamClause(tparam).values.isEmpty) Nil
      else {
        val effectTypeName = tparam.name.syntax

        contextBounds(typeBounds(tparam)).flatMap { bound =>
          typeName(bound).flatMap {
            case name
                if MonadBoundCandidates.contains(name) &&
                  needsAtMostMonad(
                    owner,
                    effectTypeName,
                    helperDefinitions,
                    helperRequirements,
                    semanticDocument
                  ) =>
              Some(TypeclassWeakening(bound, name, "Monad"))
            case _ =>
              None
          }
        }
      }
    }

  private def typeParamClause(tparam: Type.Param): Type.ParamClause =
    tparam.productElement(2).asInstanceOf[Type.ParamClause]

  private def typeBounds(tparam: Type.Param): Type.Bounds =
    tparam.productElement(3).asInstanceOf[Type.Bounds]

  private def contextBounds(bounds: Type.Bounds): List[Type] =
    bounds.productElement(2).asInstanceOf[List[Type]]

  private def needsAtMostMonad(
      owner: Tree,
      effectTypeName: String,
      helperDefinitions: Map[String, List[Defn.Def]],
      helperRequirements: Map[String, Set[String]],
      semanticDocument: Option[SemanticDocument]
  ): Boolean = {
    val operations = selectedOperationNames(owner)
    val hasRelevantEffectShape =
      hasEffectValue(owner, effectTypeName) || hasEffectResult(
        owner,
        effectTypeName
      )

    hasRelevantEffectShape &&
    operations.exists(MonadOperations.contains) &&
    !operations.exists(StrongerThanMonadOperations.contains) &&
    !usesStrongerTypeclass(owner) &&
    !callsHelperRequiringStrongerTypeclass(
      owner,
      effectTypeName,
      helperDefinitions,
      helperRequirements,
      semanticDocument,
      Set.empty
    )
  }

  private def hasEffectValue(owner: Tree, effectTypeName: String): Boolean =
    owner.collect {
      case param: Term.Param
          if param.decltpe.exists(isEffectType(_, effectTypeName)) =>
        true
      case valDef: Defn.Val
          if valDef.decltpe.exists(isEffectType(_, effectTypeName)) =>
        true
      case varDef: Defn.Var
          if varDef.decltpe.exists(isEffectType(_, effectTypeName)) =>
        true
    }.nonEmpty

  private def hasEffectResult(owner: Tree, effectTypeName: String): Boolean =
    owner.collect {
      case defn: Defn.Def
          if defn.decltpe.exists(isEffectType(_, effectTypeName)) =>
        true
    }.nonEmpty

  private def isEffectType(tpe: Type, effectTypeName: String): Boolean =
    tpe match {
      case Type.Apply.After_4_6_0(Type.Name(name), args) =>
        name == effectTypeName && args.values.length == 1
      case _ =>
        false
    }

  private def selectedOperationNames(owner: Tree): Set[String] =
    owner.collect { case select: Term.Select =>
      select.name.value
    }.toSet

  private def usesStrongerTypeclass(owner: Tree): Boolean =
    owner.collect {
      case Term.Name(name) if MonadBoundCandidates.contains(name) =>
        true
    }.nonEmpty

  private def helperDefinitionsByName(
      tree: Tree
  ): Map[String, List[Defn.Def]] =
    tree
      .collect { case defn: Defn.Def =>
        Map(defn.name.value -> List(defn))
      }
      .foldLeft(Map.empty[String, List[Defn.Def]]) { case (all, next) =>
        next.foldLeft(all) { case (acc, (name, defns)) =>
          acc.updated(name, acc.getOrElse(name, Nil) ++ defns)
        }
      }

  private def helperRequiredTypeclasses(tree: Tree): Map[String, Set[String]] =
    tree
      .collect { case defn: Defn.Def =>
        val required =
          defn.paramClauseGroups
            .flatMap { group =>
              group.tparamClause.values.flatMap { tparam =>
                contextBounds(typeBounds(tparam)).flatMap(typeName)
              } ++ group.paramClauses.flatMap { clause =>
                clause.values.flatMap(param =>
                  param.decltpe.flatMap(typeclassEvidenceName)
                )
              }
            }
            .filter(MonadBoundCandidates.contains)
            .toSet

        if (required.isEmpty) Map.empty[String, Set[String]]
        else Map(defn.name.value -> required)
      }
      .foldLeft(Map.empty[String, Set[String]]) { case (all, next) =>
        next.foldLeft(all) { case (acc, (name, required)) =>
          acc.updated(name, acc.getOrElse(name, Set.empty) ++ required)
        }
      }

  private def callsHelperRequiringStrongerTypeclass(
      owner: Tree,
      effectTypeName: String,
      helperDefinitions: Map[String, List[Defn.Def]],
      helperRequirements: Map[String, Set[String]],
      semanticDocument: Option[SemanticDocument],
      seen: Set[String]
  ): Boolean =
    appliedCallees(owner, effectTypeName).exists { callee =>
      val semanticRequirements =
        semanticDocument
          .map(semanticRequiredTypeclasses(callee.term)(using _))
          .getOrElse(Set.empty)
      val syntaxRequirements =
        helperRequirements.getOrElse(callee.name, Set.empty)
      val requirements = semanticRequirements ++ syntaxRequirements

      (requirements.nonEmpty || callee.passesEffectType) &&
      !MonadOperations.contains(callee.name) &&
      !localHelpersNeedAtMostMonad(
        callee.name,
        effectTypeName,
        helperDefinitions,
        helperRequirements,
        semanticDocument,
        seen
      )
    }

  private def localHelpersNeedAtMostMonad(
      name: String,
      effectTypeName: String,
      helperDefinitions: Map[String, List[Defn.Def]],
      helperRequirements: Map[String, Set[String]],
      semanticDocument: Option[SemanticDocument],
      seen: Set[String]
  ): Boolean =
    helperDefinitions.get(name).exists { defns =>
      defns.forall { defn =>
        val key = defn.pos.start.toString

        seen.contains(key) || {
          val operations = selectedOperationNames(defn)

          !operations.exists(StrongerThanMonadOperations.contains) &&
          !usesStrongerTypeclass(defn) &&
          !callsUnprovenOperation(
            defn,
            effectTypeName,
            helperDefinitions,
            helperRequirements,
            semanticDocument,
            seen + key
          ) &&
          !callsHelperRequiringStrongerTypeclass(
            defn,
            effectTypeName,
            helperDefinitions,
            helperRequirements,
            semanticDocument,
            seen + key
          )
        }
      }
    }

  private def callsUnprovenOperation(
      owner: Tree,
      effectTypeName: String,
      helperDefinitions: Map[String, List[Defn.Def]],
      helperRequirements: Map[String, Set[String]],
      semanticDocument: Option[SemanticDocument],
      seen: Set[String]
  ): Boolean =
    appliedCallees(owner, effectTypeName).exists { callee =>
      val semanticRequirements =
        semanticDocument
          .map(semanticRequiredTypeclasses(callee.term)(using _))
          .getOrElse(Set.empty)
      val syntaxRequirements =
        helperRequirements.getOrElse(callee.name, Set.empty)

      !SafeOperationsInsideWeakenableHelper.contains(callee.name) &&
      semanticRequirements.isEmpty &&
      syntaxRequirements.isEmpty &&
      !localHelpersNeedAtMostMonad(
        callee.name,
        effectTypeName,
        helperDefinitions,
        helperRequirements,
        semanticDocument,
        seen
      )
    }

  private def appliedCallees(
      owner: Tree,
      effectTypeName: String
  ): List[AppliedCallee] =
    owner.collect {
      case apply: Term.Apply =>
        appliedCallee(apply.fun, effectTypeName).toList
      case applyType: Term.ApplyType =>
        appliedCallee(applyType, effectTypeName).toList
    }.flatten

  private def appliedCallee(
      term: Term,
      effectTypeName: String
  ): Option[AppliedCallee] =
    term match {
      case name: Term.Name =>
        Some(AppliedCallee(name, name.value, passesEffectType = false))
      case select: Term.Select =>
        Some(
          AppliedCallee(select, select.name.value, passesEffectType = false)
        )
      case applyType: Term.ApplyType =>
        appliedCallee(applyType.fun, effectTypeName).map { callee =>
          callee.copy(
            passesEffectType = callee.passesEffectType ||
              applyType.targClause.values.exists(
                isPlainTypeName(_, effectTypeName)
              )
          )
        }
      case _ =>
        None
    }

  private def semanticRequiredTypeclasses(
      term: Term
  )(implicit doc: SemanticDocument): Set[String] =
    term.symbol.info
      .map(info => signatureRequiredTypeclasses(info.signature))
      .getOrElse(Set.empty)

  private def signatureRequiredTypeclasses(signature: Signature): Set[String] =
    signature match {
      case MethodSignature(_, parameterLists, _) =>
        parameterLists.flatten.flatMap(parameterRequiredTypeclass).toSet
      case ValueSignature(tpe) =>
        semanticTypeRequiredTypeclasses(tpe)
      case _ =>
        Set.empty
    }

  private def parameterRequiredTypeclass(
      info: SymbolInformation
  ): Option[String] =
    info.signature match {
      case ValueSignature(tpe) =>
        semanticTypeclassEvidenceName(tpe)
      case _ =>
        None
    }

  private def semanticTypeRequiredTypeclasses(
      tpe: SemanticType
  ): Set[String] =
    tpe match {
      case TypeRef(_, symbol, typeArguments) =>
        val current =
          if (
            typeArguments.length == 1 &&
            MonadBoundCandidates.contains(symbol.displayName)
          ) Set(symbol.displayName)
          else Set.empty[String]

        current ++ typeArguments.flatMap(semanticTypeRequiredTypeclasses)
      case UniversalType(_, tpe) =>
        semanticTypeRequiredTypeclasses(tpe)
      case LambdaType(_, tpe) =>
        semanticTypeRequiredTypeclasses(tpe)
      case IntersectionType(types) =>
        types.flatMap(semanticTypeRequiredTypeclasses).toSet
      case UnionType(types) =>
        types.flatMap(semanticTypeRequiredTypeclasses).toSet
      case WithType(types) =>
        types.flatMap(semanticTypeRequiredTypeclasses).toSet
      case AnnotatedType(_, tpe) =>
        semanticTypeRequiredTypeclasses(tpe)
      case ByNameType(tpe) =>
        semanticTypeRequiredTypeclasses(tpe)
      case RepeatedType(tpe) =>
        semanticTypeRequiredTypeclasses(tpe)
      case _ =>
        Set.empty
    }

  private def semanticTypeclassEvidenceName(tpe: SemanticType): Option[String] =
    tpe match {
      case TypeRef(_, symbol, List(_))
          if MonadBoundCandidates.contains(symbol.displayName) =>
        Some(symbol.displayName)
      case _ =>
        None
    }

  private def typeclassEvidenceName(tpe: Type): Option[String] =
    tpe match {
      case Type.Apply.After_4_6_0(tpe, args)
          if args.values.length == 1 && isPlainTypeName(args.values.head) =>
        typeName(tpe)
      case _ =>
        None
    }

  private def isPlainTypeName(tpe: Type): Boolean =
    tpe match {
      case Type.Name(_) => true
      case _            => false
    }

  private def isPlainTypeName(tpe: Type, effectTypeName: String): Boolean =
    tpe match {
      case Type.Name(name) => name == effectTypeName
      case _               => false
    }

  private val SafeOperationsInsideWeakenableHelper =
    MonadOperations ++ Set("identity")

  private def typeName(tpe: Type): Option[String] =
    tpe match {
      case Type.Name(value) =>
        Some(value)
      case Type.Select(_, Type.Name(value)) =>
        Some(value)
      case _ =>
        None
    }

  private def insideWeakenedBound(
      tree: Tree,
      weakenings: List[TypeclassWeakening]
  ): Boolean =
    weakenings.exists { weakening =>
      val owner = weakening.original.pos
      val candidate = tree.pos

      owner.start <= candidate.start && candidate.end <= owner.end
    }

  private def outsideTree(candidate: Tree, owner: Tree): Boolean =
    val ownerPosition = owner.pos
    val candidatePosition = candidate.pos

    candidatePosition.start < ownerPosition.start ||
    ownerPosition.end < candidatePosition.end

  private def callsMethod(tree: Tree, methodName: String): Boolean =
    tree.collect {
      case applyTerm: Term.Apply
          if callName(applyTerm.fun).contains(methodName) =>
        true
    }.nonEmpty

  private def selfRecursionIsSafe(
      defn: Defn.Def,
      params: List[Term.Param]
  ): Boolean = {
    val selfCalls = selfCallApplications(defn.body, defn.name.value)

    selfCalls.isEmpty ||
    params.length > 1 &&
    selfCalls.forall(selfCallPassesParameters(_, params))
  }

  private def selfCallApplications(
      tree: Tree,
      methodName: String
  ): List[Term.Apply] =
    tree.collect {
      case applyTerm: Term.Apply
          if callName(applyTerm.fun).contains(methodName) =>
        applyTerm
    }

  private def selfCallPassesParameters(
      applyTerm: Term.Apply,
      params: List[Term.Param]
  ): Boolean =
    applyTerm.argClause.values.map(_.syntax) == params.map(_.name.syntax)

  private def replaceExactSelfCalls(
      body: String,
      methodName: String,
      params: List[Term.Param]
  ): String = {
    val selfCall =
      params.map(_.name.syntax).mkString(s"$methodName(", ", ", ")")

    body.replace(selfCall, s"$methodName(input)")
  }

  private def normalizeScala3Varargs(body: String): String =
    body.replace(": _*", "*")

  private def callsAnyMethod(tree: Tree, methodNames: Set[String]): Boolean =
    methodNames.nonEmpty &&
      tree.collect {
        case applyTerm: Term.Apply
            if callName(applyTerm.fun).exists(methodNames.contains) =>
          true
      }.nonEmpty

  private def hasPlaceholderCallSite(tree: Tree, defn: Defn.Def): Boolean =
    plainParameters(defn).exists { params =>
      tree.collect {
        case applyTerm: Term.Apply
            if callName(applyTerm.fun).contains(defn.name.value) &&
              applyTerm.argClause.values.length == params.length &&
              outsideTree(applyTerm, defn) &&
              applyTerm.argClause.values.exists(containsPlaceholder) =>
          true
      }.nonEmpty
    }

  private def containsPlaceholder(tree: Tree): Boolean =
    tree.collect { case _: Term.Placeholder => true }.nonEmpty

  private def renderModifiers(mods: List[Mod]): String =
    if (mods.isEmpty) ""
    else mods.map(_.syntax).mkString("", " ", " ")

  private final case class CompositionRewrite(expression: String)

  private final case class SplitKleisliCall(callee: String, argument: Term)

  private final case class KleisliInput(
      alias: String,
      names: List[String],
      inputType: Option[String],
      valueTypes: List[String]
  )

  private final case class KleisliBody(input: KleisliInput, body: Term)

  private final case class SequencedProjection(call: SplitKleisliCall)

  final case class SequencedLocalCompositionRewrite(
      term: Term,
      replacement: String
  )

  def sequencedLocalCompositionPatches(
      tree: Tree,
      knownKleislies: Set[String] = Set.empty
  ): Patch =
    sequencedLocalCompositionRewrites(tree, knownKleislies).map {
      case SequencedLocalCompositionRewrite(term, replacement) =>
        Patch.replaceTree(term, replacement)
    }.asPatch

  def sequencedLocalCompositionRewrites(
      tree: Tree,
      knownKleislies: Set[String] = Set.empty
  ): List[SequencedLocalCompositionRewrite] =
    tree
      .collect { case defn: Defn.Def =>
        val inputType = defn.decltpe.flatMap(kleisliInputType)

        defn.body.collect { case applyTerm: Term.Apply =>
          kleisliBody(applyTerm, inputType).toList.flatMap {
            case KleisliBody(input, body) =>
              body.collect { case term: Term =>
                sequencedLocalComposition(term, input, knownKleislies)
                  .map(SequencedLocalCompositionRewrite(term, _))
              }.flatten
          }
        }
      }
      .flatten
      .flatten

  private def compositionBody(
      body: Term,
      knownKleislies: Set[String]
  ): Option[CompositionRewrite] =
    splitKleisliBody(body, knownKleislies).orElse(
      flatMapCompositionBody(body, knownKleislies)
    )

  private def flatMapCompositionBody(
      body: Term,
      knownKleislies: Set[String]
  ): Option[CompositionRewrite] =
    body match {
      case applyTerm: Term.Apply =>
        for {
          function <- kleisliApplyFunction(applyTerm)
          param <- singleFunctionParameter(function)
          rewrite <- composition(
            function.body,
            param.name.syntax,
            knownKleislies
          )
        } yield rewrite
      case _ =>
        None
    }

  private def kleisliBody(
      applyTerm: Term.Apply,
      inputType: Option[(String, List[String])]
  ): Option[KleisliBody] =
    for {
      function <- kleisliApplyPartialFunction(applyTerm)
      input <- singleInputCase(function, inputType)
    } yield input

  private def singleInputCase(
      function: Term.PartialFunction,
      inputType: Option[(String, List[String])]
  ): Option[KleisliBody] =
    partialFunctionCases(function) match {
      case List(Case(pattern, None, body)) =>
        inputPattern(pattern, inputType).map(KleisliBody(_, body))
      case _ =>
        None
    }

  private def partialFunctionCases(
      function: Term.PartialFunction
  ): List[Case] =
    function.cases

  private def inputPattern(
      pattern: Pat,
      inputType: Option[(String, List[String])]
  ): Option[KleisliInput] =
    pattern match {
      case Pat.Bind(Pat.Var(Term.Name(alias)), Pat.Tuple(values)) =>
        tuplePatternNames(values).map { names =>
          val (renderedInputType, valueTypes) =
            inputType.getOrElse("" -> Nil)
          KleisliInput(
            alias,
            names,
            Option.when(renderedInputType.nonEmpty)(renderedInputType),
            valueTypes
          )
        }
      case _ =>
        None
    }

  private def kleisliInputType(tpe: Type): Option[(String, List[String])] =
    tpe match {
      case returnType: Type.Apply if isKleisliResult(returnType) =>
        returnType.argClause.values.lift(1).collect {
          case tupleType: Type.Tuple =>
            tupleType.syntax -> tupleTypeValues(tupleType).map(_.syntax)
        }
      case _ =>
        None
    }

  private def tupleTypeValues(tupleType: Type.Tuple): List[Type] =
    tupleType.productElement(0).asInstanceOf[List[Type]]

  private def tuplePatternNames(values: List[Pat]): Option[List[String]] =
    values.foldRight(Option(List.empty[String])) {
      case (Pat.Var(Term.Name(name)), Some(names)) => Some(name :: names)
      case (_, _)                                  => None
    }

  private def sequencedLocalComposition(
      tree: Tree,
      input: KleisliInput,
      knownKleislies: Set[String]
  ): Option[String] =
    for {
      terms <- sequencedTerms(tree)
      if terms.length >= 2
      last <- terms.lastOption
      finalCall <- finalKleisliCall(last, knownKleislies)
        .orElse(finalSingleArgumentCall(last))
      if isSameArgument(finalCall.argument, input.alias)
      (prefix, projections) <- sequencedProjectionSuffix(
        terms.init,
        input,
        knownKleislies
      )
      if projections.nonEmpty
    } yield {
      val composed =
        (projections.map(localComposition(_, input)) :+ finalCall.callee)
          .mkString("(", " *> ", s").run(${input.alias})")

      prefix match {
        case Nil   => composed
        case terms => terms.map(_.syntax).mkString("", " *> ", s" *> $composed")
      }
    }

  private def sequencedProjectionSuffix(
      terms: List[Term],
      input: KleisliInput,
      knownKleislies: Set[String]
  ): Option[(List[Term], List[SequencedProjection])] = {
    val (reversedProjections, reversedPrefix) =
      terms.reverse.span(term =>
        sequencedProjection(term, input, knownKleislies).nonEmpty
      )
    val projections =
      reversedProjections.reverse.flatMap(
        sequencedProjection(_, input, knownKleislies)
      )

    Option.when(projections.nonEmpty)(reversedPrefix.reverse -> projections)
  }

  private def sequencedProjection(
      term: Term,
      input: KleisliInput,
      knownKleislies: Set[String]
  ): Option[SequencedProjection] =
    for {
      call <- finalKleisliCall(term, knownKleislies)
        .orElse(finalSingleArgumentCall(term))
      projectionNames <- tupleTermNames(call.argument)
      if projectionNames.nonEmpty
      if projectionNames.forall(input.names.contains)
    } yield SequencedProjection(call)

  private def localComposition(
      projection: SequencedProjection,
      input: KleisliInput
  ): String = {
    val projectionNames =
      tupleTermNames(projection.call.argument).getOrElse(Nil).toSet
    val localPattern =
      input.names
        .zipAll(input.valueTypes, "", "")
        .map { case (name, valueType) =>
          val renderedName =
            if (projectionNames.contains(name)) name else "_"
          if (valueType.nonEmpty) s"$renderedName: $valueType"
          else renderedName
        }
        .mkString("(", ", ", ")")
    val localType =
      input.inputType.fold("")(inputType => s"[$inputType]")

    s"""${projection.call.callee}.local$localType { case $localPattern =>
       |  ${projection.call.argument.syntax}
       |}""".stripMargin
  }

  private def sequencedTerms(term: Tree): Option[List[Term]] =
    term match {
      case Term.ApplyInfix.After_4_6_0(
            left,
            Term.Name("*>"),
            Type.ArgClause(Nil),
            Term.ArgClause(List(right), None)
          ) =>
        sequencedTerms(left).map(_ :+ right)
      case _ =>
        Some(List(term.asInstanceOf[Term]))
    }

  private def finalSingleArgumentCall(term: Term): Option[SplitKleisliCall] =
    term match {
      case applyTerm: Term.Apply =>
        applyTerm.argClause.values match {
          case List(argument: Term) =>
            Some(SplitKleisliCall(applyTerm.fun.syntax, argument))
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def tupleTermNames(term: Term): Option[List[String]] =
    term match {
      case Term.Tuple(values) =>
        values.foldRight(Option(List.empty[String])) {
          case (Term.Name(name), Some(names)) => Some(name :: names)
          case (_, _)                         => None
        }
      case _ =>
        None
    }

  private def splitKleisliBody(
      body: Term,
      knownKleislies: Set[String]
  ): Option[CompositionRewrite] =
    body match {
      case applyTerm: Term.Apply =>
        for {
          function <- kleisliApplyFunction(applyTerm)
          param <- singleFunctionParameter(function)
          split <- splitFinalKleisliCall(function.body, knownKleislies)
        } yield {
          val leftBody = renderFunctionBody(split.argument)

          CompositionRewrite(
            s"""${split.callee}.local { ${param.name.syntax} =>
               |${indent(leftBody, 2)}
               |}""".stripMargin
          )
        }
      case _ =>
        None
    }

  private def composition(
      body: Term,
      inputParameter: String,
      knownKleislies: Set[String]
  ): Option[CompositionRewrite] =
    body match {
      case applyTerm: Term.Apply =>
        for {
          firstCall <- selectQualifier(applyTerm.fun, "flatMap")
          function <- singleFunctionArgument(applyTerm)
          midParam <- singleFunctionParameter(function)
          first <- kleisliCall(firstCall, inputParameter, knownKleislies)
          second <- kleisliCall(
            function.body,
            midParam.name.syntax,
            knownKleislies
          )
        } yield CompositionRewrite(s"$first.andThen($second)")
      case _ =>
        None
    }

  private def splitFinalKleisliCall(
      body: Term,
      knownKleislies: Set[String]
  ): Option[SplitKleisliCall] =
    body match {
      case block: Term.Block =>
        block.stats.lastOption.flatMap {
          case last: Term =>
            finalKleisliCall(last, knownKleislies).map { call =>
              call.copy(argument =
                Term.Block(block.stats.dropRight(1) :+ call.argument)
              )
            }
          case _ =>
            None
        }
      case term =>
        finalKleisliCall(term, knownKleislies)
    }

  private def finalKleisliCall(
      term: Term,
      knownKleislies: Set[String]
  ): Option[SplitKleisliCall] =
    term match {
      case applyTerm: Term.Apply =>
        applyTerm.argClause.values match {
          case List(argument: Term) =>
            kleisliCallee(applyTerm.fun, knownKleislies).map { callee =>
              SplitKleisliCall(callee, argument)
            }
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def isSameArgument(argument: Term, name: String): Boolean =
    argument match {
      case Term.Name(value) => value == name
      case _                => false
    }

  private def callName(term: Term): Option[String] =
    term match {
      case Term.Name(value)     => Some(value)
      case Term.Select(_, name) => Some(name.value)
      case _                    => None
    }

  private def tupledArguments(arguments: List[Term]): String =
    arguments.map(_.syntax).mkString("((", ", ", "))")

  private def renderFunctionBody(body: Term): String =
    body match {
      case Term.Block(List(single)) =>
        single.syntax
      case Term.Block(stats) =>
        stats.map(_.syntax).mkString("\n")
      case other =>
        other.syntax
    }

  private def kleisliCall(
      term: Term,
      argumentName: String,
      knownKleislies: Set[String]
  ): Option[String] =
    term match {
      case applyTerm: Term.Apply if applyTerm.argClause.values match {
            case List(Term.Name(name)) => name == argumentName
            case _                     => false
          } =>
        kleisliCallee(applyTerm.fun, knownKleislies)
      case _ =>
        None
    }

  private def kleisliCallee(
      term: Term,
      knownKleislies: Set[String]
  ): Option[String] =
    term match {
      case select: Term.Select
          if select.name.value == "run" || select.name.value == "apply" =>
        Some(select.qual.syntax)
      case other if knownKleislies.contains(other.syntax) =>
        Some(other.syntax)
      case _ =>
        None
    }

  private def kleisliApplyFunction(
      applyTerm: Term.Apply
  ): Option[Term.Function] =
    for {
      callee <- selectQualifier(applyTerm.fun, "apply")
      _ <- callee match {
        case Term.Name("Kleisli") => Some(())
        case _                    => None
      }
      function <- singleFunctionArgument(applyTerm)
    } yield function

  private def kleisliApplyPartialFunction(
      applyTerm: Term.Apply
  ): Option[Term.PartialFunction] =
    for {
      callee <- selectQualifier(applyTerm.fun, "apply")
      _ <- callee match {
        case Term.Name("Kleisli") => Some(())
        case _                    => None
      }
      function <- singlePartialFunctionArgument(applyTerm)
    } yield function

  private def singleFunctionArgument(
      applyTerm: Term.Apply
  ): Option[Term.Function] =
    applyTerm.argClause.values match {
      case List(function: Term.Function) => Some(function)
      case List(Term.Block(List(function: Term.Function))) =>
        Some(function)
      case _ => None
    }

  private def singleFunctionParameter(
      function: Term.Function
  ): Option[Term.Param] =
    function.paramClause.values match {
      case List(param) => Some(param)
      case _           => None
    }

  private def selectQualifier(term: Term, name: String): Option[Term] =
    term match {
      case select: Term.Select if select.name.value == name =>
        Some(select.qual)
      case _ =>
        None
    }

  private def singlePartialFunctionArgument(
      applyTerm: Term.Apply
  ): Option[Term.PartialFunction] =
    applyTerm.argClause.values match {
      case List(function: Term.PartialFunction) => Some(function)
      case _                                    => None
    }

  private def indent(value: String, spaces: Int): String = {
    val prefix = " " * spaces

    value.linesIterator.map(prefix + _).mkString("\n")
  }

}
