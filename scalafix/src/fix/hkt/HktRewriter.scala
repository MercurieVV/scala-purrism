package fix.hkt

import scala.meta._

import scalafix.v1.Patch
import scalafix.v1.SemanticDocument
import scalafix.v1.Symbol
import scalafix.v1.XtensionTreeScalafix

object HktRewriter {
  def rewrite(
      usage: UsageResult.Abstractable,
      solution: CapabilitySolver.Solution,
      index: CatsIndex,
      typeParamName: String
  )(implicit doc: SemanticDocument): Patch = {
    val kind = solution.constraints.headOption
      .flatMap(index.typeclasses.get)
      .map(_.kind)
      .getOrElse(kindOf(usage.target))

    kind match {
      case KindShape.Binary => Patch.empty
      case _ =>
        val reusable = reusableTypeParam(usage, solution, index, kind)
        val selectedName = reusable.map(_.name.value).getOrElse(typeParamName)
        val replacementPatch = replaceSignatureTypes(usage, selectedName, kind)
        val syntaxImports =
          usage.ops.flatMap(op => index.syntaxImport(op.method))
        val imports =
          if (reusable.nonEmpty) syntaxImports
          else requiredImports(solution, index) ++ syntaxImports
        val importPatch = addMissingImports(usage.defn, imports.distinct.sorted)

        reusable match {
          case Some(_) => replacementPatch + importPatch
          case None =>
            val renderedExtras = renderedExtraArguments(usage, solution)
            val pinnedExtras = renderedExtras != solution.extraTypeParams
            val extraDeclarations =
              if (pinnedExtras) Nil else solution.extraTypeParams
            val usingStyle =
              solution.extraTypeParams.nonEmpty || sourceUsesUsingClauses
            val typeParamPatch = addTypeParameters(
              usage.defn,
              renderTypeParameter(selectedName, kind),
              extraDeclarations,
              if (usingStyle) Nil
              else
                renderConstraints(selectedName, renderedExtras, solution, index)
            )
            val constraintPatch =
              if (usingStyle)
                addUsingConstraints(
                  usage.defn,
                  selectedName,
                  renderConstraints(
                    selectedName,
                    renderedExtras,
                    solution,
                    index
                  )
                )
              else Patch.empty

            typeParamPatch + constraintPatch + replacementPatch + importPatch
        }
    }
  }

  def freshTypeParamName(
      defn: Defn.Def,
      preferred: List[String]
  ): Option[String] = {
    val taken = (defn :: enclosingTrees(defn))
      .flatMap(typeParameters)
      .map(_.name.value)
      .toSet
    preferred.find(!taken(_))
  }

  def requiredImports(
      solution: CapabilitySolver.Solution,
      index: CatsIndex
  ): List[String] =
    solution.constraints
      .flatMap(index.typeclasses.get)
      .map(_.importPath)
      .distinct
      .sorted

  private final case class ExistingConstraint(
      symbol: Symbol,
      extraArguments: List[Type]
  )

  private def reusableTypeParam(
      usage: UsageResult.Abstractable,
      solution: CapabilitySolver.Solution,
      index: CatsIndex,
      kind: KindShape
  )(implicit doc: SemanticDocument): Option[Type.Param] = {
    val owners = usage.defn :: enclosingTrees(usage.defn)
    val evidence = owners.flatMap(usingEvidence)
    val expectedExtras = renderedExtraArguments(usage, solution)

    owners
      .flatMap(typeParameters)
      .find { candidate =>
        typeParameterKind(candidate) == kind && {
          val constraints =
            contextBoundConstraints(candidate) ++ evidence.flatMap {
              case (parameter, constraint)
                  if sameTypeParameter(parameter, candidate) =>
                List(constraint)
              case _ => Nil
            }

          solution.constraints.forall { required =>
            constraints.exists { existing =>
              (existing.symbol == required || index.isAncestor(
                required,
                existing.symbol
              )) &&
              extraArgumentsMatch(existing, required, expectedExtras, index)
            }
          }
        }
      }
  }

