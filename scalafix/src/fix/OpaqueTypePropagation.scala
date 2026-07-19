package fix

import scala.annotation.nowarn
import scala.meta._
import scalafix.v1._

final class OpaqueTypePropagation
    extends SemanticRule("OpaqueTypePropagation") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val tree =
      dialects.Scala3(doc.input.text).parse[Source].toOption.getOrElse(doc.tree)
    val chains =
      OpaqueTypePropagation.mergePropagationChains(
        OpaqueTypePropagation.findPropagationChains(tree)
      )
    val contextTree = OpaqueTypePropagation.contextTree(tree, doc.input)
    val contextChains =
      OpaqueTypePropagation.mergePropagationChains(
        OpaqueTypePropagation.findPropagationChains(contextTree)
      )
    val plan = OpaqueTypePropagation.rewritePlan(
      tree,
      chains,
      Some(doc.input),
      contextChains,
      Some(contextTree)
    )

    plan.patches(using doc)
  }
}

@nowarn
object OpaqueTypePropagation {

  val SupportedPrimitives: Set[String] = Set(
    "String",
    "Int",
    "Long",
    "BigDecimal",
    "Double",
    "UUID"
  )

  // Single-word generic names that should NEVER become top-level opaque types on their own
  val GenericParamNames: Set[String] = Set(
    "value",
    "values",
    "line",
    "lines",
    "str",
    "string",
    "s",
    "v",
    "x",
    "y",
    "z",
    "val",
    "var",
    "arg",
    "args",
    "param",
    "params",
    "input",
    "output",
    "item",
    "items",
    "elem",
    "elems",
    "obj",
    "object",
    "opt",
    "option",
    "res",
    "result",
    "seq",
    "list",
    "array",
    "data",
    "body",
    "content",
    "text",
    "message",
    "a",
    "b",
    "c",
    "d",
    "title",
    "name",
    "key",
    "label",
    "number",
    "state",
    "status",
    "path",
    "root",
    "file",
    "dir"
  )

  // Requires a multi-word domain prefix (e.g. userId, taskId, branchName, commitSha, deadlineMillis)
  private val DomainSuffixPattern =
    "(?i).+[A-Z_].*(id|name|branch|ref|amount|price|token|code|key|url|email|address|number|count|width|height|millis|seconds|sha)$".r

  final case class ParameterNode(
      name: String,
      paramType: String,
      paramTree: Term.Param,
      ownerDefName: Option[String]
  )

  final case class PropagationChain(
      typeName: String,
      primitiveType: String,
      nodes: List[ParameterNode],
      returnTypeDefs: List[Defn.Def]
  ) {
    def depth: Int = nodes.length + returnTypeDefs.length
  }

  def mergePropagationChains(
      chains: List[PropagationChain]
  ): List[PropagationChain] =
    chains
      .groupBy(chain => (chain.typeName, chain.primitiveType))
      .map { case ((typeName, primitiveType), grouped) =>
        PropagationChain(
          typeName = typeName,
          primitiveType = primitiveType,
          nodes = grouped
            .flatMap(_.nodes)
            .distinctBy(node =>
              (node.name, node.ownerDefName, node.paramTree.pos.start)
            ),
          returnTypeDefs = grouped
            .flatMap(_.returnTypeDefs)
            .distinctBy(_.pos.start)
        )
      }
      .toList
      .sortBy(_.typeName)

