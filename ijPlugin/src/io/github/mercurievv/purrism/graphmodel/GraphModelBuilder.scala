package io.github.mercurievv.purrism.graphmodel

import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Builds a [[GraphModel]] from a `SemanticIndex`: build-tool modules (the
  * leading path segment of each symbol's definition uri),
  * classes/traits/objects, type aliases, methods (with a rendered signature and
  * edges to the methods they call), and values -- plus the relationships
  * between them (`Contains`, `Extends`, `Calls`, `TypeReference`, `Implicit`).
  *
  * Modelled after
  * `com.github.mercurievv.scalasemantic.analysis.graph.DependencyGraphs`, but
  * where that module lifts everything to owning-type granularity for coupling
  * metrics, this one keeps methods and values as first-class nodes so the full
  * entity graph is available for drill-down -- [[ModuleProjection]] is what
  * collapses it back down to modules for the Three.js overview.
  */
object GraphModelBuilder:

  def build(index: SemanticIndex): GraphModel =
    val defUri: Map[String, String] =
      index.occurrences.iterator.collect {
        case (uri, occ) if occ.role == s.SymbolOccurrence.Role.DEFINITION =>
          occ.symbol -> uri
      }.toMap

    // First path segment alone would collapse a nested submodule (e.g. `scalafix/testInput`) into
    // its parent (`scalafix`), since both URIs start with "scalafix/". Splitting on "/src/" keeps
    // everything up to and including the module's own directory name instead.
    def moduleOf(symbol: String): String =
      defUri.get(symbol) match
        case Some(uri) =>
          val idx = uri.indexOf("/src/")
          val name =
            if idx > 0 then uri.substring(0, idx) else uri.takeWhile(_ != '/')
          if name.nonEmpty then name else "<unknown>"
        case None => "<unknown>"

    def scopeInfos(scope: Option[s.Scope]): Seq[s.SymbolInformation] =
      scope.toSeq.flatMap { sc =>
        if sc.hardlinks.nonEmpty then sc.hardlinks
        else sc.symlinks.flatMap(index.info)
      }

    def renderType(t: s.Type): String =
      typeHead(t).map(index.displayName).getOrElse("?")

    def renderMethodSignature(si: s.SymbolInformation): String =
      si.signature match
        case m: s.MethodSignature =>
          val params = m.parameterLists
            .flatMap(pl => scopeInfos(Some(pl)))
            .map(p =>
              s"${index.displayName(p.symbol)}: ${renderType(valueType(p))}"
            )
            .mkString(", ")
          s"($params): ${renderType(m.returnType)}"
        case _ => ""

    def renderValueSignature(si: s.SymbolInformation): String =
      si.signature match
        case v: s.ValueSignature => renderType(v.tpe)
        case _                   => ""

    def signatureTypeRefs(sig: s.Signature): Set[String] =
      sig match
        case v: s.ValueSignature => typeRefs(v.tpe)
        case m: s.MethodSignature =>
          typeRefs(m.returnType) ++
            m.parameterLists
              .flatMap(pl => scopeInfos(Some(pl)))
              .flatMap(p => typeRefs(valueType(p)))
              .toSet
        case _ => Set.empty

    def implicitDependencyTypes(si: s.SymbolInformation): Set[String] =
      si.signature match
        case m: s.MethodSignature =>
          m.parameterLists.toList
            .flatMap(pl => scopeInfos(Some(pl)))
            .filter(isImplicit)
            .flatMap(p => typeRefs(valueType(p)))
            .toSet
        case _ => Set.empty

    def buildCallEdges(nodeIds: Set[String]): Vector[Edge] =
      index.documents.iterator.flatMap { doc =>
        val ordered = doc.occurrences.sortBy(o =>
          (
            o.range.map(_.startLine).getOrElse(0),
            o.range.map(_.startCharacter).getOrElse(0)
          )
        )
        ordered
          .foldLeft((Option.empty[String], Vector.empty[Edge])):
            case ((_, acc), occ)
                if occ.role == s.SymbolOccurrence.Role.DEFINITION && index
                  .isMethod(occ.symbol) =>
              (Some(occ.symbol), acc)
            case ((Some(current), acc), occ)
                if occ.role == s.SymbolOccurrence.Role.REFERENCE && index
                  .isMethod(occ.symbol) &&
                  occ.symbol != current && nodeIds.contains(current) && nodeIds
                    .contains(occ.symbol) =>
              (Some(current), acc :+ Edge(current, occ.symbol, EdgeKind.Calls))
            case (state, _) => state
          ._2
      }.toVector

    val definedSymbols: Vector[s.SymbolInformation] =
      index.symbols.values.iterator
        .filter(si => defUri.contains(si.symbol))
        .toVector

    val typeInfos: Vector[s.SymbolInformation] =
      definedSymbols.filter(si =>
        classKind(si.kind).isDefined || isTypeAlias(si)
      )

    val methodInfos: Vector[s.SymbolInformation] =
      definedSymbols.filter(si => index.isMethod(si.symbol))

    val valueInfos: Vector[s.SymbolInformation] =
      definedSymbols.filter(si => isValue(si) && !index.isMethod(si.symbol))

    val moduleNames: Set[String] =
      (typeInfos.iterator ++ methodInfos.iterator ++ valueInfos.iterator)
        .map(si => moduleOf(si.symbol))
        .toSet

    val moduleNodes =
      moduleNames.toVector.map(m => Node(s"module:$m", NodeKind.Module, m, m))

    // One File node per definition uri actually referenced by a kept symbol -- not every uri
    // SemanticIndex knows about, just the ones something above is owned by. `File` sits between
    // Module and the top-level types it defines: source of truth for "which module" is still
    // moduleOf(symbol), read off the same uri.
    val fileUris: Set[String] =
      (typeInfos.iterator ++ methodInfos.iterator ++ valueInfos.iterator)
        .flatMap(si => defUri.get(si.symbol))
        .toSet

    def fileIdOf(uri: String): String = s"file:$uri"

    def moduleOfUri(uri: String): String =
      val idx = uri.indexOf("/src/")
      val name =
        if idx > 0 then uri.substring(0, idx) else uri.takeWhile(_ != '/')
      if name.nonEmpty then name else "<unknown>"

    val fileNodes = fileUris.toVector.map { uri =>
      val name = uri.split('/').lastOption.getOrElse(uri)
      Node(
        fileIdOf(uri),
        NodeKind.File,
        name,
        moduleOfUri(uri),
        sourceUri = Some(uri)
      )
    }

    val typeNodes = typeInfos.map { si =>
      val kind = classKind(si.kind).getOrElse(NodeKind.TypeAlias)
      Node(
        si.symbol,
        kind,
        index.displayName(si.symbol),
        moduleOf(si.symbol),
        sourceUri = defUri.get(si.symbol)
      )
    }

    val methodNodes = methodInfos.map { si =>
      Node(
        si.symbol,
        NodeKind.MethodDef,
        index.displayName(si.symbol),
        moduleOf(si.symbol),
        signature = Some(renderMethodSignature(si)),
        sourceUri = defUri.get(si.symbol)
      )
    }

    val valueNodes = valueInfos.map { si =>
      Node(
        si.symbol,
        NodeKind.ValueDef,
        index.displayName(si.symbol),
        moduleOf(si.symbol),
        signature = Some(renderValueSignature(si)),
        sourceUri = defUri.get(si.symbol)
      )
    }

    val nodes =
      moduleNodes ++ fileNodes ++ typeNodes ++ methodNodes ++ valueNodes
    val nodeIds = nodes.map(_.id).toSet

    val moduleContainsFileEdges = fileNodes.map { f =>
      Edge(s"module:${f.module}", f.id, EdgeKind.Contains)
    }

    // A member's owner (index.owner) is another kept node (its enclosing class/method/value) when
    // there is one; otherwise it's a top-level definition, whose parent becomes its own file instead
    // of jumping straight to the module -- File now sits between the two.
    val containsEdges =
      (typeNodes ++ methodNodes ++ valueNodes).flatMap { n =>
        val owner = index.owner(n.id)
        val parent =
          if nodeIds.contains(owner) then owner
          else
            defUri
              .get(n.id)
              .map(fileIdOf)
              .filter(nodeIds.contains)
              .getOrElse(s"module:${n.module}")
        Option.when(parent != n.id)(Edge(parent, n.id, EdgeKind.Contains))
      }

    val extendsEdges =
      typeInfos.flatMap { si =>
        si.signature match
          case c: s.ClassSignature =>
            c.parents.flatMap(typeHead).collect {
              case p if p != si.symbol && nodeIds.contains(p) =>
                Edge(si.symbol, p, EdgeKind.Extends)
            }
          case _ => Nil
      }

    val callEdges = buildCallEdges(nodeIds)

    val typeRefEdges =
      (methodInfos ++ valueInfos).flatMap { si =>
        signatureTypeRefs(si.signature).collect {
          case t if t != si.symbol && nodeIds.contains(t) =>
            Edge(si.symbol, t, EdgeKind.TypeReference)
        }
      }

    val implicitEdges =
      (methodInfos ++ valueInfos).flatMap { si =>
        implicitDependencyTypes(si).collect {
          case t if t != si.symbol && nodeIds.contains(t) =>
            Edge(si.symbol, t, EdgeKind.Implicit)
        }
      }

    val edges =
      moduleContainsFileEdges ++ containsEdges ++ extendsEdges ++ callEdges ++ typeRefEdges ++ implicitEdges

    GraphMetrics.compute(GraphModel(nodes, collapseEdges(edges)))

  // --- semanticdb helpers, index-independent ---------------------------------

  private def classKind(kind: s.SymbolInformation.Kind): Option[NodeKind] =
    kind match
      case s.SymbolInformation.Kind.CLASS     => Some(NodeKind.ClassDef)
      case s.SymbolInformation.Kind.TRAIT     => Some(NodeKind.TraitDef)
      case s.SymbolInformation.Kind.INTERFACE => Some(NodeKind.TraitDef)
      case s.SymbolInformation.Kind.OBJECT    => Some(NodeKind.ObjectDef)
      case _                                  => None

  private def isTypeAlias(si: s.SymbolInformation): Boolean =
    si.kind == s.SymbolInformation.Kind.TYPE && si.signature
      .isInstanceOf[s.TypeSignature]

  private def isValue(si: s.SymbolInformation): Boolean =
    si.kind == s.SymbolInformation.Kind.FIELD && si.signature
      .isInstanceOf[s.ValueSignature]

  private def isImplicit(info: s.SymbolInformation): Boolean =
    (info.properties & s.SymbolInformation.Property.IMPLICIT.value) != 0

  private def valueType(info: s.SymbolInformation): s.Type =
    info.signature match
      case v: s.ValueSignature  => v.tpe
      case m: s.MethodSignature => m.returnType
      case _                    => s.Type.Empty

  private def typeHead(t: s.Type): Option[String] =
    t match
      case s.TypeRef(_, sym, _) => Some(sym)
      case s.SingleType(_, sym) => Some(sym)
      case _                    => None

  /** Type symbols a `Type` references, head plus all type arguments,
    * recursively.
    */
  private def typeRefs(t: s.Type): Set[String] =
    t match
      case s.TypeRef(_, sym, args)  => args.flatMap(typeRefs).toSet + sym
      case s.SingleType(_, sym)     => Set(sym)
      case s.ThisType(sym)          => Set(sym)
      case s.SuperType(_, sym)      => Set(sym)
      case s.ByNameType(inner)      => typeRefs(inner)
      case s.RepeatedType(inner)    => typeRefs(inner)
      case s.WithType(ts)           => ts.flatMap(typeRefs).toSet
      case s.IntersectionType(ts)   => ts.flatMap(typeRefs).toSet
      case s.UnionType(ts)          => ts.flatMap(typeRefs).toSet
      case s.AnnotatedType(_, t2)   => typeRefs(t2)
      case s.ExistentialType(t2, _) => typeRefs(t2)
      case s.UniversalType(_, t2)   => typeRefs(t2)
      case s.StructuralType(t2, _)  => typeRefs(t2)
      case _                        => Set.empty

  private def collapseEdges(edges: Vector[Edge]): Vector[Edge] =
    edges
      .groupBy(e => (e.from, e.to, e.kind))
      .view
      .mapValues(_.map(_.weight).sum)
      .map { case ((from, to, kind), weight) => Edge(from, to, kind, weight) }
      .toVector
