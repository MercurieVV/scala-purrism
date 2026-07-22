package fix.opaque

import java.nio.file.Files
import java.nio.file.Path

import scala.annotation.nowarn
import scala.meta._
import scala.meta.internal.{semanticdb => s}

/** Turns the SemanticDB payload into value-flow edges.
  *
  * SemanticDB records *symbols at ranges*, not a tree: it will say "the symbol
  * `Git/ensureBranch().` occurs at 57:12" but not which argument of that call
  * sits in which parameter. So each source is parsed with scalameta and the
  * parse tree is aligned to the occurrence table by position -- the tree
  * supplies structure, SemanticDB supplies identity.
  *
  * These parses exist only to derive edges. Nothing here is ever a patch
  * target; the closure carries symbol strings and `Provenance`, never a `Tree`.
  * That wall is what keeps this from repeating `OpaqueTypePropagation`'s bug of
  * anchoring patches on a tree parsed from a different `Input` than `doc.tree`.
  */
@nowarn("cat=deprecation")
final class GraphBuilder(index: SemanticdbIndex, sourceroot: Path) {

  def build(): Graph = {
    val perDocument = index.documents.map(document => graphFor(document))
    Graph(
      edges = perDocument.flatMap(_.edges) ++ fieldAliasEdges,
      syntheticTypes = perDocument.flatMap(_.syntheticTypes).toMap
    )
  }

  // -- per-document tree walking ------------------------------------------

  private def sourceText(document: s.TextDocument): Option[String] =
    if (document.text.nonEmpty) Some(document.text)
    else {
      val file = sourceroot.resolve(document.uri)
      if (Files.isRegularFile(file)) Some(Files.readString(file)) else None
    }