  private def addTypeParameters(
      defn: Defn.Def,
      primary: String,
      extras: List[String],
      contextBounds: List[String]
  ): Patch = {
    val renderedPrimary =
      if (contextBounds.isEmpty) primary
      else s"$primary: ${contextBounds.mkString(": ")}"
    val additions = renderedPrimary :: extras
    val existing = defTypeParameters(defn)

    existing.lastOption match {
      case Some(last) =>
        Patch.addRight(last, additions.mkString(", ", ", ", ""))
      case None => Patch.addRight(defn.name, additions.mkString("[", ", ", "]"))
    }
  }

  private def addUsingConstraints(
      defn: Defn.Def,
      typeParamName: String,
      constraints: List[String]
  ): Patch = {
    val parameters = constraints.zipWithIndex.map { case (constraint, index) =>
      val name =
        if (index == 0) typeParamName else s"$typeParamName${index + 1}"
      s"$name: $constraint"
    }
    val clauses = defn.paramClauseGroups.flatMap(_.paramClauses)
    val existingUsing = clauses.reverse.find(isUsingClause)

    existingUsing.flatMap(_.values.lastOption) match {
      case Some(last) =>
        Patch.addRight(last, parameters.mkString(", ", ", ", ""))
      case None =>
        clauses.lastOption match {
          case Some(last) =>
            Patch.addRight(last, parameters.mkString("(using ", ", ", ")"))
          case None =>
            defn.paramClauseGroups.headOption
              .map(_.tparamClause)
              .filter(_.values.nonEmpty)
              .map(clause =>
                Patch
                  .addRight(clause, parameters.mkString("(using ", ", ", ")"))
              )
              .getOrElse(
                Patch.addRight(
                  defn.name,
                  parameters.mkString("(using ", ", ", ")")
                )
              )
        }
    }
  }

  private def replaceSignatureTypes(
      usage: UsageResult.Abstractable,
      replacement: String,
      kind: KindShape
  )(implicit doc: SemanticDocument): Patch = {
    val writtenConstructor = usage.target match {
      case applied: Type.Apply => applied.tpe.symbol
      case target              => target.symbol
    }
    val constructors =
      Set(usage.constructor, writtenConstructor).filter(!_.isNone)
    val roots = usage.defn.paramClauseGroups
      .flatMap(_.paramClauses)
      .flatMap(_.values)
      .flatMap(_.decltpe) ++ usage.defn.decltpe.toList
    val targets =
      roots.flatMap(outerMatchingTypes(_, constructors, kind)).distinct

    targets
      .map(tree => Patch.replaceTree(tree, replacement))
      .foldLeft(Patch.empty)(_ + _)
  }

  private def outerMatchingTypes(
      tree: Tree,
      constructors: Set[Symbol],
      kind: KindShape
  )(implicit doc: SemanticDocument): List[Type] =
    kind match {
      case KindShape.Unary =>
        tree match {
          case applied: Type.Apply if constructors(applied.tpe.symbol) =>
            List(applied.tpe)
          case _ =>
            tree.children.flatMap(outerMatchingTypes(_, constructors, kind))
        }
      case KindShape.Star =>
        tree match {
          case tpe: Type if constructors(tpe.symbol) => List(tpe)
          case _ =>
            tree.children.flatMap(outerMatchingTypes(_, constructors, kind))
        }
      case KindShape.Binary => Nil
    }

  private def renderConstraints(
      typeParamName: String,
      extraArguments: List[String],
      solution: CapabilitySolver.Solution,
      index: CatsIndex
  ): List[String] =
    solution.constraints.map { symbol =>
      val typeclass = index.typeclasses(symbol)
      val extras = extraArguments.take(typeclass.typeParamCount - 1)
      val arguments = typeParamName :: extras
      if (arguments.size == 1) typeclass.renderName
      else s"${typeclass.renderName}[${arguments.mkString(", ")}]"
    }

  private def renderedExtraArguments(
      usage: UsageResult.Abstractable,
      solution: CapabilitySolver.Solution
  ): List[String] =
    if (solution.extraTypeParams.isEmpty) Nil
    else
      usage.constructor.value match {
        case "scala/util/Try#" | "cats/effect/IO#" | "cats/effect/SyncIO#" =>
          "Throwable" :: solution.extraTypeParams.drop(1)
        case _ => solution.extraTypeParams
      }

  private def renderTypeParameter(name: String, kind: KindShape): String =
    kind match {
      case KindShape.Star   => name
      case KindShape.Unary  => s"$name[_]"
      case KindShape.Binary => name
    }