  final case class RewritePlan(
      chains: List[PropagationChain],
      opaqueTypeDefinitions: List[String],
      contextChains: List[PropagationChain],
      contextTree: Tree
  ) {
    @nowarn
    def patches(implicit doc: SemanticDocument): Patch = {
      if (chains.isEmpty) Patch.empty
      else {
        val typeDefCode =
          if (opaqueTypeDefinitions.nonEmpty)
            opaqueTypeDefinitions.mkString("\n\n") + "\n\n"
          else ""
        val headerPatch =
          if (typeDefCode.nonEmpty) insertTypeDefs(doc.tree, typeDefCode)
          else Patch.empty

        val companionEqPatches = chains.flatMap(chain =>
          ensureCompanionEqPatch(doc.tree, chain.typeName)
        )

        val paramPatches = chains.flatMap { chain =>
          chain.nodes.map { node =>
            node.paramTree.decltpe
              .map(replacePrimitiveType(_, chain.primitiveType, chain.typeName))
              .getOrElse(Patch.empty)
          }
        }

        val returnPatches = chains
          .flatMap(chain => chain.returnTypeDefs.map(defn => defn -> chain))
          .distinctBy { case (defn, _) => defn.pos.start }
          .flatMap { case (defn, chain) =>
            defn.decltpe.map { tpe =>
              if (kleisliInputType(tpe).isDefined) Patch.empty
              else
                replacePrimitiveType(tpe, chain.primitiveType, chain.typeName)
            }
          }

        val returnBodyMapPatches =
          chains.flatMap(_.returnTypeDefs).flatMap { defn =>
            defn.decltpe.flatMap { tpe =>
              chains.find(_.returnTypeDefs.contains(defn)).flatMap { chain =>
                if (
                  containsPrimitiveInMappableResult(
                    tpe,
                    chain.primitiveType
                  ) &&
                  !isOpaqueUsage(defn.body, chain) &&
                  !containsOpaqueConstructor(defn.body, chain.typeName)
                )
                  Some(
                    Patch.replaceTree(
                      defn.body,
                      s"${defn.body.syntax}.map(${chain.typeName}(_))"
                    )
                  )
                else None
              }
            }
          }

        val commandCollectionUnwrapPatches: List[Patch] = chains.flatMap {
          chain =>
            doc.tree.collect {
              case collection: Term.Apply
                  if isCommandLikeCollection(collection) =>
                unwrapCollectionOpaqueElementPatches(collection, chain)
            }.flatten
        }

        val opaqueToPrimitiveCollectionPatches: List[Patch] =
          opaqueToPrimitiveCollectionPatchesFor(doc.tree, chains)

        val valCollectionWrapPatches: List[Patch] = chains.flatMap { chain =>
          doc.tree.collect {
            case Defn.Val(_, List(Pat.Var(Term.Name(name))), Some(_), value)
                if isChainTargetName(name, chain) =>
              wrapIntroducedValuePatch(value, chain)
          }
        }

        val inferredValWrapPatches: List[Patch] = chains.flatMap { chain =>
          doc.tree.collect {
            case Defn.Val(_, List(Pat.Var(Term.Name(name))), None, value)
                if shouldWrapInferredValue(name, value, chain) =>
              wrapBoundaryExpressionPatch(value, chain)
          }
        }
        val inferredOpaqueNames =
          inferredOpaqueValueNames(doc.tree, chains).toSet

        val genericReturnTypeArgPatches =
          genericReturnTypeArgumentPatches(doc.tree, contextChains)
        val localCallBoundaryPatchList: List[Patch] =
          localCallBoundaryPatches(
            doc.tree,
            contextChains,
            Some(contextTree),
            inferredOpaqueNames
          )
        val kleisliInputTypePatchList: List[Patch] =
          kleisliInputTypePatches(doc.tree, chains)
        val kleisliLocalTypePatchList: List[Patch] =
          kleisliLocalTypePatches(doc.tree, chains)
        val opaqueConstructorArithmeticPatchList: List[Patch] =
          opaqueConstructorArithmeticPatches(
            doc.tree,
            contextChains,
            inferredOpaqueNames
          )

        val unwrapUsagePatches: List[Patch] = chains.flatMap { chain =>
          doc.tree.collect {
            case infix: Term.ApplyInfix
                if opIsPrimitiveArithmetic(infix.op.value) =>
              arithmeticOpaqueUnwrapPatches(infix, chain, inferredOpaqueNames)

            case Term.Apply(
                  Term.Select(Term.Name("Thread"), Term.Name("sleep")),
                  args
                ) =>
              args.collect {
                case nameTerm: Term.Name
                    if chain.nodes.exists(n => n.name == nameTerm.value) ||
                      (inferredOpaqueNames.contains(nameTerm.value) &&
                        termNameMatchesChain(nameTerm.value, chain)) =>
                  Patch.replaceTree(nameTerm, s"${nameTerm.value}.value")
              }

            case apply: Term.Apply =>
              val isPrimitiveApi = apply.fun match {
                case Term.Select(
                      Term.Name("os"),
                      Term.Name(
                        "proc" | "Path" | "RelPath" | "SubPath" | "read" |
                        "write" | "makeDir"
                      )
                    ) =>
                  true
                case Term.Select(Term.Name("System"), _)       => true
                case Term.Name("print" | "println" | "printf") => true
                case _                                         => false
              }
              if (isPrimitiveApi) {
                apply.argClause.values.flatMap(arg =>
                  unwrapPrimitiveArgument(
                    arg,
                    List(chain),
                    inferredOpaqueNames,
                    Some(chain.primitiveType)
                  )
                )
              } else
                optionPredicateEqualityPatches(apply, chain) ++
                  getOrElseDefaultWrapPatches(apply, chain)

            case apply: Term.ApplyInfix =>
              val op = apply.op.value
              if (
                op == "==" || op == "!=" || op == "===" || op == "!==" ||
                op == "=!="
              ) {
                equalityBoundaryPatches(apply, chain)
              } else if (op == ">=" || op == "<=" || op == ">" || op == "<") {
                val leftPatch = apply.lhs match {
                  case nameTerm: Term.Name
                      if isOpaqueOperatorOperand(
                        nameTerm,
                        chain,
                        inferredOpaqueNames
                      ) =>
                    List(
                      Patch.replaceTree(nameTerm, s"${nameTerm.value}.value")
                    )
                  case _ => Nil
                }
                val rightPatch = apply.argClause match {
                  case Term.ArgClause(List(nameTerm: Term.Name), _)
                      if isOpaqueOperatorOperand(
                        nameTerm,
                        chain,
                        inferredOpaqueNames
                      ) =>
                    List(
                      Patch.replaceTree(nameTerm, s"${nameTerm.value}.value")
                    )
                  case _ => Nil
                }
                leftPatch ++ rightPatch
              } else if (opIsPrimitiveArithmetic(op)) {
                arithmeticOpaqueUnwrapPatches(apply, chain, inferredOpaqueNames)
              } else List.empty[Patch]

          }.flatten
        }

        headerPatch + companionEqPatches.asPatch + paramPatches.asPatch + returnPatches.asPatch + returnBodyMapPatches.asPatch + valCollectionWrapPatches.asPatch + inferredValWrapPatches.asPatch + genericReturnTypeArgPatches.asPatch + localCallBoundaryPatchList.asPatch + kleisliInputTypePatchList.asPatch + kleisliLocalTypePatchList.asPatch + opaqueConstructorArithmeticPatchList.asPatch + commandCollectionUnwrapPatches.asPatch + opaqueToPrimitiveCollectionPatches.asPatch + unwrapUsagePatches.asPatch
      }
    }

    private def insertTypeDefs(tree: Tree, code: String): Patch = {
      val stats = tree match {
        case source: Source => source.stats
        case pkg: Pkg       => pkg.body.stats
        case _              => Nil
      }

      val imports = stats.collect { case imp: Import => imp }
      imports.lastOption match {
        case Some(lastImport) =>
          Patch.addRight(lastImport, "\n\n" + code.trim + "\n")
        case None =>
          stats.headOption match {
            case Some(firstStat) =>
              Patch.addLeft(firstStat, code.trim + "\n\n")
            case None =>
              Patch.addLeft(tree, code.trim + "\n\n")
          }
      }
    }

    private def ensureCompanionEqPatch(
        tree: Tree,
        typeName: String
    ): Option[Patch] =
      tree
        .collect {
          case obj: Defn.Object if obj.name.value == typeName =>
            obj
        }
        .headOption
        .flatMap { obj =>
          val hasEq = obj.templ.body.stats.exists(stat =>
            stat.syntax.contains(s"Eq[$typeName]") || stat.syntax.contains(
              s"cats.Eq[$typeName]"
            )
          )
          if (hasEq) None
          else
            obj.templ.body.stats.lastOption.map { lastStat =>
              Patch.addRight(
                lastStat,
                s"\n  given cats.Eq[$typeName] = cats.Eq.by(_.value)"
              )
            }
        }
  }

  def isDomainParameter(
      paramName: String,
      contextName: Option[String]
  ): Boolean = {
    val cleanName = paramName.replaceAll("^_+|_+$", "")
    if (GenericParamNames.contains(cleanName.toLowerCase)) false
    else if (cleanName.equalsIgnoreCase("id") && contextName.isDefined) true
    else DomainSuffixPattern.matches(cleanName)
  }

  def inferOpaqueTypeName(
      paramName: String,
      contextName: Option[String] = None
  ): String = {
    val cleanName = paramName.replaceAll("^_+|_+$", "")
    val lower = cleanName.toLowerCase

    if (lower == "id" && contextName.isDefined) {
      pascalCase(contextName.get) + "Id"
    } else if (lower.contains("branch") || lower.contains("refname")) {
      "BranchName"
    } else if (
      lower.contains("taskid") || lower.contains("tasknumber") || lower
        .contains("issuenumber") || lower.contains("parentid")
    ) {
      "TaskNumber"
    } else if (
      lower.contains("millis") || lower.contains("seconds") || lower
        .contains("timeout") || lower.contains("poll")
    ) {
      "DeadlineMillis"
    } else {
      pascalCase(cleanName)
    }
  }

  private def pascalCase(s: String): String = {
    if (s.isEmpty) ""
    else {
      val parts = s.split("(?<=[a-z])(?=[A-Z])|_|-").filter(_.nonEmpty)
      parts.map(p => p.substring(0, 1).toUpperCase + p.substring(1)).mkString
    }
  }

  def generateOpaqueTypeDef(
      opaqueName: String,
      primitiveType: String
  ): String = {
    s"""opaque type $opaqueName = $primitiveType
       |object $opaqueName:
       |  def apply(value: $primitiveType): $opaqueName = value.asInstanceOf[$opaqueName]
       |  extension (opaqueValue: $opaqueName) def value: $primitiveType = opaqueValue.asInstanceOf[$primitiveType]
       |  given cats.Eq[$opaqueName] = cats.Eq.by(_.value)""".stripMargin
  }

  final case class GenericMethodShape(
      name: String,
      typeParamNames: List[String],
      paramTypeParams: List[Set[String]],
      returnTypeParams: Set[String]
  )

  final case class LocalCallShape(
      name: String,
      params: List[LocalCallParam]
  )

  final case class LocalCallParam(
      name: String,
      opaqueChain: Option[PropagationChain],
      primitiveType: Option[String],
      repeated: Boolean
  )