  @nowarn("cat=deprecation")
  private def graphFor(document: s.TextDocument): Graph = {
    val parsed = sourceText(document)
      .flatMap(text => dialects.Scala3(text).parse[Source].toOption)

    parsed
      .map { tree =>
        val lookup = new OccurrenceLookup(document)
        val ctx = new DocumentContext(document.uri, lookup)

        val callEdges = tree.collect { case apply: Term.Apply =>
          argumentEdges(apply, ctx)
        }.flatten

        val returnEdges = tree.collect { case defn: Defn.Def =>
          ctx.symbolOf(defn.name).toList.flatMap { defSymbol =>
            tailExpressions(defn.body).flatMap { tail =>
              ctx.valueNode(tail).map { source =>
                Edge(
                  source,
                  Node(defSymbol, TypePath.root),
                  EdgeKind.BodyToReturn,
                  ctx.provenanceOf(tail)
                )
              }
            }
          }
        }.flatten

        val valEdges = tree.collect { case Defn.Val(_, pats, _, rhs) =>
          tailExpressions(rhs).flatMap { tail =>
            ctx.valueNode(tail).toList.flatMap { sourceNode =>
              pats.flatMap(pat => patternEdges(pat, sourceNode, ctx))
            }
          }
        }.flatten

        val defaultParamEdges = tree.collect { case param: Term.Param =>
          (ctx.symbolOf(param.name), param.default) match {
            case (Some(paramSymbol), Some(defaultTerm)) =>
              ctx
                .valueNode(defaultTerm)
                .map { source =>
                  Edge(
                    source,
                    Node(paramSymbol, TypePath.root),
                    EdgeKind.ArgToParam,
                    ctx.provenanceOf(defaultTerm)
                  )
                }
                .toList
            case _ => Nil
          }
        }.flatten

        val matchEdges = tree.collect { case Term.Match(scrutinee, cases) =>
          ctx.valueNode(scrutinee).toList.flatMap { sourceNode =>
            cases.flatMap(c => patternEdges(c.pat, sourceNode, ctx))
          }
        }.flatten

        val generatorEdges = tree.collect {
          case Enumerator.Generator(pat, rhs) =>
            ctx.valueNode(rhs).toList.flatMap { sourceNode =>
              patternEdges(
                pat,
                Node(sourceNode.symbol, sourceNode.path / 0),
                ctx
              )
            }
        }.flatten

        val receiverEdges = tree.collect {
          case select @ Term.Select(qual, name) =>
            ctx.symbolOf(name).toList.flatMap { calleeSymbol =>
              if (index.isProject(calleeSymbol)) Nil
              else {
                ctx
                  .valueNode(qual)
                  .map { source =>
                    Edge(
                      source,
                      Node(s"foreign:$calleeSymbol:this", TypePath.root),
                      EdgeKind.ArgToParam,
                      ctx.provenanceOf(qual)
                    )
                  }
                  .toList
              }
            }
          case infix @ Term.ApplyInfix(lhs, op, _, _) =>
            ctx.symbolOf(op).toList.flatMap { calleeSymbol =>
              if (index.isProject(calleeSymbol)) Nil
              else {
                ctx
                  .valueNode(lhs)
                  .map { source =>
                    Edge(
                      source,
                      Node(s"foreign:$calleeSymbol:this", TypePath.root),
                      EdgeKind.ArgToParam,
                      ctx.provenanceOf(lhs)
                    )
                  }
                  .toList
              }
            }
        }.flatten

        val controlFlowEdges = tree.collect {
          case Term.If(cond, _, _) =>
            ctx
              .valueNode(cond)
              .map { source =>
                Edge(
                  source,
                  Node("foreign:if:cond", TypePath.root),
                  EdgeKind.ArgToParam,
                  ctx.provenanceOf(cond)
                )
              }
              .toList
          case Term.While(cond, _) =>
            ctx
              .valueNode(cond)
              .map { source =>
                Edge(
                  source,
                  Node("foreign:while:cond", TypePath.root),
                  EdgeKind.ArgToParam,
                  ctx.provenanceOf(cond)
                )
              }
              .toList
        }.flatten

        val kleisliEdges =
          tree.collect {
            case defn: Defn.Def =>
              kleisliBinderEdges(Some(defn.name), defn.body, ctx)
            case defn: Defn.Val =>
              val name = defn.pats match {
                case List(Pat.Var(single)) => Some(single)
                case _                     => None
              }
              kleisliBinderEdges(name, defn.rhs, ctx)
          }.flatten ++ tree.collect { case apply: Term.Apply =>
            kleisliApplicationEdges(apply, ctx)
          }.flatten

        val kleisliReturnEdges = tree.collect {
          case apply @ Term.Apply(fun, args) if isKleisliConstructor(fun) =>
            val applyNode = ctx.expressionNode(apply)
            val terms: List[Term] = args.flatMap {
              case Term.PartialFunction(cases) =>
                cases.flatMap(c => tailExpressions(c.body)).collect {
                  case t: Term => t
                }
              case Term.Function(_, body) =>
                tailExpressions(body).collect { case t: Term => t }
              case _ =>
                Nil
            }
            terms.flatMap { retTerm =>
              ctx.valueNode(retTerm).map { retNode =>
                Edge(
                  Node(retNode.symbol, retNode.path / 0),
                  Node(applyNode.symbol, applyNode.path / 2),
                  EdgeKind.HktPassthrough,
                  ctx.provenanceOf(retTerm)
                )
              }
            }
        }.flatten

        val reshapes = tree.collect { case apply: Term.Apply =>
          reshapeGraph(apply, ctx)
        }
        val hktEdges = tree.collect { case apply: Term.Apply =>
          passthroughEdges(apply, ctx)
        }.flatten

        Graph(
          edges =
            callEdges ++ returnEdges ++ valEdges ++ kleisliEdges ++ kleisliReturnEdges ++
              reshapes.flatMap(_.edges) ++ hktEdges ++ defaultParamEdges ++
              matchEdges ++ generatorEdges ++ receiverEdges ++ controlFlowEdges,
          syntheticTypes = reshapes.flatMap(_.syntheticTypes).toMap
        )
      }
      .getOrElse(Graph.empty)
  }