  private def sourceUsesUsingClauses(implicit doc: SemanticDocument): Boolean =
    doc.tree.collect {
      case clause: Term.ParamClause if isUsingClause(clause) => clause
    }.nonEmpty

  private def isUsingClause(clause: Term.ParamClause): Boolean =
    clause.mod.exists(_.is[Mod.Using])

  private def contextBoundConstraints(param: Type.Param)(implicit
      doc: SemanticDocument
  ): List[ExistingConstraint] =
    contextBounds(param).flatMap { bound =>
      constraintType(bound).map { case (symbol, arguments) =>
        ExistingConstraint(symbol, arguments)
      }
    }

  private def usingEvidence(tree: Tree)(implicit
      doc: SemanticDocument
  ): List[(Type, ExistingConstraint)] =
    parameterClauses(tree)
      .filter(isUsingClause)
      .flatMap(_.values)
      .flatMap(_.decltpe)
      .flatMap {
        case applied: Type.Apply =>
          applied.argClause.values match {
            case parameter :: extras =>
              constraintType(applied.tpe).map { case (symbol, _) =>
                parameter -> ExistingConstraint(symbol, extras)
              }
            case Nil => Nil
          }
        case _ => Nil
      }

  private def constraintType(tpe: Type)(implicit
      doc: SemanticDocument
  ): Option[(Symbol, List[Type])] =
    tpe match {
      case applied: Type.Apply if indexableSymbol(applied.tpe.symbol) =>
        Some(applied.tpe.symbol -> applied.argClause.values)
      case _ if indexableSymbol(tpe.symbol) => Some(tpe.symbol -> Nil)
      case _                                => None
    }

  private def indexableSymbol(symbol: Symbol): Boolean =
    !symbol.isNone && symbol.isGlobal

  private def sameTypeParameter(left: Type, right: Type.Param)(implicit
      doc: SemanticDocument
  ): Boolean = {
    val leftSymbol = left.symbol
    val rightSymbol = right.name.symbol
    (!leftSymbol.isNone && leftSymbol == rightSymbol) || left.syntax == right.name.value
  }

  private def extraArgumentsMatch(
      existing: ExistingConstraint,
      required: Symbol,
      expected: List[String],
      index: CatsIndex
  )(implicit doc: SemanticDocument): Boolean = {
    val count =
      index.typeclasses.get(required).map(_.typeParamCount - 1).getOrElse(0)
    if (count == 0) true
    else {
      val actual = existing.extraArguments.take(count).map(typeIdentity)
      actual == expected.take(count)
    }
  }

  private def typeIdentity(
      tpe: Type
  )(implicit doc: SemanticDocument): String = {
    val symbol = tpe.symbol
    if (!symbol.isNone && symbol.isGlobal) symbol.displayName
    else tpe.syntax
  }

  private def addMissingImports(
      defn: Defn.Def,
      requested: List[String]
  )(implicit doc: SemanticDocument): Patch = {
    val visible = visibleImports(defn)
    val missing =
      requested.filterNot(path => visible.exists(importCovers(_, path)))

    if (missing.isEmpty) Patch.empty
    else if (missing.forall(importerOf(_).isDefined))
      // Deduped by scalafix against the symbol each importer resolves to. The
      // hand-built block below anchors every definition's imports on the same
      // node, so a file where two definitions are widened gets the same import
      // emitted twice.
      missing
        .flatMap(importerOf)
        .map(Patch.addGlobalImport)
        .foldLeft(Patch.empty)(_ + _)
    else {
      val block = missing.map(path => s"import $path").mkString("\n")
      topLevelImports(visible).lastOption match {
        case Some(last) => Patch.addRight(last, s"\n$block")
        case None =>
          enclosingTrees(defn).collectFirst { case pkg: Pkg => pkg } match {
            case Some(pkg) => Patch.addRight(pkg.ref, s"\n\n$block")
            case None =>
              doc.tree match {
                case source: Source =>
                  source.stats.headOption
                    .map(first => Patch.addLeft(first, s"$block\n\n"))
                    .getOrElse(Patch.empty)
                case _ => Patch.empty
              }
          }
      }
    }
  }

