package fix.opaque

import java.nio.file.Paths

/** Prints the closure a seed produces against a real SemanticDB target root,
  * without writing to any file. This is the Stage 1 review artefact: it shows
  * what *would* be converted, what would be wrapped or unwrapped, and every
  * merge point where the analysis needs a human decision.
  *
  * Usage:
  * {{{
  * mill scalafix.test.runMain fix.opaque.DumpClosure \
  *   <sourceroot> <semanticdb-targetroot> <primitive> <seed>...
  * }}}
  */
object DumpClosure {

  def main(args: Array[String]): Unit = {
    if (args.length < 4) {
      println(
        "usage: DumpClosure <sourceroot> <targetroot> <primitiveSymbol> <seedSymbol>..."
      )
      sys.exit(2)
    }

    val sourceroot = Paths.get(args(0)).toAbsolutePath.normalize
    val targetroot = Paths.get(args(1)).toAbsolutePath.normalize
    val primitive = SemanticdbIndex.canonicalType(args(2))
    val rest = args.drop(3).toList
    val (seedSymbols, widenArgs) = rest.span(_ != "--widen")
    val widen = widenArgs.drop(1).toSet
    if (widen.nonEmpty) println(s"widen: ${widen.mkString(", ")}")

    val index = SemanticdbIndex.load(List(targetroot))
    println(s"documents: ${index.documents.length}")
    println(s"symbols:   ${index.symbolInfo.size}")

    val stale = index.staleDocuments(sourceroot)
    if (stale.nonEmpty)
      println(s"WARNING stale semanticdb for: ${stale.mkString(", ")}")

    seedSymbols.foreach { seed =>
      index.symbolInfo.get(seed) match {
        case None =>
          println(s"\n!! seed not found: $seed")
          val near = index.symbolInfo.keys
            .filter(
              _.toLowerCase.contains(seed.toLowerCase.takeWhile(_ != '#'))
            )
            .toList
            .sorted
            .take(15)
          if (near.nonEmpty)
            println(s"   did you mean:\n     ${near.mkString("\n     ")}")
        case Some(info) =>
          println(s"\nseed $seed : ${index.typeAt(Node(seed, TypePath.root))}")
          println(s"     displayName=${info.displayName} kind=${info.kind}")
          println(s"     signature=${info.signature.toString.take(220)}")
          println(s"     inputArgIndex=${index.functionInputArgIndex(seed)}")
          (0 to 3).foreach { i =>
            val slot = index.typeAt(Node(seed, TypePath(List(1, i))))
            if (slot.nonEmpty) println(s"     input slot $i = ${slot.get}")
          }
      }
    }

    // A document that fails to parse silently contributes no edges, which looks
    // exactly like a missing feature. Report it explicitly.
    import scala.meta._
    index.documents.foreach { doc =>
      val text =
        if (doc.text.nonEmpty) Some(doc.text)
        else {
          val file = sourceroot.resolve(doc.uri)
          if (java.nio.file.Files.isRegularFile(file))
            Some(java.nio.file.Files.readString(file))
          else None
        }
      text match {
        case None => println(s"NO SOURCE for ${doc.uri}")
        case Some(source) =>
          dialects.Scala3(source).parse[Source] match {
            case _: Parsed.Success[_] => ()
            case error: Parsed.Error =>
              println(s"PARSE FAILED ${doc.uri}: ${error.message.take(160)}")
          }
      }
    }

    val graph = new GraphBuilder(index, sourceroot).build()
    println(s"\nedges: ${graph.edges.length}")
    graph.edges.groupBy(_.kind).toList.sortBy(_._1.toString).foreach {
      case (kind, group) => println(f"  $kind%-16s ${group.length}")
    }
    println(s"synthetic nodes: ${graph.syntheticTypes.size}")

    val facts = GraphBuilder.facts(index, graph)

    val seeds = seedSymbols.map(Node(_, TypePath.root)).toSet
    val result = Closure.compute(seeds, facts, primitive, widen)

    section("CLOSURE (would be retyped)", result.members.map(_.render))
    section(
      "GENESIS (would be wrapped)",
      result.genesis.map(b =>
        s"${b.node.render}  <- ${b.counterpart.render}  at ${b.at.render}"
      )
    )
    section(
      "LEAVES (would be unwrapped with .value)",
      result.leaves.map(b =>
        s"${b.node.render}  -> ${b.counterpart.render}  at ${b.at.render}"
      )
    )
    section(
      "MERGE POINTS (need a decision)",
      result.mergePoints.map(_.message)
    )
  }

  private def section(title: String, lines: List[String]): Unit = {
    println(s"\n=== $title (${lines.length}) ===")
    lines.foreach(line => println(s"  $line"))
  }
}