  /** `k.local[T](fn)` reshapes a Kleisli's input: the resulting arrow takes a
    * `T`, `fn` converts it to `k`'s own input, and `k` runs.
    *
    * `Git.scala:18-29` reshapes a 5-tuple into a 4-tuple, and three things have
    * to move together -- the `local[...]` type argument, the ascriptions in the
    * lambda's pattern, and `k`'s declared input. The type argument has no
    * symbol of its own, so it gets a synthetic node keyed by position, typed by
    * resolving the written type through the occurrence table.
    */
  private def reshapeGraph(
      apply: Term.Apply,
      ctx: DocumentContext
  ): Graph = apply.fun match {
    case Term.ApplyType(Term.Select(receiver, Term.Name("local")), typeArgs) =>
      val target = ctx.calleeSymbol(receiver)
      val inputIndex = target.flatMap(index.functionInputArgIndex)
      val inputType = typeArgs.headOption

      val slotTypes = inputType.toList.flatMap {
        case Type.Tuple(elements) => elements.zipWithIndex
        case single               => List(single -> 0)
      }

      val syntheticOwner = inputType
        .map(tpe =>
          s"localinput:${ctx.uri}:${tpe.pos.startLine}:${tpe.pos.startColumn}"
        )

      val synthetics = for {
        owner <- syntheticOwner.toList
        (slotType, slot) <- slotTypes
        symbol <- ctx.symbolOf(typeNameOf(slotType)).toList
      } yield Node(owner, TypePath(List(slot))) ->
        SemanticdbIndex.canonicalType(symbol)

      // The reshaped input flows into the lambda's pattern binders...
      val lambdaPatterns = apply.argClause.values.toList.flatMap(patternsOf)
      val binderEdges = for {
        owner <- syntheticOwner.toList
        pattern <- lambdaPatterns
        (slot, boundName) <- tupleSlotNames(pattern)
        boundSymbol <- ctx.symbolOf(boundName).toList
        edge <- List(
          Edge(
            Node(owner, TypePath(List(slot))),
            Node(boundSymbol, TypePath.root),
            EdgeKind.Reshape,
            ctx.provenanceOf(boundName)
          ),
          Edge(
            Node(boundSymbol, TypePath.root),
            Node(owner, TypePath(List(slot))),
            EdgeKind.Reshape,
            ctx.provenanceOf(boundName)
          )
        )
      } yield edge

      // ...and the tuple the lambda returns flows into the wrapped arrow.
      val outputEdges = for {
        targetSymbol <- target.toList
        index0 <- inputIndex.toList
        body <- apply.argClause.values.toList.flatMap(lambdaBodies)
        (element, slot) <- body match {
          case Term.Tuple(elements) => elements.zipWithIndex
          case single               => List(single -> 0)
        }
        source <- ctx.valueNode(element).toList
        edge <- List(
          Edge(
            source,
            Node(targetSymbol, TypePath(List(index0, slot))),
            EdgeKind.Reshape,
            ctx.provenanceOf(element)
          ),
          Edge(
            Node(targetSymbol, TypePath(List(index0, slot))),
            source,
            EdgeKind.Reshape,
            ctx.provenanceOf(element)
          )
        )
      } yield edge

      Graph(binderEdges ++ outputEdges, synthetics.toMap)

    case _ => Graph.empty
  }

  private def typeNameOf(tpe: Type): Type = tpe match {
    case Type.Apply(inner, _) => inner
    case other                => other
  }

  private def patternsOf(term: Term): List[Pat] = term match {
    case Term.PartialFunction(cases)   => cases.map(_.pat)
    case Term.Block(List(inner: Term)) => patternsOf(inner)
    case _                             => Nil
  }

  private def localPlaceholders(tree: Tree): List[Term.Placeholder] = {
    def loop(t: Tree): List[Term.Placeholder] = t match {
      case p: Term.Placeholder => List(p)
      case _: Term.Function | _: Term.AnonymousFunction |
          _: Term.PartialFunction =>
        Nil
      case other => other.children.flatMap(loop)
    }
    tree.children.flatMap(loop)
  }

  private def lambdaBodies(term: Term): List[Term] = term match {
    case Term.PartialFunction(cases) =>
      cases.flatMap(c => tailExpressions(c.body))
    case Term.Function(_, body)        => tailExpressions(body)
    case Term.AnonymousFunction(body)  => lambdaBodies(body)
    case Term.Block(List(inner: Term)) => lambdaBodies(inner)
    case other =>
      val hasPlaceholder = localPlaceholders(other).nonEmpty
      if (hasPlaceholder) List(other) else Nil
  }