  @nowarn("cat=deprecation")
  def findPropagationChains(tree: Tree): List[PropagationChain] = {
    val allDefMethods = tree.collect { case defn: Defn.Def => defn }
    val allDeclMethods = tree.collect { case decl: Decl.Def => decl }
    val allClasses = tree.collect { case cls: Defn.Class => cls }
    val opaqueAliases = existingOpaqueAliases(tree)

    val paramNodesDef = for {
      defn <- allDefMethods
      paramClause <- defn.paramClauseGroups.flatMap(_.paramClauses)
      param <- paramClause.values
      tpe <- param.decltpe
      primitiveTypeName = unwrapPrimitiveType(tpe, opaqueAliases)
      if primitiveTypeName.exists(SupportedPrimitives.contains)
      if isDomainParameter(param.name.value, Some(defn.name.value))
    } yield {
      ParameterNode(
        name = param.name.value,
        paramType = primitiveTypeName.get,
        paramTree = param,
        ownerDefName = Some(defn.name.value)
      )
    }

    val paramNodesDecl = for {
      decl <- allDeclMethods
      paramClause <- decl.paramClauseGroups.flatMap(_.paramClauses)
      param <- paramClause.values
      tpe <- param.decltpe
      primitiveTypeName = unwrapPrimitiveType(tpe, opaqueAliases)
      if primitiveTypeName.exists(SupportedPrimitives.contains)
      if isDomainParameter(param.name.value, Some(decl.name.value))
    } yield {
      ParameterNode(
        name = param.name.value,
        paramType = primitiveTypeName.get,
        paramTree = param,
        ownerDefName = Some(decl.name.value)
      )
    }

    val classParamNodes = for {
      cls <- allClasses
      paramClause <- cls.ctor.paramClauses
      param <- paramClause.values
      tpe <- param.decltpe
      primitiveTypeName = unwrapPrimitiveType(tpe, opaqueAliases)
      if primitiveTypeName.exists(SupportedPrimitives.contains)
      if isDomainParameter(param.name.value, Some(cls.name.value))
    } yield {
      ParameterNode(
        name = param.name.value,
        paramType = primitiveTypeName.get,
        paramTree = param,
        ownerDefName = Some(cls.name.value)
      )
    }

    val callSitePassings = tree.collect { case apply: Term.Apply =>
      apply.argClause.values.collect { case Term.Name(argName) =>
        argName
      } ++ apply.argClause.values.collect {
        case Term.Assign(Term.Name(argName), value)
            if isPrimitiveIntroducedValue(value) =>
          argName
      }
    }.flatten

    val combinedNodes = paramNodesDef ++ paramNodesDecl ++ classParamNodes

    val grouped = combinedNodes.groupBy { node =>
      val inferred = inferOpaqueTypeName(node.name, node.ownerDefName)
      (node.paramType, inferred)
    }

    grouped
      .flatMap { case ((primitiveType, typeName), nodes) =>
        val callPassingsCount =
          nodes.map(n => callSitePassings.count(_ == n.name)).sum
        val totalWeight = nodes.length + callPassingsCount

        if (totalWeight >= 2) {
          val matchingReturnDefs = allDefMethods.filter { defn =>
            defn.decltpe.exists(t =>
              unwrapPrimitiveType(t, opaqueAliases).contains(primitiveType)
            ) &&
            (nodes.exists(_.ownerDefName.contains(defn.name.value)) ||
              inferOpaqueTypeName(defn.name.value) == typeName)
          }

          Some(
            PropagationChain(
              typeName = typeName,
              primitiveType = primitiveType,
              nodes = nodes,
              returnTypeDefs = matchingReturnDefs
            )
          )
        } else None
      }
      .toList
      .sortBy(_.typeName)
  }

  @nowarn("cat=deprecation")
  def genericMethodShapes(tree: Tree): List[GenericMethodShape] =
    tree.collect { case defn: Defn.Def => defn }.flatMap { defn =>
      val typeParamNames = defn.tparams.map(_.name.value)
      val returnTypeParams =
        defn.decltpe
          .map(collectTypeParamNames(_, typeParamNames.toSet))
          .getOrElse(Set.empty)

      Option.when(typeParamNames.nonEmpty && returnTypeParams.nonEmpty) {
        GenericMethodShape(
          name = defn.name.value,
          typeParamNames = typeParamNames,
          paramTypeParams = defn.paramClauseGroups
            .flatMap(_.paramClauses)
            .flatMap(_.values)
            .map(param =>
              param.decltpe
                .map(collectTypeParamNames(_, typeParamNames.toSet))
                .getOrElse(Set.empty)
            ),
          returnTypeParams = returnTypeParams
        )
      }
    }

  private def genericReturnTypeArgumentPatches(
      tree: Tree,
      chains: List[PropagationChain]
  ): List[Patch] = {
    val shapes =
      genericMethodShapes(tree).map(shape => shape.name -> shape).toMap

    tree.collect { case Defn.Val(_, _, Some(declaredType), value) =>
      genericCallTypedByOpaqueArgument(value, shapes, chains)
        .flatMap { case (typeArg, chain) =>
          List(
            replacePrimitiveType(
              declaredType,
              chain.primitiveType,
              chain.typeName
            ),
            Patch.replaceTree(typeArg, chain.typeName)
          )
        }
    }.flatten
  }

  private def genericCallTypedByOpaqueArgument(
      term: Term,
      shapes: Map[String, GenericMethodShape],
      chains: List[PropagationChain]
  ): List[(Type, PropagationChain)] =
    term match {
      case Term.Apply.After_4_6_0(typedFun, argClause) =>
        explicitTypeApply(typedFun, shapes).toList.flatMap {
          case (shape, typeArgs) =>
            shape.paramTypeParams
              .zip(argClause.values)
              .flatMap { case (typeParams, arg) =>
                typeParams.toList.flatMap { typeParam =>
                  if (shape.returnTypeParams.contains(typeParam)) {
                    shape.typeParamNames.indexOf(typeParam) match {
                      case index if index >= 0 && index < typeArgs.length =>
                        chains.find(isOpaqueArgument(arg, _)).flatMap { chain =>
                          val typeArg = typeArgs(index)
                          Option.when(
                            containsPrimitive(typeArg, chain.primitiveType)
                          )(
                            typeArg -> chain
                          )
                        }
                      case _ => None
                    }
                  } else None
                }
              }
        }
      case _ => Nil
    }

  @nowarn("cat=deprecation")
  private def localCallBoundaryPatches(
      tree: Tree,
      chains: List[PropagationChain],
      shapeTree: Option[Tree] = None,
      inferredOpaqueNames: Set[String] = Set.empty
  ): List[Patch] = {
    val shapes = localCallShapes(shapeTree.getOrElse(tree), chains)
      .groupBy(_.name)
      .map { case (name, shapes) =>
        name -> shapes.distinctBy(shape =>
          shape.params.map(param =>
            (
              param.opaqueChain.map(_.typeName),
              param.primitiveType,
              param.repeated
            )
          )
        )
      }
      .toMap

    tree.collect { case apply: Term.Apply =>
      methodName(apply.fun).toList.flatMap {
        case name if isPrimitiveCommandHelperName(name) =>
          apply.argClause.values.flatMap(
            unwrapPrimitiveCommandArgument(_, chains, inferredOpaqueNames)
          )
        case name =>
          shapes.getOrElse(name, Nil).flatMap { shape =>
            alignCallParams(shape.params, apply.argClause.values)
              .flatMap {
                case (LocalCallParam(paramName, Some(chain), _, _), arg) =>
                  wrapCallArgument(
                    arg,
                    chain,
                    inferredOpaqueNames,
                    Some(paramName)
                  )
                case (LocalCallParam(_, None, Some(primitiveType), _), arg) =>
                  unwrapPrimitiveArgument(
                    arg,
                    chains,
                    inferredOpaqueNames,
                    Some(primitiveType)
                  )
                case _ => Nil
              }
          }
      }
    }.flatten
  }

