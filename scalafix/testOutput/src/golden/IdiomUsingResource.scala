
package golden

import java.io.InputStream
import scala.util.Using

final class IdiomUsingResource {

  /** One expression, and the body reads the resource it closes. */
  def readAll(open: () => InputStream): String = {
    val stream = open()
    Using.resource(stream)(_ => scala.io.Source.fromInputStream(stream, "UTF-8").mkString)
  }

  /** A block body keeps its braces. */
  def drain(open: () => InputStream): Int = {
    val stream = open()
    Using.resource(stream) { _ =>
      val first = stream.read()
      first + stream.read()
    }
  }

  /** The finally does more than close, so there is nothing safe to say. */
  def noisy(open: () => InputStream, log: String => Unit): Unit = {
    val stream = open()
    try stream.read() 
    finally { log("done"); stream.close() }
  }

  def widen(value: Any): String =
    value.asInstanceOf[String] 
}