  /** Values threaded unchanged through a container: `map`, `traverse_`, `fold`
    * and friends hand the payload to a function, so the payload's node flows to
    * whatever that function binds it to.
    *
    * `baseBranch.traverse_(ensureBranch(root, _, progress))` is the shape that
    * matters here: the placeholder is not a named binder, so the payload has to
    * be linked straight to the parameter the placeholder sits in.
    */
  private def passthroughEdges(
      apply: Term.Apply,
      ctx: DocumentContext
  ): List[Edge] = apply.fun match {
    case Term.Select(receiver, Term.Name(method))
        if GraphBuilder.PassthroughMethods.contains(method) =>
      val receiverNode = ctx.valueNode(receiver)
      val applyNode = ctx.expressionNode(apply)
      if (ctx.uri.contains("github.scala") && apply.toString.contains("body")) {
        println(
          s"DEBUG passthroughEdges for: $apply, method: $method, receiver: $receiver, receiverNode: $receiverNode"
        )
      }

      val payload = receiverNode.map(node => Node(node.symbol, node.path / 0))

      val inboundEdges = payload.toList.flatMap { source =>
        apply.argClause.values.toList.flatMap { argument =>
          val namedBinders = argument match {
            case Term.Function(params, _) =>
              params.flatMap(param => ctx.symbolOf(param.name))
            case _ => Nil
          }

          val namedEdges = namedBinders.map(binder =>
            Edge(
              source,
              Node(binder, TypePath.root),
              EdgeKind.HktPassthrough,
              ctx.provenanceOf(argument)
            )
          )

          val partialFunctionEdges = argument match {
            case Term.PartialFunction(cases) =>
              cases.flatMap(c => patternEdges(c.pat, source, ctx))
            case _ => Nil
          }

          // `f(a, _, c)`: the placeholder occupies one of f's parameters.
          val placeholderEdges = localPlaceholders(argument).flatMap {
            placeholder =>
              placeholderParameter(placeholder, argument, ctx).map { param =>
                Edge(
                  source,
                  Node(param, TypePath.root),
                  EdgeKind.HktPassthrough,
                  ctx.provenanceOf(placeholder)
                )
              }
          }

          val lambdaPlaceholderEdges = localPlaceholders(argument).map {
            placeholder =>
              Edge(
                source,
                ctx.expressionNode(placeholder),
                EdgeKind.HktPassthrough,
                ctx.provenanceOf(placeholder)
              )
          }

          val methodRefEdges = argument match {
            case _: Term.Name | _: Term.Select =>
              val callee = ctx.calleeSymbol(argument)
              val params = callee.toList.flatMap(index.parameterSymbols)
              params.headOption.map { param =>
                Edge(
                  source,
                  Node(param, TypePath.root),
                  EdgeKind.ArgToParam,
                  ctx.provenanceOf(argument)
                )
              }.toList
            case _ => Nil
          }

          namedEdges ++ partialFunctionEdges ++ placeholderEdges ++ lambdaPlaceholderEdges ++ methodRefEdges
        }
      }

      val returnEdges = if (method == "map" || method == "traverse") {
        apply.argClause.values.toList.flatMap { argument =>
          val bodies = lambdaBodies(argument)
          if (bodies.nonEmpty) {
            bodies.flatMap { retTerm =>
              ctx.valueNode(retTerm).map { retNode =>
                Edge(
                  retNode,
                  Node(applyNode.symbol, applyNode.path / 0),
                  EdgeKind.HktPassthrough,
                  ctx.provenanceOf(retTerm)
                )
              }
            }
          } else {
            argument match {
              case _: Term.Name | _: Term.Select =>
                ctx.calleeSymbol(argument).toList.map { calleeSymbol =>
                  Edge(
                    Node(calleeSymbol, TypePath.root),
                    Node(applyNode.symbol, applyNode.path / 0),
                    EdgeKind.HktPassthrough,
                    ctx.provenanceOf(argument)
                  )
                }
              case _ => Nil
            }
          }
        }
      } else if (method == "flatMap" || method == "flatTraverse") {
        apply.argClause.values.toList.flatMap { argument =>
          val bodies = lambdaBodies(argument)
          if (bodies.nonEmpty) {
            bodies.flatMap { retTerm =>
              ctx.valueNode(retTerm).map { retNode =>
                Edge(
                  Node(retNode.symbol, retNode.path / 0),
                  Node(applyNode.symbol, applyNode.path / 0),
                  EdgeKind.HktPassthrough,
                  ctx.provenanceOf(retTerm)
                )
              }
            }
          } else {
            argument match {
              case _: Term.Name | _: Term.Select =>
                ctx.calleeSymbol(argument).toList.map { calleeSymbol =>
                  Edge(
                    Node(calleeSymbol, TypePath.root / 0),
                    Node(applyNode.symbol, applyNode.path / 0),
                    EdgeKind.HktPassthrough,
                    ctx.provenanceOf(argument)
                  )
                }
              case _ => Nil
            }
          }
        }
      } else {
        receiverNode.toList.map { recNode =>
          Edge(
            Node(recNode.symbol, recNode.path / 0),
            Node(applyNode.symbol, applyNode.path / 0),
            EdgeKind.HktPassthrough,
            ctx.provenanceOf(apply)
          )
        }
      }

      inboundEdges ++ returnEdges

    case _ => Nil
  }

