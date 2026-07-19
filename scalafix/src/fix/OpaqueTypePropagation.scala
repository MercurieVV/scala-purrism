package fix

import scala.annotation.nowarn
import scala.meta._
import scalafix.v1._

final class OpaqueTypePropagation
    extends SemanticRule("OpaqueTypePropagation") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val tree =
      dialects.Scala3(doc.input.text).parse[Source].toOption.getOrElse(doc.tree)
    val chains = OpaqueTypePropagation.findPropagationChains(tree)
    val plan = OpaqueTypePropagation.rewritePlan(tree, chains, Some(doc.input))

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
    "(?i).+[A-Z_].*(id|name|amount|price|token|code|key|url|email|address|number|count|width|height|millis|seconds|sha)$".r

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
    @nowarn("cat=deprecation")
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

        val unwrapUsagePatches: List[Patch] = chains.flatMap { chain =>
          doc.tree.collect {
            case Term.ApplyInfix(lhs: Term.Name, op, _, _)
                if (op.value == "/" || op.value == "*" || op.value == "+" || op.value == "-") &&
                  chain.nodes.exists(n => n.name == lhs.value) =>
              List(Patch.replaceTree(lhs, s"${lhs.value}.value"))

            case Term.Apply(
                  Term.Select(Term.Name("Thread"), Term.Name("sleep")),
                  args
                ) =>
              args.collect {
                case nameTerm: Term.Name
                    if chain.nodes.exists(n => n.name == nameTerm.value) =>
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
                apply.argClause.values.collect {
                  case nameTerm: Term.Name
                      if chain.nodes.exists(n => n.name == nameTerm.value) =>
                    Patch.replaceTree(nameTerm, s"${nameTerm.value}.value")
                }
              } else List.empty[Patch]

            case apply: Term.ApplyInfix =>
              val op = apply.op.value
              if (
                op == "==" || op == "!=" || op == ">=" || op == "<=" || op == ">" || op == "<"
              ) {
                val leftPatch = apply.lhs match {
                  case nameTerm: Term.Name
                      if chain.nodes.exists(n => n.name == nameTerm.value) =>
                    List(
                      Patch.replaceTree(nameTerm, s"${nameTerm.value}.value")
                    )
                  case _ => Nil
                }
                val rightPatch = apply.argClause match {
                  case Term.ArgClause(List(nameTerm: Term.Name), _)
                      if chain.nodes.exists(n => n.name == nameTerm.value) =>
                    List(
                      Patch.replaceTree(nameTerm, s"${nameTerm.value}.value")
                    )
                  case _ => Nil
                }
                leftPatch ++ rightPatch
              } else List.empty[Patch]
          }.flatten
        }

        headerPatch + paramPatches.asPatch + returnPatches.asPatch + unwrapUsagePatches.asPatch
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
       |  extension (opaqueValue: $opaqueName) def value: $primitiveType = opaqueValue.asInstanceOf[$primitiveType]""".stripMargin
  }

  def findPropagationChains(tree: Tree): List[PropagationChain] = {
    val allDefMethods = tree.collect { case defn: Defn.Def => defn }
    val allDeclMethods = tree.collect { case decl: Decl.Def => decl }
    val allClasses = tree.collect { case cls: Defn.Class => cls }

    val paramNodesDef = for {
      defn <- allDefMethods
      paramClause <- defn.paramClauseGroups.flatMap(_.paramClauses)
      param <- paramClause.values
      tpe <- param.decltpe
      primitiveTypeName = unwrapPrimitiveType(tpe)
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
      primitiveTypeName = unwrapPrimitiveType(tpe)
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
      primitiveTypeName = unwrapPrimitiveType(tpe)
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
      docInput: Option[Input] = None
  ): RewritePlan = {
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
      opaqueTypeDefinitions = opaqueDefs
    )
  }
}
