package fix

import scala.meta._
import scalafix.v1._

final class OpaqueTypePropagation
    extends SemanticRule("OpaqueTypePropagation") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val tree =
      dialects.Scala3(doc.input.text).parse[Source].toOption.getOrElse(doc.tree)
    val chains = OpaqueTypePropagation.findPropagationChains(tree)
    val plan = OpaqueTypePropagation.rewritePlan(tree, chains)

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
    "d"
  )

  private val SpecificDomainPattern =
    "(?i).*(id|name|amount|price|token|code|key|url|email|address|number|count|width|height|millis|seconds|status)$".r

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
              case Some(tpe: Type.Name) =>
                Patch.replaceTree(tpe, chain.typeName)
              case Some(
                    Type.Apply.After_4_6_0(
                      Type.Name("Option"),
                      argClause
                    )
                  ) if argClause.values.length == 1 =>
                Patch.replaceTree(argClause.values.head, chain.typeName)
              case _ => Patch.empty
            }
          }
        }

        val returnPatches = chains.flatMap(_.returnTypeDefs).flatMap { defn =>
          defn.decltpe match {
            case Some(tpe: Type.Name) =>
              chains.find(_.returnTypeDefs.contains(defn)).map { chain =>
                Patch.replaceTree(tpe, chain.typeName)
              }
            case _ => None
          }
        }

        headerPatch + paramPatches.asPatch + returnPatches.asPatch
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

    val paramNodes = for {
      defn <- allMethods
      paramClause <- defn.paramClauseGroups.flatMap(_.paramClauses)
      param <- paramClause.values
      tpe <- param.decltpe
      primitiveTypeName = unwrapPrimitiveType(tpe)
      if primitiveTypeName.exists(SupportedPrimitives.contains)
      cleanName = param.name.value.replaceAll("^_+|_+$", "")
      if !GenericParamNames.contains(cleanName.toLowerCase)
    } yield {
      ParameterNode(
        name = param.name.value,
        paramType = primitiveTypeName.get,
        paramTree = param,
        ownerDefName = Some(defn.name.value)
      )
    }

    val classParamNodes = for {
      cls <- allClasses
      paramClause <- cls.ctor.paramClauses
      param <- paramClause.values
      tpe <- param.decltpe
      primitiveTypeName = unwrapPrimitiveType(tpe)
      if primitiveTypeName.exists(SupportedPrimitives.contains)
      cleanName = param.name.value.replaceAll("^_+|_+$", "")
      if !GenericParamNames.contains(cleanName.toLowerCase)
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
      }
    }.flatten

    val combinedNodes = paramNodes ++ classParamNodes

    val grouped = combinedNodes.groupBy { node =>
      val inferred = inferOpaqueTypeName(node.name, node.ownerDefName)
      (node.paramType, inferred)
    }

    grouped
      .flatMap { case ((primitiveType, typeName), nodes) =>
        val callPassingsCount =
          nodes.map(n => callSitePassings.count(_ == n.name)).sum
        val totalWeight = nodes.length + callPassingsCount
        val isDomainMatch =
          nodes.exists(n => SpecificDomainPattern.matches(n.name))

        if (totalWeight >= 2 || isDomainMatch) {
          val matchingReturnDefs = allMethods.filter { defn =>
            defn.decltpe.exists(t =>
              unwrapPrimitiveType(t).contains(primitiveType)
            ) &&
            nodes.exists(_.ownerDefName.contains(defn.name.value))
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

  private def unwrapPrimitiveType(tpe: Type): Option[String] = tpe match {
    case Type.Name(name) if SupportedPrimitives.contains(name) => Some(name)
    case Type.Apply.After_4_6_0(Type.Name("Option"), argClause)
        if argClause.values.length == 1 =>
      unwrapPrimitiveType(argClause.values.head)
    case _ => None
  }

  def rewritePlan(tree: Tree, chains: List[PropagationChain]): RewritePlan = {
    val existingTypeNames = tree.collect {
      case defn: Defn.Type if defn.name.value != "" => defn.name.value
      case cls: Defn.Class                          => cls.name.value
      case traitDef: Defn.Trait                     => traitDef.name.value
      case obj: Defn.Object                         => obj.name.value
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