  /** The parameter symbol a `_` stands in for, by its position in the call. */
  private def placeholderParameter(
      placeholder: Term.Placeholder,
      within: Tree,
      ctx: DocumentContext
  ): Option[String] =
    within
      .collect {
        case call: Term.Apply
            if call.argClause.values.exists(_ eq placeholder) =>
          val slot = call.argClause.values.indexWhere(_ eq placeholder)
          ctx.calleeSymbol(call.fun).flatMap { callee =>
            index.parameterSymbols(callee).lift(slot)
          }
      }
      .flatten
      .headOption

  /** Link a Kleisli's declared input tuple to the names its body destructures.
    *
    * `def branchExistsLocally: Kleisli[F, (os.Path, String), Boolean] =
    * Kleisli.apply { case (root, branchName) => ... }`
    *
    * binds `branchName` to path [1, 1] of the method's return type -- type
    * argument 1 is the input tuple, and slot 1 within it. Nothing about the
    * tuple needs special handling: `TupleN` is an ordinary type reference, so
    * `typeAt` walks into it like any other type argument.
    */
  private def kleisliBinderEdges(
      binder: Option[Term.Name],
      body: Term,
      ctx: DocumentContext
  ): List[Edge] =
    for {
      ownerSymbol <- binder.flatMap(ctx.symbolOf).toList
      inputIndex <- index.functionInputArgIndex(ownerSymbol).toList
      pattern <- kleisliInputPatterns(body)
      (slot, boundName) <- tupleSlotNames(pattern)
      boundSymbol <- ctx.symbolOf(boundName).toList
    } yield Edge(
      Node(ownerSymbol, TypePath(List(inputIndex, slot))),
      Node(boundSymbol, TypePath.root),
      EdgeKind.TupleSlot,
      ctx.provenanceOf(boundName)
    )

  /** The `case` patterns of a `Kleisli.apply { ... }` / `Kleisli { ... }` body.
    */
  private def kleisliInputPatterns(body: Term): List[Pat] =
    body.collect {
      case Term.Apply(fun, args) if isKleisliConstructor(fun) =>
        args.collect { case Term.PartialFunction(cases) =>
          cases.map(_.pat)
        }.flatten
    }.flatten

  private def isKleisliConstructor(fun: Term): Boolean = fun match {
    case Term.Name("Kleisli")                                  => true
    case Term.Select(Term.Name("Kleisli"), Term.Name("apply")) => true
    case Term.Select(_, Term.Name("Kleisli"))                  => true
    case _                                                     => false
  }

  /** Names bound at each tuple position, seeing through the wrappers a case
    * pattern can carry: `case whole @ (a, b)`, `case (a: os.Path, b: String)`.
    */
  private def tupleSlotNames(pattern: Pat): List[(Int, Term.Name)] =
    unwrapPattern(pattern) match {
      case Pat.Tuple(elements) =>
        elements.zipWithIndex.flatMap { case (element, slot) =>
          unwrapPattern(element) match {
            case Pat.Var(name) => List(slot -> name)
            case _             => Nil
          }
        }
      case _ => Nil
    }

  private def unwrapPattern(pattern: Pat): Pat = pattern match {
    case Pat.Bind(_, inner)  => unwrapPattern(inner)
    case Pat.Typed(inner, _) => unwrapPattern(inner)
    case other               => other
  }

  /** A Kleisli applied to its input, in either spelling.
    *
    * `branchExistsOnOrigin((root, branchName))` passes an explicit tuple, while
    * `acquireWorktree(root, worktreePath, branchName, baseBranch, progress)`
    * relies on Scala auto-tupling five loose arguments into the one the Kleisli
    * actually takes. Both address the same input slots, so both are flattened
    * the same way here -- missing the second spelling is what kept all of
    * `Git.scala` out of the graph.
    */
  private def kleisliApplicationEdges(
      apply: Term.Apply,
      ctx: DocumentContext
  ): List[Edge] = {
    val slotted: List[(Term, Int)] = apply.argClause.values.toList match {
      case List(Term.Tuple(elements)) => elements.zipWithIndex
      case List(single)               => List(single -> -1)
      case autoTupled                 => autoTupled.zipWithIndex
    }

    for {
      callee <- ctx.calleeSymbol(apply.fun).toList
      inputIndex <- index.functionInputArgIndex(callee).toList
      (element, slot) <- slotted
      source <- ctx.valueNode(element).toList
    } yield Edge(
      source,
      Node(
        callee,
        if (slot < 0) TypePath(List(inputIndex))
        else TypePath(List(inputIndex, slot))
      ),
      EdgeKind.TupleSlot,
      ctx.provenanceOf(element)
    )
  }

