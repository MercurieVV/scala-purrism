package fix

import scala.meta._

import scalafix.v1._

final class TypelevelPurrism extends SemanticRule("TypelevelPurrism") {
  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect { case defn: Defn.Def =>
      kleisliPatch(defn)
    }.asPatch

  private def kleisliPatch(
      defn: Defn.Def
  )(implicit doc: SemanticDocument): Patch =
    TypelevelPurrism.kleisliRewrite(defn).fold(Patch.empty) { rewritten =>
      Patch.addGlobalImport(Symbol("cats/data/Kleisli#")) +
        Patch.replaceTree(defn, rewritten)
    }
}

private[fix] object TypelevelPurrism {
  def kleisliRewrite(defn: Defn.Def): Option[String] =
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
      returnType.tpe.syntax == "F" &&
      returnType.argClause.values.length == 1

  private def renderModifiers(mods: List[Mod]): String =
    if (mods.isEmpty) ""
    else mods.map(_.syntax).mkString("", " ", " ")
}
