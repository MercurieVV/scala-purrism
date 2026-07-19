package fix

import scala.meta._

import scalafix.v1._

final class TypelevelPurrism extends SemanticRule("TypelevelPurrism") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val knownKleislies = TypelevelPurrism.collectKleisliNames(doc.tree)

    doc.tree.collect { case defn: Defn.Def =>
      kleisliPatch(defn, knownKleislies)
    }.asPatch
  }

  private def kleisliPatch(
      defn: Defn.Def,
      knownKleislies: Set[String]
  )(implicit doc: SemanticDocument): Patch =
    TypelevelPurrism
      .kleisliRewrite(defn, knownKleislies)
      .fold(Patch.empty) { rewritten =>
        Patch.addGlobalImport(Symbol("cats/data/Kleisli#")) +
          Patch.replaceTree(defn, rewritten)
      }
}

private[fix] object TypelevelPurrism {
  def kleisliRewrite(
      defn: Defn.Def,
      knownKleislies: Set[String] = Set.empty
  ): Option[String] =
    kleisliCompositionRewrite(defn, knownKleislies)
      .orElse(kleisliApplyRewrite(defn))

  private def kleisliApplyRewrite(defn: Defn.Def): Option[String] =
    singlePlainParameter(defn).flatMap { param =>
      defn.decltpe.collect {
        case returnType: Type.Apply
            if isKleisliCandidate(defn.mods, param, returnType) =>
          val modifiers = renderModifiers(defn.mods)
          val parameterName = param.name.syntax
          val parameterType = param.decltpe.map(_.syntax).getOrElse("")
          val result = returnType.argClause.values.head

          s"""${modifiers}def ${defn.name.syntax}: Kleisli[${returnType.tpe.syntax}, $parameterType, ${result.syntax}] =
             |  Kleisli.apply { $parameterName =>
             |    ${defn.body.syntax}
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

  private def singlePlainParameter(defn: Defn.Def): Option[Term.Param] =
    defn.paramClauseGroups match {
      case List(group)
          if group.tparamClause.values.isEmpty &&
            group.paramClauses.length == 1 &&
            group.paramClauses.head.mod.isEmpty =>
        group.paramClauses.head.values match {
          case List(param) => Some(param)
          case _           => None
        }
      case _ =>
        None
    }

  private def isKleisliCandidate(
      mods: List[Mod],
      param: Term.Param,
      returnType: Type.Apply
  ): Boolean =
    !mods.exists(_.is[Mod.Override]) &&
      param.mods.isEmpty &&
      param.decltpe.nonEmpty &&
      param.default.isEmpty &&
      isFResult(returnType)

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

  private def renderModifiers(mods: List[Mod]): String =
    if (mods.isEmpty) ""
    else mods.map(_.syntax).mkString("", " ", " ")

  private final case class CompositionRewrite(expression: String)

  private final case class SplitKleisliCall(callee: String, argument: Term)

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
            s"""Kleisli.apply { ${param.name.syntax} =>
               |${indent(leftBody, 2)}
               |}.andThen(${split.callee})""".stripMargin
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

  private def indent(value: String, spaces: Int): String = {
    val prefix = " " * spaces

    value.linesIterator.map(prefix + _).mkString("\n")
  }

}