  /** Align a call's arguments to the callee's parameter symbols.
    *
    * Named arguments bind by name; a trailing repeated parameter absorbs every
    * remaining positional argument, so
    * `call(root, "git", "branch", branchName)` maps all four onto the varargs
    * symbol.
    */
  @nowarn("cat=deprecation")
  private def argumentEdges(
      apply: Term.Apply,
      ctx: DocumentContext
  ): List[Edge] = {
    val callee = ctx.calleeSymbol(apply.fun)
    val params = callee.toList.flatMap(index.parameterSymbols)
    if (params.isEmpty) {
      // A callee we have no signature for -- `os.proc(...)`, a JDK method, any
      // library call. Its parameters cannot be retyped, so each argument gets a
      // synthetic Foreign node. That is what turns "this value crosses into a
      // library" into a reportable unwrap site instead of silence.
      callee.toList.flatMap { calleeSymbol =>
        if (index.isProject(calleeSymbol)) Nil
        else
          apply.argClause.values.toList.zipWithIndex.flatMap { case (arg, i) =>
            ctx.valueNode(arg).map { source =>
              Edge(
                source,
                Node(s"foreign:$calleeSymbol:$i", TypePath.root),
                EdgeKind.ArgToParam,
                ctx.provenanceOf(arg)
              )
            }
          }
      }
    } else {
      val args = apply.argClause.values.toList
      val (named, positional) = args.partition {
        case Term.Assign(_: Term.Name, _) => true
        case _                            => false
      }

      val namedPairs = named.collect {
        case Term.Assign(Term.Name(label), value) =>
          params
            .find(param =>
              index.symbolInfo.get(param).exists(_.displayName == label)
            )
            .map(_ -> value)
      }.flatten

      val usedByName = namedPairs.map(_._1).toSet
      val remaining = params.filterNot(usedByName.contains)
      val positionalPairs =
        if (remaining.lastOption.exists(isRepeated)) {
          val fixed = remaining.dropRight(1)
          fixed.zip(positional) ++
            positional.drop(fixed.length).map(remaining.last -> _)
        } else remaining.zip(positional)

      (namedPairs ++ positionalPairs).flatMap { case (param, arg) =>
        ctx.valueNode(arg).map { source =>
          Edge(
            source,
            Node(param, TypePath.root),
            EdgeKind.ArgToParam,
            ctx.provenanceOf(arg)
          )
        }
      }
    }
  }

  private def isRepeated(symbol: String): Boolean =
    index.symbolInfo
      .get(symbol)
      .exists(_.signature match {
        case s.ValueSignature(s.RepeatedType(_)) => true
        case _                                   => false
      })

  /** Every expression a term can actually evaluate to, so a value flowing out
    * of a branching body is tracked on all of its branches.
    */
  private def tailExpressions(term: Term): List[Term] =
    term match {
      case Term.Block(stats) =>
        stats.lastOption
          .collect { case tail: Term => tail }
          .toList
          .flatMap(tailExpressions)
      case Term.If(_, thenp, elsep) =>
        tailExpressions(thenp) ++ tailExpressions(elsep)
      case matched: Term.Match =>
        matched.cases.flatMap(branch => tailExpressions(branch.body))
      case Term.Try(body, catches, _) =>
        tailExpressions(body) ++ catches.flatMap(c => tailExpressions(c.body))
      case other => List(other)
    }

  // -- case-class field aliases -------------------------------------------

  private val ctorParamPattern = """^(.+)#`<init>`\(\)\.\((.+)\)$""".r

  /** One case-class field surfaces as four symbols -- getter, constructor
    * parameter, `apply` parameter and `copy` parameter. They denote the same
    * storage, so edges run both ways; otherwise which of the four a user
    * happens to seed would change the result.
    */
  private def fieldAliasEdges: List[Edge] =
    index.symbolInfo.keys.toList.sorted.flatMap { symbol =>
      symbol match {
        case ctorParamPattern(owner, field) =>
          val companion = owner.stripSuffix("#") + "."
          val siblings = List(
            s"$owner#$field.",
            s"$owner#copy().($field)",
            s"${companion}apply().($field)"
          ).filter(index.symbolInfo.contains)

          siblings.flatMap { sibling =>
            val at = provenanceOfSymbol(symbol)
            List(
              Edge(
                Node(symbol, TypePath.root),
                Node(sibling, TypePath.root),
                EdgeKind.FieldAlias,
                at
              ),
              Edge(
                Node(sibling, TypePath.root),
                Node(symbol, TypePath.root),
                EdgeKind.FieldAlias,
                at
              )
            )
          }
        case _ => Nil
      }
    }