  @nowarn("cat=deprecation")
  def localCallShapes(
      tree: Tree,
      chains: List[PropagationChain]
  ): List[LocalCallShape] = {
    val defShapes = tree.collect { case defn: Defn.Def =>
      LocalCallShape(
        name = defn.name.value,
        params = defn.paramClauseGroups
          .flatMap(_.paramClauses)
          .flatMap(_.values)
          .map(param => localCallParam(param.name.value, param.decltpe, chains))
          .toList
      )
    }

    val classShapes = tree.collect { case cls: Defn.Class =>
      LocalCallShape(
        name = cls.name.value,
        params = cls.ctor.paramClauses
          .flatMap(_.values)
          .map(param => localCallParam(param.name.value, param.decltpe, chains))
          .toList
      )
    }

    defShapes ++ classShapes
  }

  private def localCallParam(
      paramName: String,
      maybeType: Option[Type],
      chains: List[PropagationChain]
  ): LocalCallParam =
    LocalCallParam(
      name = paramName,
      opaqueChain =
        maybeType.flatMap(tpe => chainForParam(paramName, tpe, chains)),
      primitiveType = maybeType.flatMap(unwrapPrimitiveType(_)),
      repeated = maybeType.exists(isRepeatedType)
    )

  private def alignCallParams(
      params: List[LocalCallParam],
      args: List[Term]
  ): List[(LocalCallParam, Term)] =
    if (params.lastOption.exists(_.repeated)) {
      val fixed = params.dropRight(1)
      val repeated = params.last
      fixed.zip(args.take(fixed.length)) ++
        args.drop(fixed.length).map(repeated -> _)
    } else params.zip(args)

  private def chainForParam(
      paramName: String,
      tpe: Type,
      chains: List[PropagationChain]
  ): Option[PropagationChain] =
    chains.find { chain =>
      chain.nodes.exists(_.name == paramName) ||
      typeNamesContain(tpe, chain.typeName) ||
      (containsPrimitive(tpe, chain.primitiveType) &&
        termNameMatchesChain(paramName, chain))
    }

  private def wrapCallArgument(
      arg: Term,
      chain: PropagationChain,
      inferredOpaqueNames: Set[String] = Set.empty,
      expectedParamName: Option[String] = None
  ): List[Patch] =
    arg match {
      case Term.Assign(Term.Name(argName), value)
          if isChainTargetName(argName, chain) =>
        wrapCallArgument(
          value,
          chain,
          inferredOpaqueNames,
          Some(argName)
        )
      case Term.Assign(_, value) =>
        wrapCallArgument(value, chain, inferredOpaqueNames, expectedParamName)
      case mapCall @ Term.Apply.After_4_6_0(
            Term.Select(_, Term.Name("map")),
            Term.ArgClause(List(function), _)
          ) if argumentMatchesChain(mapCall, chain, expectedParamName) =>
        wrapMapResultPatches(mapCall, function, chain)
      case _
          if isAlreadyOpaqueArgument(arg, chain, inferredOpaqueNames) ||
            containsPlaceholder(arg) =>
        Nil
      case Term.Name("None") =>
        Nil
      case Term.Select(opaqueValue, Term.Name("value"))
          if isOpaqueUsage(opaqueValue, chain) =>
        List(Patch.replaceTree(arg, opaqueValue.syntax))
      case _
          if shouldWrapExpression(arg, chain.typeName) &&
            argumentMatchesChain(arg, chain, expectedParamName) =>
        List(
          Patch.replaceTree(
            arg,
            s"${chain.typeName}(${renderConstructorArgument(arg, chain)})"
          )
        )
      case _ => Nil
    }

