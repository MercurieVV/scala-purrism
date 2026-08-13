/*
rules = [PreferEffectIdioms]
 */
package golden

import java.io.InputStream

final class IdiomUsingResource {

  /** One expression, and the body reads the resource it closes. */
  def readAll(open: () => InputStream): String = {
    val stream = open()
    try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()
  }

  /** A block body keeps its braces. */
  def drain(open: () => InputStream): Int = {
    val stream = open()
    try {
      val first = stream.read()
      first + stream.read()
    } finally stream.close()
  }

  /** The finally does more than close, so there is nothing safe to say. */
  def noisy(open: () => InputStream, log: String => Unit): Unit = {
    val stream = open()
    try stream.read() // assert: PreferEffectIdioms
    finally { log("done"); stream.close() }
  }

  def widen(value: Any): String =
    value.asInstanceOf[String] // assert: PreferEffectIdioms
}