  private def provenanceOfSymbol(symbol: String): Provenance =
    index
      .occurrencesOf(symbol)
      .collectFirst {
        case (uri, occurrence)
            if occurrence.role == s.SymbolOccurrence.Role.DEFINITION =>
          occurrence.range
            .map(r =>
              Provenance(
                uri,
                r.startLine,
                r.startCharacter,
                r.endLine,
                r.endCharacter
              )
            )
            .getOrElse(Provenance.unknown)
      }
      .getOrElse(Provenance.unknown)

  // -- position/symbol alignment ------------------------------------------

  /** Occurrences indexed by their start position, for tree alignment. */
  private final class OccurrenceLookup(document: s.TextDocument) {
    private val byStart: Map[(Int, Int), String] =
      document.occurrences.iterator
        .flatMap(occurrence =>
          occurrence.range
            .map(r => (r.startLine, r.startCharacter) -> occurrence.symbol)
        )
        .toMap

    def at(pos: Position): Option[String] =
      byStart.get((pos.startLine, pos.startColumn))
  }

  private final class DocumentContext(
      val uri: String,
      lookup: OccurrenceLookup
  ) {

    def symbolOf(tree: Tree): Option[String] =
      lookup.at(tree.pos).map(SemanticdbIndex.qualify(uri, _))

    def provenanceOf(tree: Tree): Provenance =
      Provenance(
        uri,
        tree.pos.startLine,
        tree.pos.startColumn,
        tree.pos.endLine,
        tree.pos.endColumn
      )

    /** The symbol a call's function position refers to. */
    @nowarn("cat=deprecation")
    def calleeSymbol(fun: Term): Option[String] = (fun match {
      case name: Term.Name          => symbolOf(name)
      case Term.Select(_, name)     => symbolOf(name)
      case Term.ApplyType(inner, _) => calleeSymbol(inner)
      case Term.Apply(inner, _)     => calleeSymbol(inner)
      case _                        => None
    }).map(resolveConstructor)

    /** `TaskRun(...)` records an occurrence of the *type* or its companion, not
      * of the synthesised `apply`. Without this, every case-class construction
      * looks like a call with no parameters and the arguments flow nowhere.
      */
    private def resolveConstructor(symbol: String): String =
      if (index.parameterSymbols(symbol).nonEmpty) symbol
      else {
        val companionApply =
          if (symbol.endsWith("#")) s"${symbol.dropRight(1)}.apply()."
          else if (symbol.endsWith(".")) s"${symbol}apply()."
          else symbol
        val primaryCtor =
          if (symbol.endsWith("#")) s"$symbol`<init>`()."
          else if (symbol.endsWith(".")) s"${symbol.dropRight(1)}#`<init>`()."
          else symbol
        List(companionApply, primaryCtor)
          .find(candidate => index.parameterSymbols(candidate).nonEmpty)
          .getOrElse(symbol)
      }

    /** The node a term evaluates to.
      *
      * A term that resolves to a known symbol is that symbol's value node.
      * Anything else -- a literal, an interpolation, an arithmetic expression,
      * a call into a library -- is a synthetic `Expression` node: this is where
      * a value is born, and where a wrap would go.
      */
    @nowarn("cat=deprecation")
    def valueNode(term: Term): Option[Node] = term match {
      case Term.Assign(_, value) => valueNode(value)
      case name: Term.Name =>
        Some(
          symbolOf(name)
            .filter(index.isProject)
            .map(Node(_, TypePath.root))
            .getOrElse(expressionNode(term))
        )
      case select @ Term.Select(_, name) =>
        val sym = symbolOf(name)
        if (uri.contains("github.scala") && select.toString.contains("body")) {
          println(
            s"DEBUG valueNode for select: $select, name: $name, symbolOf: $sym, isProject: ${sym.exists(index.isProject)}"
          )
        }
        Some(
          sym
            .filter(index.isProject)
            .map(Node(_, TypePath.root))
            .getOrElse(expressionNode(select))
        )
      case apply: Term.Apply =>
        // A call's value is its callee's return, unless the callee is unknown or foreign.
        Some(
          calleeSymbol(apply.fun)
            .filter(index.isProject)
            .map(Node(_, TypePath.root))
            .getOrElse(expressionNode(apply))
        )
      case other => Some(expressionNode(other))
    }

    /** A synthetic node standing for an expression that has no symbol. Keyed by
      * position so it is stable across runs and distinct per site.
      */
    def expressionNode(term: Tree): Node =
      Node(
        s"expr:$uri:${term.pos.startLine}:${term.pos.startColumn}:${term.pos.endLine}:${term.pos.endColumn}",
        TypePath.root
      )
  }