  /** A dotted import path as an `Importer`: `cats.Functor`, and the wildcard
    * form `cats.syntax.functor.*`.
    */
  private def importerOf(path: String): Option[Importer] = {
    val segments = path.split('.').toList
    val (prefix, last) = segments.splitAt(segments.length - 1)
    (prefix, last) match {
      case (head :: rest, List(leaf)) =>
        val ref = rest.foldLeft[Term.Ref](Term.Name(head))((acc, name) =>
          Term.Select(acc, Term.Name(name))
        )
        if (leaf == "*" || leaf == "_")
          Some(Importer(ref, List(Importee.Wildcard())))
        else
          Some(Importer(ref, List(Importee.Name(Name(leaf)))))
      case _ => None
    }
  }

  private def visibleImports(
      defn: Defn.Def
  )(implicit doc: SemanticDocument): List[Import] = {
    val scopes = (defn :: enclosingTrees(defn)).filter(isScope)
    doc.tree
      .collect {
        case tree: Import
            if tree.pos.start < defn.pos.start && nearestScope(tree).exists(
              scope => scopes.exists(_ eq scope)
            ) =>
          tree
      }
      .sortBy(_.pos.start)
  }

  private def topLevelImports(imports: List[Import]): List[Import] =
    imports.filter(importTree =>
      nearestScope(importTree).exists {
        case _: Source => true
        case _: Pkg    => true
        case _         => false
      }
    )

  private def importCovers(tree: Import, requested: String): Boolean =
    tree.importers.exists { importer =>
      val prefix = importer.ref.syntax
      importer.importees.exists {
        case Importee.Name(name) => requested == s"$prefix.${name.value}"
        case _: Importee.Wildcard =>
          requested.startsWith(s"$prefix.") ||
          (prefix == "cats.syntax.all" && requested.startsWith("cats.syntax."))
        case _ => false
      }
    }

  private def nearestScope(tree: Tree): Option[Tree] =
    tree.parent match {
      case Some(parent) if isScope(parent) => Some(parent)
      case Some(parent)                    => nearestScope(parent)
      case None                            => None
    }

  private def isScope(tree: Tree): Boolean =
    tree match {
      case _: Source     => true
      case _: Pkg        => true
      case _: Template   => true
      case _: Term.Block => true
      case _             => false
    }

  private def parameterClauses(tree: Tree): List[Term.ParamClause] =
    tree match {
      case defn: Defn.Def  => defn.paramClauseGroups.flatMap(_.paramClauses)
      case cls: Defn.Class => cls.ctor.paramClauses.toList
      case traitDef: Defn.Trait => traitDef.ctor.paramClauses.toList
      case enumDef: Defn.Enum   => enumDef.ctor.paramClauses.toList
      case _                    => Nil
    }

  private def typeParameters(tree: Tree): List[Type.Param] =
    tree match {
      case defn: Defn.Def       => defTypeParameters(defn)
      case cls: Defn.Class      => cls.tparamClause.values
      case traitDef: Defn.Trait => traitDef.tparamClause.values
      case enumDef: Defn.Enum   => enumDef.tparamClause.values
      case _                    => Nil
    }

  private def defTypeParameters(defn: Defn.Def): List[Type.Param] =
    defn.paramClauseGroups.flatMap(_.tparamClause.values)

  private def typeParameterKind(param: Type.Param): KindShape =
    kindFromArity(typeParamClause(param).values.size)

  private def kindOf(tpe: Type): KindShape =
    tpe match {
      case applied: Type.Apply => kindFromArity(applied.argClause.values.size)
      case _                   => KindShape.Star
    }

  private def kindFromArity(arity: Int): KindShape =
    arity match {
      case 0 => KindShape.Star
      case 1 => KindShape.Unary
      case _ => KindShape.Binary
    }

  private def typeParamClause(param: Type.Param): Type.ParamClause =
    param.productElement(2).asInstanceOf[Type.ParamClause]

  private def contextBounds(param: Type.Param): List[Type] = {
    val bounds = param.productElement(3).asInstanceOf[Type.Bounds]
    bounds.productElement(2).asInstanceOf[List[Type]]
  }

  private def enclosingTrees(tree: Tree): List[Tree] =
    tree.parent match {
      case Some(parent) => parent :: enclosingTrees(parent)
      case None         => Nil
    }
}