  private def argumentMatchesChain(
      arg: Term,
      chain: PropagationChain,
      expectedParamName: Option[String]
  ): Boolean =
    arg match {
      case Lit.String(_) | _: Term.Interpolate =>
        chain.primitiveType == "String"
      case Lit.Int(_) =>
        chain.primitiveType == "Int" || chain.primitiveType == "Long"
      case Lit.Long(_) =>
        chain.primitiveType == "Long"
      case Lit.Double(_) =>
        chain.primitiveType == "Double"
      case Term.Name(name) =>
        termNameMatchesChain(name, chain)
      case select: Term.Select =>
        isOpaqueUsage(select, chain) || selectMatchesChain(select, chain)
      case Term.ApplyInfix(lhs, op, _, Term.ArgClause(List(rhs), _))
          if opIsPrimitiveArithmetic(op.value) =>
        argumentMatchesChain(lhs, chain, expectedParamName) ||
        argumentMatchesChain(rhs, chain, expectedParamName)
      case Term.Apply.After_4_6_0(Term.Name(name), _)
          if name == chain.typeName =>
        true
      case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("map")), _) =>
        expectedParamName.exists(isChainTargetName(_, chain))
      case _ => false
    }

  private def unwrapPrimitiveCommandArgument(
      arg: Term,
      chains: List[PropagationChain],
      inferredOpaqueNames: Set[String]
  ): List[Patch] =
    unwrapPrimitiveArgument(arg, chains, inferredOpaqueNames, None)

  private def unwrapPrimitiveArgument(
      arg: Term,
      chains: List[PropagationChain],
      inferredOpaqueNames: Set[String],
      expectedPrimitiveType: Option[String]
  ): List[Patch] =
    arg match {
      case Term.Assign(_, value) =>
        unwrapPrimitiveArgument(
          value,
          chains,
          inferredOpaqueNames,
          expectedPrimitiveType
        )
      case _ =>
        chains.collectFirst {
          case chain
              if expectedPrimitiveType.forall(_ == chain.primitiveType) &&
                isAlreadyOpaqueArgument(arg, chain, inferredOpaqueNames) =>
            Patch.replaceTree(arg, s"${arg.syntax}.value")
        }.toList
    }

  private def isPrimitiveCommandHelperName(name: String): Boolean =
    name == "call" ||
      name == "callOutput" ||
      name == "callOutputUnchecked"

  private def opIsPrimitiveArithmetic(op: String): Boolean =
    op == "/" || op == "*" || op == "+" || op == "-"

  private def wrapMapResultPatches(
      mapCall: Term.Apply,
      function: Term,
      chain: PropagationChain
  ): List[Patch] =
    function match {
      case Term.Function(_, body)
          if shouldWrapExpression(body, chain.typeName) =>
        List(
          Patch.replaceTree(
            body,
            s"${chain.typeName}(${renderConstructorArgument(body, chain)})"
          )
        )
      case Term.PartialFunction(cases) =>
        cases.flatMap { caseClause =>
          caseClause.body match {
            case body: Term if shouldWrapExpression(body, chain.typeName) =>
              List(
                Patch.replaceTree(
                  body,
                  s"${chain.typeName}(${renderConstructorArgument(body, chain)})"
                )
              )
            case _ => Nil
          }
        }
      case _ if shouldWrapExpression(mapCall, chain.typeName) =>
        List(
          Patch.replaceTree(
            mapCall,
            s"${chain.typeName}(${renderConstructorArgument(mapCall, chain)})"
          )
        )
      case _ => Nil
    }

  private def renderConstructorArgument(
      arg: Term,
      chain: PropagationChain
  ): String =
    arg match {
      case Term.ApplyInfix(lhs, op, _, Term.ArgClause(List(rhs), _))
          if opIsPrimitiveArithmetic(op.value) =>
        s"${renderPrimitiveArithmeticSide(lhs, chain)} ${op.value} ${renderPrimitiveArithmeticSide(rhs, chain)}"
      case _ => arg.syntax
    }

  private def renderPrimitiveArithmeticSide(
      term: Term,
      chain: PropagationChain
  ): String =
    term match {
      case name: Term.Name if isKnownOpaqueVariable(name, chain) =>
        s"${name.value}.value"
      case name: Term.Name if termNameMatchesChain(name.value, chain) =>
        s"${name.value}.value"
      case _ => term.syntax
    }

  private def arithmeticOpaqueUnwrapPatches(
      apply: Term.ApplyInfix,
      chain: PropagationChain,
      inferredOpaqueNames: Set[String]
  ): List[Patch] = {
    if (
      isDirectApplyArgument(apply) && !isDirectOpaqueConstructorArgument(
        apply,
        chain
      )
    )
      Nil
    else {
      val leftPatch = apply.lhs match {
        case name: Term.Name
            if isOpaqueOperatorOperand(name, chain, inferredOpaqueNames) =>
          List(Patch.replaceTree(name, s"${name.value}.value"))
        case _ => Nil
      }
      val rightPatch = apply.argClause match {
        case Term.ArgClause(List(name: Term.Name), _)
            if isOpaqueOperatorOperand(name, chain, inferredOpaqueNames) =>
          List(Patch.replaceTree(name, s"${name.value}.value"))
        case _ => Nil
      }
      leftPatch ++ rightPatch
    }
  }

  private def opaqueConstructorArithmeticPatches(
      tree: Tree,
      chains: List[PropagationChain],
      inferredOpaqueNames: Set[String]
  ): List[Patch] =
    tree.collect {
      case Term.Apply.After_4_6_0(
            Term.Name(typeName),
            Term.ArgClause(List(infix: Term.ApplyInfix), _)
          ) =>
        chains.find(_.typeName == typeName).toList.flatMap { chain =>
          arithmeticOpaqueUnwrapPatches(infix, chain, inferredOpaqueNames)
        }
    }.flatten

  private def isOpaqueOperatorOperand(
      term: Term,
      chain: PropagationChain,
      inferredOpaqueNames: Set[String]
  ): Boolean =
    isAlreadyOpaqueArgument(term, chain, inferredOpaqueNames) ||
      (term match {
        case Term.Name(name) => termNameMatchesChain(name, chain)
        case _               => false
      })

  private def isInsideOpaqueConstructor(
      tree: Tree,
      chain: PropagationChain
  ): Boolean =
    tree.parent.exists {
      case Term.Apply.After_4_6_0(Term.Name(name), _)
          if name == chain.typeName =>
        true
      case parent => isInsideOpaqueConstructor(parent, chain)
    }

  private def isDirectApplyArgument(tree: Tree): Boolean =
    tree.parent.exists {
      case Term.ArgClause(values, _) => values.exists(value => value eq tree)
      case _                         => false
    }

  private def isDirectOpaqueConstructorArgument(
      tree: Tree,
      chain: PropagationChain
  ): Boolean =
    tree.parent.exists {
      case Term.ArgClause(values, _) if values.exists(value => value eq tree) =>
        tree.parent.flatMap(_.parent).exists {
          case Term.Apply.After_4_6_0(Term.Name(name), _) =>
            name == chain.typeName
          case _ => false
        }
      case _ => false
    }

  @nowarn("cat=deprecation")
  private def kleisliInputTypePatches(
      tree: Tree,
      chains: List[PropagationChain]
  ): List[Patch] =
    tree
      .collect { case defn: Defn.Def =>
        for {
          declaredType <- defn.decltpe.toList
          inputType <- kleisliInputType(declaredType).toList
          names = kleisliInputPatternNames(defn.body, inputTypeArity(inputType))
          if names.nonEmpty
          patches <- patchTupleTypeByNames(inputType, names, chains)
        } yield patches
      }
      .flatten
      .flatten

  @nowarn("cat=deprecation")
  private def kleisliLocalTypePatches(
      tree: Tree,
      chains: List[PropagationChain]
  ): List[Patch] =
    tree.collect {
      case Term.Apply.After_4_6_0(
            Term.ApplyType.After_4_6_0(
              Term.Select(_, Term.Name("local")),
              typeArgClause
            ),
            argClause
          ) =>
        typeArgClause.values.headOption.toList.flatMap { inputType =>
          val names = localInputPatternNames(
            argClause.values.headOption,
            inputTypeArity(inputType)
          )
          patchTupleTypeByNames(inputType, names, chains).flatten ++
            typedPatternTypePatches(argClause.values.headOption, chains)
        }
    }.flatten

  private def localInputPatternNames(
      maybeTerm: Option[Term],
      arity: Int
  ): List[String] =
    maybeTerm
      .map(_.collect {
        case term: Term.Function =>
          term.params.map(_.name.value)
        case Term.PartialFunction(cases) =>
          cases.flatMap(caseClause => patternNames(caseClause.pat))
      })
      .flatMap(
        _.filter(names => arity <= 0 || names.length == arity)
          .sortBy(names => -names.count(_.nonEmpty))
          .headOption
      )
      .getOrElse(Nil)

  private def typedPatternTypePatches(
      maybeTerm: Option[Term],
      chains: List[PropagationChain]
  ): List[Patch] =
    maybeTerm.toList.flatMap { term =>
      term.collect {
        case Pat.Typed(Pat.Var(Term.Name(name)), tpe) =>
          chains.collectFirst {
            case chain
                if termNameMatchesChain(name, chain) &&
                  containsPrimitive(tpe, chain.primitiveType) =>
              replacePrimitiveType(tpe, chain.primitiveType, chain.typeName)
          }
        case Pat.Typed(Pat.Wildcard(), tpe) =>
          chainForUnnamedNestedType(tpe, chains).map { chain =>
            replacePrimitiveType(tpe, chain.primitiveType, chain.typeName)
          }
      }.flatten
    }

  private def kleisliInputType(tpe: Type): Option[Type] =
    tpe match {
      case Type.Apply.After_4_6_0(Type.Name("Kleisli"), argClause)
          if argClause.values.length >= 2 =>
        Some(argClause.values(1))
      case Type.Apply
            .After_4_6_0(Type.Select(_, Type.Name("Kleisli")), argClause)
          if argClause.values.length >= 2 =>
        Some(argClause.values(1))
      case _ => None
    }

  @nowarn("cat=deprecation")
  private def kleisliInputPatternNames(
      body: Term,
      arity: Int
  ): List[String] =
    body
      .collect {
        case Term.Apply.After_4_6_0(
              Term.Select(Term.Name("Kleisli"), Term.Name("apply")),
              Term.ArgClause(List(Term.PartialFunction(List(caseClause))), _)
            ) =>
          patternNames(caseClause.pat)
      }
      .filter(names => arity <= 0 || names.length == arity)
      .sortBy(names => -names.count(_.nonEmpty))
      .headOption
      .getOrElse(Nil)

  private def inputTypeArity(inputType: Type): Int =
    inputType match {
      case Type.Tuple(args) => args.length
      case _                => 1
    }

  private def patternNames(pattern: Pat): List[String] =
    pattern match {
      case Pat.Tuple(args)          => args.flatMap(patternNames)
      case Pat.Var(Term.Name(name)) => List(name)
      case Pat.Typed(pat, _)        => patternNames(pat)
      case Pat.Bind(_, pat)         => patternNames(pat)
      case Pat.Wildcard()           => List("")
      case _                        => Nil
    }

  private def patchTupleTypeByNames(
      inputType: Type,
      names: List[String],
      chains: List[PropagationChain]
  ): List[List[Patch]] =
    inputType match {
      case Type.Tuple(args) =>
        List(
          args.zip(names).flatMap { case (argType, name) =>
            chainForNameAndType(name, argType, chains).toList.flatMap { chain =>
              replacePrimitiveType(
                argType,
                chain.primitiveType,
                chain.typeName
              ) :: Nil
            }
          }
        )
      case single if names.length == 1 =>
        List(
          chainForNameAndType(names.head, single, chains).toList.map { chain =>
            replacePrimitiveType(single, chain.primitiveType, chain.typeName)
          }
        )
      case _ => Nil
    }

  private def chainForNameAndType(
      name: String,
      tpe: Type,
      chains: List[PropagationChain]
  ): Option[PropagationChain] = {
    val named = chains.find(chain =>
      name.nonEmpty &&
        termNameMatchesChain(name, chain) &&
        containsPrimitive(tpe, chain.primitiveType)
    )
    named.orElse {
      Option
        .when(name.isEmpty)(())
        .flatMap(_ => chainForUnnamedNestedType(tpe, chains))
    }
  }

  private def chainForUnnamedNestedType(
      tpe: Type,
      chains: List[PropagationChain]
  ): Option[PropagationChain] = {
    val matchingChains = chains.filter(chain =>
      isNestedTypeConstructor(tpe) &&
        containsPrimitive(tpe, chain.primitiveType)
    )
    Option.when(matchingChains.length == 1)(matchingChains.head)
  }

  private def explicitTypeApply(
      term: Term,
      shapes: Map[String, GenericMethodShape]
  ): Option[(GenericMethodShape, List[Type])] =
    term match {
      case Term.ApplyType.After_4_6_0(fun, typeArgClause) =>
        methodName(fun).flatMap(shapes.get).map(_ -> typeArgClause.values)
      case _ => None
    }

  private def methodName(term: Term): Option[String] =
    term match {
      case Term.Name(name)                 => Some(name)
      case Term.Select(_, Term.Name(name)) => Some(name)
      case Term.ApplyType.After_4_6_0(fun, _) =>
        methodName(fun)
      case _ => None
    }

  private def isOpaqueArgument(
      term: Term,
      chain: PropagationChain
  ): Boolean =
    term match {
      case Term.Name(name) =>
        termNameMatchesChain(name, chain)
      case Term.Select(_, Term.Name(name)) =>
        termNameMatchesChain(name, chain)
      case Term.Apply.After_4_6_0(Term.Name(name), _)
          if name == chain.typeName =>
        true
      case _ => false
    }

  private def isAlreadyOpaqueArgument(
      term: Term,
      chain: PropagationChain,
      inferredOpaqueNames: Set[String] = Set.empty
  ): Boolean =
    term match {
      case Term.Name(name) =>
        chain.nodes.exists(_.name == name) ||
        (inferredOpaqueNames
          .contains(name) && termNameMatchesChain(name, chain))
      case Term.Select(_, Term.Name(name)) =>
        chain.nodes.exists(_.name == name)
      case Term.Apply.After_4_6_0(Term.Name(name), _)
          if name == chain.typeName =>
        true
      case _ => false
    }

  private def isKnownOpaqueVariable(
      term: Term,
      chain: PropagationChain
  ): Boolean =
    term match {
      case Term.Name(name) =>
        chain.nodes.exists(_.name == name)
      case Term.Select(_, Term.Name(name)) =>
        chain.nodes.exists(_.name == name)
      case Term.Apply.After_4_6_0(Term.Name(name), _)
          if name == chain.typeName =>
        true
      case _ => false
    }

  private def collectTypeParamNames(
      tpe: Type,
      typeParamNames: Set[String]
  ): Set[String] =
    tpe match {
      case Type.Name(name) if typeParamNames.contains(name) => Set(name)
      case Type.Repeated(tpe) =>
        collectTypeParamNames(tpe, typeParamNames)
      case Type.Apply.After_4_6_0(_, argClause) =>
        argClause.values.flatMap(collectTypeParamNames(_, typeParamNames)).toSet
      case Type.Tuple(args) =>
        args.flatMap(collectTypeParamNames(_, typeParamNames)).toSet
      case _ => Set.empty
    }

  @nowarn("cat=deprecation")
  private def existingOpaqueAliases(tree: Tree): Map[String, String] =
    tree
      .collect {
        case defn: Defn.Type if defn.mods.exists(_.syntax == "opaque") =>
          unwrapPrimitiveType(defn.body).map(defn.name.value -> _)
      }
      .flatten
      .toMap

  @nowarn("cat=deprecation")
  private def unwrapPrimitiveType(
      tpe: Type,
      opaqueAliases: Map[String, String] = Map.empty
  ): Option[String] = tpe match {
    case Type.Name(name) if SupportedPrimitives.contains(name) => Some(name)
    case Type.Name(name)    => opaqueAliases.get(name)
    case Type.Repeated(tpe) => unwrapPrimitiveType(tpe, opaqueAliases)
    case Type.Apply.After_4_6_0(_, argClause) =>
      argClause.values.iterator
        .flatMap(arg => unwrapPrimitiveType(arg, opaqueAliases))
        .toSeq
        .headOption
    case Type.Tuple(args) =>
      args.iterator
        .flatMap(arg => unwrapPrimitiveType(arg, opaqueAliases))
        .toSeq
        .headOption
    case _ => None
  }

  private def replacePrimitiveType(
      tpe: Type,
      primitiveType: String,
      typeName: String
  ): Patch =
    tpe match {
      case Type.Name(name) if name == primitiveType =>
        Patch.replaceTree(tpe, typeName)
      case Type.Repeated(inner) =>
        replacePrimitiveType(inner, primitiveType, typeName)
      case Type.Apply.After_4_6_0(_, argClause) =>
        argClause.values
          .map(replacePrimitiveType(_, primitiveType, typeName))
          .asPatch
      case Type.Tuple(args) =>
        args.map(replacePrimitiveType(_, primitiveType, typeName)).asPatch
      case _ => Patch.empty
    }

  private def containsPrimitiveInMappableResult(
      tpe: Type,
      primitiveType: String
  ): Boolean =
    tpe match {
      case Type.Name(_) => false
      case Type.Apply.After_4_6_0(_, argClause) =>
        argClause.values.lastOption.exists {
          case Type.Name(name) => name == primitiveType
          case nested          => containsPrimitive(nested, primitiveType)
        }
      case _ => false
    }

  private def containsPrimitive(
      tpe: Type,
      primitiveType: String
  ): Boolean =
    tpe match {
      case Type.Name(name) => name == primitiveType
      case Type.Repeated(tpe) =>
        containsPrimitive(tpe, primitiveType)
      case Type.Apply.After_4_6_0(_, argClause) =>
        argClause.values.exists(containsPrimitive(_, primitiveType))
      case Type.Tuple(args) => args.exists(containsPrimitive(_, primitiveType))
      case _                => false
    }

  private def typeNamesContain(
      tpe: Type,
      typeName: String
  ): Boolean =
    tpe match {
      case Type.Name(name) => name == typeName
      case Type.Repeated(tpe) =>
        typeNamesContain(tpe, typeName)
      case Type.Apply.After_4_6_0(_, argClause) =>
        argClause.values.exists(typeNamesContain(_, typeName))
      case Type.Tuple(args) => args.exists(typeNamesContain(_, typeName))
      case _                => false
    }

  private def isNestedTypeConstructor(tpe: Type): Boolean =
    tpe match {
      case Type.Apply.After_4_6_0(_, _) => true
      case Type.Tuple(_)                => true
      case _                            => false
    }

  private def isRepeatedType(tpe: Type): Boolean =
    tpe match {
      case Type.Repeated(_) => true
      case _                => false
    }

  private def containsOpaqueConstructor(term: Term, typeName: String): Boolean =
    term.collect {
      case Term.Apply.After_4_6_0(Term.Name(wrapper), _)
          if wrapper == typeName =>
        wrapper
    }.nonEmpty

  private def equalityBoundaryPatches(
      apply: Term.ApplyInfix,
      chain: PropagationChain
  ): List[Patch] = {
    val rhs = apply.argClause match {
      case Term.ArgClause(List(value), _) => Some(value)
      case _                              => None
    }

    rhs.toList.flatMap { right =>
      if (isOpaqueUsage(apply.lhs, chain))
        wrapEqualitySide(right, chain)
      else if (containsPlaceholder(apply.lhs) && isOpaqueUsage(right, chain))
        List(Patch.replaceTree(right, s"${right.syntax}.value"))
      else if (
        rhs.exists(containsPlaceholder) && isOpaqueUsage(apply.lhs, chain)
      )
        List(Patch.replaceTree(apply.lhs, s"${apply.lhs.syntax}.value"))
      else if (isOpaqueUsage(right, chain))
        wrapEqualitySide(apply.lhs, chain)
      else Nil
    }
  }

  private def wrapEqualitySide(
      term: Term,
      chain: PropagationChain
  ): List[Patch] =
    if (shouldWrapBoundaryValue(term, chain.typeName))
      List(Patch.replaceTree(term, s"${chain.typeName}(${term.syntax})"))
    else Nil

  private def isOpaqueUsage(
      term: Term,
      chain: PropagationChain
  ): Boolean =
    term match {
      case Term.Name(name) => termNameMatchesChain(name, chain)
      case Term.Select(_, Term.Name(name)) =>
        termNameMatchesChain(name, chain)
      case _ => false
    }

  private def selectMatchesChain(
      select: Term.Select,
      chain: PropagationChain
  ): Boolean = {
    val receiverPrefix = domainTermName(select.qual)
    val selected = select.name.value
    receiverPrefix.exists(prefix =>
      termNameMatchesChain(prefix + pascalCase(selected), chain)
    )
  }

  private def domainTermName(term: Term): Option[String] =
    term match {
      case Term.Name(name) => Some(name)
      case Term.Select(_, Term.Name(name)) =>
        Some(name)
      case _ => None
    }

  private def termNameMatchesChain(
      name: String,
      chain: PropagationChain
  ): Boolean =
    chain.nodes.exists(_.name == name) ||
      (isDomainParameter(name, None) && inferOpaqueTypeName(
        name
      ) == chain.typeName)

  private def isChainTargetName(
      name: String,
      chain: PropagationChain
  ): Boolean =
    chain.nodes.exists(node =>
      name == node.name || name == s"${node.name}s"
    ) || termNameMatchesChain(name, chain)

  private def wrapIntroducedValuePatch(
      value: Term,
      chain: PropagationChain
  ): Patch =
    collectionElementWrapPatches(value, chain) match {
      case patches if patches.nonEmpty => patches.asPatch
      case _ if shouldWrapIntroducedValue(value, chain.typeName) =>
        Patch.replaceTree(value, s"${chain.typeName}(${value.syntax})")
      case _ => Patch.empty
    }

  private def wrapBoundaryExpressionPatch(
      value: Term,
      chain: PropagationChain
  ): Patch =
    collectionElementWrapPatches(value, chain) match {
      case patches if patches.nonEmpty => patches.asPatch
      case _ if shouldWrapExpression(value, chain.typeName) =>
        Patch.replaceTree(
          value,
          s"${chain.typeName}(${renderConstructorArgument(value, chain)})"
        )
      case _ => Patch.empty
    }

  private def inferredOpaqueValueNames(
      tree: Tree,
      chains: List[PropagationChain]
  ): List[String] =
    chains.flatMap { chain =>
      tree.collect {
        case Defn.Val(_, List(Pat.Var(Term.Name(name))), None, value)
            if shouldWrapInferredValue(name, value, chain) =>
          name
      }
    }

  private def shouldWrapInferredValue(
      name: String,
      value: Term,
      chain: PropagationChain
  ): Boolean =
    isChainTargetName(name, chain) &&
      !isBooleanLikeName(name) &&
      (collectionElementWrapPatches(value, chain).nonEmpty ||
        isPrimitiveBoundaryExpression(value))

  private def isBooleanLikeName(name: String): Boolean = {
    val lower = name.toLowerCase
    lower.startsWith("has") ||
    lower.startsWith("is") ||
    lower.startsWith("should") ||
    lower.startsWith("switches")
  }

  private def isPrimitiveBoundaryExpression(value: Term): Boolean =
    value match {
      case _: Lit.String | _: Lit.Int | _: Lit.Long | _: Lit.Double |
          _: Term.Interpolate =>
        true
      case _: Term.Name | _: Term.Select => true
      case Term.Block(stats) =>
        stats.lastOption.exists {
          case term: Term => isPrimitiveBoundaryExpression(term)
          case _          => false
        }
      case Term.If(_, thenp, elsep) =>
        isPrimitiveBoundaryExpression(thenp) &&
        isPrimitiveBoundaryExpression(elsep)
      case Term.ApplyInfix(_, op, _, _) if opIsPrimitiveArithmetic(op.value) =>
        true
      case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("getOrElse")), _) =>
        true
      case Term.Select(_, Term.Name("toMillis" | "toSeconds")) => true
      case Term.Apply.After_4_6_0(
            Term.Select(_, Term.Name("toMillis" | "toSeconds")),
            _
          ) =>
        true
      case _ => false
    }

  private def collectionElementWrapPatches(
      value: Term,
      chain: PropagationChain
  ): List[Patch] =
    value match {
      case Term.Apply.After_4_6_0(
            Term.Name("Seq" | "List" | "Vector" | "Set"),
            argClause
          ) =>
        argClause.values.flatMap(wrapCollectionElement(_, chain)).toList
      case _ => Nil
    }

  private def wrapCollectionElement(
      value: Term,
      chain: PropagationChain
  ): List[Patch] =
    value match {
      case Term.Select(opaqueValue, Term.Name("value"))
          if isOpaqueUsage(opaqueValue, chain) =>
        List(Patch.replaceTree(value, opaqueValue.syntax))
      case _ if shouldWrapIntroducedValue(value, chain.typeName) =>
        List(Patch.replaceTree(value, s"${chain.typeName}(${value.syntax})"))
      case _ => Nil
    }

  private def unwrapCollectionOpaqueElementPatches(
      collection: Term.Apply,
      chain: PropagationChain
  ): List[Patch] =
    collection.fun match {
      case Term.Name("Seq" | "List" | "Vector" | "Set") =>
        collection.argClause.values.collect {
          case value: Term if isOpaqueUsage(value, chain) =>
            Patch.replaceTree(value, s"${value.syntax}.value")
        }.toList
      case _ => Nil
    }

  private def opaqueToPrimitiveCollectionPatchesFor(
      tree: Tree,
      chains: List[PropagationChain]
  ): List[Patch] =
    tree.collect {
      case select @ Term.Select(receiver, Term.Name("toList"))
          if isInsideCommandConcat(select) =>
        chains.collectFirst {
          case chain if isOpaqueUsage(receiver, chain) =>
            Patch.replaceTree(select, s"${select.syntax}.map(_.value)")
        }
    }.flatten

  private def isInsideCommandConcat(tree: Tree): Boolean =
    tree.parent.exists {
      case repeated: Term.Repeated => isInsideCommandConcat(repeated)
      case infix: Term.ApplyInfix if infix.op.value == "++" =>
        infix.lhs.collect {
          case apply: Term.Apply if isCommandLikeCollection(apply) => ()
        }.nonEmpty || isInsideCommandConcat(infix)
      case apply: Term.Apply if isCommandLikeCollection(apply) => true
      case other => isInsideCommandConcat(other)
    }

  private def isCommandLikeCollection(collection: Term.Apply): Boolean =
    collection.fun match {
      case Term.Name("Seq" | "List" | "Vector" | "Set") =>
        val stringValues = collection.argClause.values.collect {
          case Lit.String(value) => value
        }
        stringValues.exists(value =>
          value == "gh" || value == "git" || value.startsWith("--")
        )
      case _ => false
    }

  private def optionPredicateEqualityPatches(
      apply: Term.Apply,
      chain: PropagationChain
  ): List[Patch] =
    apply match {
      case Term.Apply.After_4_6_0(
            Term.Select(receiver, Term.Name("forall" | "exists")),
            Term.ArgClause(List(predicate), _)
          ) if isOpaqueUsage(receiver, chain) =>
        predicate.collect {
          case infix: Term.ApplyInfix
              if infix.op.value == "==" || infix.op.value == "!=" ||
                infix.op.value == "===" || infix.op.value == "!==" =>
            val rhs = infix.argClause match {
              case Term.ArgClause(List(value), _) => Some(value)
              case _                              => None
            }
            val lhsPlaceholder = infix.lhs.is[Term.Placeholder]
            val rhsPlaceholder = rhs.exists(_.is[Term.Placeholder])

            if (lhsPlaceholder) rhs.toList.flatMap(wrapEqualitySide(_, chain))
            else if (rhsPlaceholder) wrapEqualitySide(infix.lhs, chain)
            else Nil
        }.flatten
      case _ => Nil
    }

  private def getOrElseDefaultWrapPatches(
      apply: Term.Apply,
      chain: PropagationChain
  ): List[Patch] =
    apply match {
      case Term.Apply.After_4_6_0(
            Term.Select(receiver, Term.Name("getOrElse")),
            Term.ArgClause(List(defaultValue), _)
          )
          if isOpaqueUsage(receiver, chain) &&
            shouldWrapExpression(defaultValue, chain.typeName) =>
        List(
          Patch.replaceTree(
            apply,
            s"${receiver.syntax}.getOrElse(${chain.typeName}(${renderConstructorArgument(defaultValue, chain)}))"
          )
        )
      case _ => Nil
    }

  private def isPrimitiveIntroducedValue(value: Term): Boolean =
    value match {
      case _: Lit.String | _: Lit.Int | _: Lit.Long | _: Lit.Double => true
      case _: Term.Name                                             => true
      case _                                                        => false
    }

  private def shouldWrapIntroducedValue(
      value: Term,
      typeName: String
  ): Boolean =
    isPrimitiveIntroducedValue(value) && !isWrappedWith(value, typeName)

  private def shouldWrapBoundaryValue(
      value: Term,
      typeName: String
  ): Boolean =
    (isPrimitiveIntroducedValue(value) || value.is[Term.Select]) &&
      !isWrappedWith(value, typeName) &&
      !containsPlaceholder(value)

  private def isWrappedWith(value: Term, typeName: String): Boolean =
    value match {
      case Term.Apply.After_4_6_0(Term.Name(wrapper), _)
          if wrapper == typeName =>
        true
      case _ => false
    }

  private def shouldWrapExpression(
      value: Term,
      typeName: String
  ): Boolean =
    !isWrappedWith(value, typeName) &&
      !containsPlaceholder(value) &&
      value.syntax != "None"

  private def containsPlaceholder(value: Tree): Boolean =
    value.collect { case _: Term.Placeholder => () }.nonEmpty

  private def getFilePath(docInput: Input): Option[java.nio.file.Path] = {
    docInput match {
      case Input.File(path, _) => Some(path.toNIO.toAbsolutePath.normalize)
      case Input.VirtualFile(path, _) =>
        try Some(java.nio.file.Paths.get(path).toAbsolutePath.normalize)
        catch { case _: Exception => None }
      case Input.Slice(input, _, _) => getFilePath(input)
      case _                        => None
    }
  }

  private def hasChainForTypeName(
      fileText: String,
      typeName: String
  ): Boolean = {
    typeName match {
      case "BranchName" =>
        fileText.contains("branchName:") || fileText.contains(
          "baseBranch:"
        ) || fileText.contains("headBranch:") || fileText.contains(
          "refName:"
        ) || fileText.contains("refname:")
      case "TaskNumber" =>
        fileText.contains("taskNumber:") || fileText.contains(
          "taskId:"
        ) || fileText.contains("issueNumber:") || fileText.contains("parentId:")
      case "AgentToolId" =>
        fileText.contains("AgentTool") && fileText.contains("id:")
      case "DeadlineMillis" =>
        fileText.contains("deadlineMillis:") || fileText.contains(
          "timeoutMillis:"
        ) || fileText.contains("pollMillis:")
      case other =>
        val lower = other.toLowerCase
        fileText.toLowerCase.contains(lower)
    }
  }

  private def isFirstFileForTypeName(
      chain: PropagationChain,
      docInput: Input
  ): Boolean = {
    getFilePath(docInput) match {
      case Some(rawPath) =>
        val absPath = rawPath.toAbsolutePath.normalize
        val parent = absPath.getParent
        if (parent == null) true
        else {
          import java.nio.file.Files
          import scala.jdk.CollectionConverters._
          try {
            val stream = Files.list(parent)
            val filesWithChain =
              try {
                stream
                  .iterator()
                  .asScala
                  .map(_.toAbsolutePath.normalize)
                  .filter(_.getFileName.toString.endsWith(".scala"))
                  .filter { file =>
                    val text = Files.readString(file)
                    hasChainForTypeName(text, chain.typeName)
                  }
                  .toList
                  .sortBy(_.getFileName.toString)
              } finally {
                stream.close()
              }

            filesWithChain.headOption.contains(absPath)
          } catch {
            case _: Exception => true
          }
        }
      case None => true
    }
  }

  def rewritePlan(
      tree: Tree,
      chains: List[PropagationChain],
      docInput: Option[Input] = None,
      contextChains: List[PropagationChain] = Nil,
      contextTree: Option[Tree] = None
  ): RewritePlan = {
    val resolvedContextChains =
      if (contextChains.nonEmpty) contextChains else chains
    val resolvedContextTree = contextTree.getOrElse(tree)
    val existingInTree = tree.collect {
      case defn: Defn.Type if defn.name.value != "" => defn.name.value
      case cls: Defn.Class                          => cls.name.value
      case traitDef: Defn.Trait                     => traitDef.name.value
      case obj: Defn.Object                         => obj.name.value
    }.toSet

    val newChainsForTypeDefs = chains.filter { c =>
      !existingInTree.contains(c.typeName) &&
      docInput.forall(input => isFirstFileForTypeName(c, input))
    }

    val opaqueDefs =
      newChainsForTypeDefs.map(c =>
        generateOpaqueTypeDef(c.typeName, c.primitiveType)
      )

    RewritePlan(
      chains = chains,
      opaqueTypeDefinitions = opaqueDefs,
      contextChains = resolvedContextChains,
      contextTree = resolvedContextTree
    )
  }

  def contextTree(
      currentTree: Tree,
      docInput: Input
  ): Tree =
    getFilePath(docInput) match {
      case Some(path) =>
        val parent = path.getParent
        if (parent == null) currentTree
        else {
          import java.nio.file.Files
          import scala.jdk.CollectionConverters._
          try {
            val stream = Files.list(parent)
            val parsed =
              try {
                stream
                  .iterator()
                  .asScala
                  .filter(file =>
                    file.getFileName.toString.endsWith(".scala") &&
                      file.toAbsolutePath.normalize != path
                  )
                  .flatMap { file =>
                    try {
                      dialects
                        .Scala3(Files.readString(file))
                        .parse[Source]
                        .toOption
                    } catch {
                      case _: Exception => None
                    }
                  }
                  .toList
              } finally {
                stream.close()
              }
            Source(topLevelStats(currentTree) ++ parsed.flatMap(topLevelStats))
          } catch {
            case _: Exception => currentTree
          }
        }
      case None => currentTree
    }

  private def topLevelStats(tree: Tree): List[Stat] =
    tree match {
      case source: Source => source.stats
      case pkg: Pkg       => pkg.body.stats
      case stat: Stat     => List(stat)
      case _              => Nil
    }
}