  private def patternEdges(
      pat: Pat,
      sourceNode: Node,
      ctx: DocumentContext
  ): List[Edge] = pat match {
    case Pat.Var(name) =>
      ctx.symbolOf(name).toList.map { varSymbol =>
        Edge(
          sourceNode,
          Node(varSymbol, TypePath.root),
          EdgeKind.ArgToParam,
          ctx.provenanceOf(name)
        )
      }

    case Pat.Typed(lhs, _) =>
      patternEdges(lhs, sourceNode, ctx)

    case Pat.Bind(lhs, rhs) =>
      patternEdges(lhs, sourceNode, ctx) ++ patternEdges(rhs, sourceNode, ctx)

    case Pat.Tuple(elements) =>
      elements.zipWithIndex.flatMap { case (element, i) =>
        patternEdges(element, Node(sourceNode.symbol, sourceNode.path / i), ctx)
      }

    case Pat.Extract(fun, args) =>
      val callee = ctx.calleeSymbol(fun)
      val params = callee.toList.flatMap(index.parameterSymbols)
      if (params.nonEmpty) {
        params.zip(args.values).flatMap { case (param, arg) =>
          patternEdges(arg, Node(param, TypePath.root), ctx)
        }
      } else {
        args.values.zipWithIndex.flatMap { case (arg, i) =>
          patternEdges(arg, Node(sourceNode.symbol, sourceNode.path / i), ctx)
        }
      }

    case Pat.ExtractInfix(lhs, op, rhs) =>
      val callee = ctx.calleeSymbol(op)
      val params = callee.toList.flatMap(index.parameterSymbols)
      val args = lhs :: rhs
      if (params.nonEmpty) {
        params.zip(args).flatMap { case (param, arg) =>
          patternEdges(arg, Node(param, TypePath.root), ctx)
        }
      } else {
        args.zipWithIndex.flatMap { case (arg, i) =>
          patternEdges(arg, Node(sourceNode.symbol, sourceNode.path / i), ctx)
        }
      }

    case _ => Nil
  }
}

/** The flow edges, plus types for nodes that have no symbol of their own -- at
  * present the explicit type argument of a `Kleisli.local[...]` reshaping.
  */
final case class Graph(
    edges: List[Edge],
    syntheticTypes: Map[Node, String]
)

object Graph {
  val empty: Graph = Graph(Nil, Map.empty)
}

object GraphBuilder {

  /** Methods that hand a container's payload to a function without changing it.
    * Only the payload's identity matters here, not the container's shape.
    */
  val PassthroughMethods: Set[String] = Set(
    "map",
    "flatMap",
    "foreach",
    "traverse",
    "traverse_",
    "flatTraverse",
    "fold",
    "foldMap",
    "collect",
    "filter",
    "filterNot",
    "exists",
    "forall",
    "getOrElse",
    "orElse",
    "local",
    "handleErrorWith",
    "as",
    "void",
    "attempt"
  )

  /** Origin lookup that understands the synthetic node symbols above. */
  def originOf(index: SemanticdbIndex, symbol: String): Origin =
    if (symbol.startsWith("expr:")) Origin.Expression
    else if (symbol.startsWith("foreign:")) Origin.Foreign
    else if (symbol.startsWith("localinput:")) Origin.Project
    else if (index.isProject(symbol)) Origin.Project
    else Origin.Foreign

  /** The `Facts` view the closure runs over, combining SemanticDB signatures
    * with the synthetic nodes the builder introduced.
    */
  def facts(index: SemanticdbIndex, graph: Graph): Facts = new Facts {
    def edges: List[Edge] = graph.edges
    def origin(symbol: String): Origin = originOf(index, symbol)
    def typeAt(node: Node): Option[String] =
      graph.syntheticTypes.get(node).orElse(index.typeAt(node))
  }
}
