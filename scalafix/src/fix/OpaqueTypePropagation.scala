package fix

import scala.meta._
import scalafix.v1._

final class OpaqueTypePropagation
    extends SemanticRule("OpaqueTypePropagation") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val chains = OpaqueTypePropagation.findPropagationChains(doc.tree)
    val plan = OpaqueTypePropagation.rewritePlan(doc.tree, chains)

    plan.patches(using doc)
  }
}

object OpaqueTypePropagation {

  val SupportedPrimitives: Set[String] = Set(
    "String",
    "Int",
    "Long",
    "BigDecimal",
    "Double",
    "UUID"
  )

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

  final case class RewritePlan(
      chains: List[PropagationChain],
      opaqueTypeDefinitions: List[String]
  ) {
    def patches(implicit doc: SemanticDocument): Patch = {
      if (chains.isEmpty) Patch.empty
      else {
        val typeDefCode = opaqueTypeDefinitions.mkString("\n\n") + "\n\n"
        val headerPatch = insertTypeDefs(doc.tree, typeDefCode)

        val paramPatches = chains.flatMap { chain =>
          chain.nodes.map { node =>
            node.paramTree.decltpe match {
              case Some(tpe) => Patch.replaceTree(tpe, chain.typeName)
              case None      => Patch.empty
            }
          }
        }

        val returnPatches = chains.flatMap(_.returnTypeDefs).flatMap { defn =>
          defn.decltpe match {
            case Some(tpe) =>
              chains.find(_.returnTypeDefs.contains(defn)).map { chain =>
                Patch.replaceTree(tpe, chain.typeName)
              }
            case None => None
          }
        }

        headerPatch + paramPatches.asPatch + returnPatches.asPatch
      }
    }

    private def insertTypeDefs(tree: Tree, code: String): Patch = {
      tree match {
        case source: Source =>
          source.stats.headOption match {
            case Some(stat) => Patch.addLeft(stat, code)
            case None       => Patch.addLeft(source, code)
          }
        case pkg: Pkg =>
          pkg.body.stats.headOption match {
            case Some(stat) => Patch.addLeft(stat, code)
            case None       => Patch.addLeft(pkg.body, code)
          }
        case other =>
          Patch.addLeft(other, code)
      }
    }
  }

  def inferOpaqueTypeName(
      paramName: String,
      contextName: Option[String] = None
  ): String = {
    val cleanName = paramName.replaceAll("^_+|_+$", "")
    if (cleanName.equalsIgnoreCase("id") && contextName.isDefined) {
      pascalCase(contextName.get) + "Id"
    } else if (
      cleanName.toLowerCase.endsWith("id") && !cleanName.equalsIgnoreCase("id")
    ) {
      pascalCase(cleanName)
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
       |  def apply(value: $primitiveType): $opaqueName = value
       |  extension (opaqueValue: $opaqueName) def value: $primitiveType = opaqueValue""".stripMargin
  }

  def findPropagationChains(tree: Tree): List[PropagationChain] = {
    val allMethods = tree.collect { case defn: Defn.Def => defn }
    val allClasses = tree.collect { case cls: Defn.Class => cls }

    // Collect all primitive parameters across methods and classes
    val paramNodes = for {
      defn <- allMethods
      paramClause <- defn.paramClauseGroups.flatMap(_.paramClauses)
      param <- paramClause.values
      tpe <- param.decltpe
      primitiveTypeName = tpe.syntax
      if SupportedPrimitives.contains(primitiveTypeName)
    } yield {
      ParameterNode(
        name = param.name.value,
        paramType = primitiveTypeName,
        paramTree = param,
        ownerDefName = Some(defn.name.value)
      )
    }

    val classParamNodes = for {
      cls <- allClasses
      paramClause <- cls.ctor.paramClauses
      param <- paramClause.values
      tpe <- param.decltpe
      primitiveTypeName = tpe.syntax
      if SupportedPrimitives.contains(primitiveTypeName)
    } yield {
      ParameterNode(
        name = param.name.value,
        paramType = primitiveTypeName,
        paramTree = param,
        ownerDefName = Some(cls.name.value)
      )
    }

    val combinedNodes = paramNodes ++ classParamNodes

    // Group nodes by primitive type and inferred name
    val grouped = combinedNodes.groupBy { node =>
      val inferred = inferOpaqueTypeName(node.name, node.ownerDefName)
      (node.paramType, inferred)
    }

    // Build propagation chains for clusters with propagation depth >= 2
    grouped
      .collect {
        case ((primitiveType, typeName), nodes) if nodes.length >= 2 =>
          val matchingReturnDefs = allMethods.filter { defn =>
            defn.decltpe.exists(_.syntax == primitiveType) &&
            nodes.exists(_.ownerDefName.contains(defn.name.value))
          }

          PropagationChain(
            typeName = typeName,
            primitiveType = primitiveType,
            nodes = nodes,
            returnTypeDefs = matchingReturnDefs
          )
      }
      .toList
      .sortBy(_.typeName)
  }

  def rewritePlan(tree: Tree, chains: List[PropagationChain]): RewritePlan = {
    val existingTypeNames = tree.collect {
      case defn: Defn.Type if defn.name.value != "" => defn.name.value
    }.toSet

    val newChains =
      chains.filterNot(c => existingTypeNames.contains(c.typeName))
    val opaqueDefs =
      newChains.map(c => generateOpaqueTypeDef(c.typeName, c.primitiveType))

    RewritePlan(
      chains = newChains,
      opaqueTypeDefinitions = opaqueDefs
    )
  }
}
