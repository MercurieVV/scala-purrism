package fix.vocabulary

import scala.meta._
import scala.util.Try

final class ConstructMatcher private (classes: List[Class[?]]) {
  def matches(node: Tree): Boolean = classes.exists(_.isInstance(node))

  def findAll(tree: Tree): List[Tree] =
    tree.collect { case node if matches(node) => node }
}

object ConstructMatcher {
  val empty: ConstructMatcher = new ConstructMatcher(Nil)

  /** A dotted config entry like `scala.meta.Term.If` names a case class nested
    * inside a sealed trait's companion object; its JVM binary name uses `$` for
    * that nesting (`scala.meta.Term$If`). Try the literal name first (covers a
    * top-level class with no nesting), then fall back to replacing the last `.`
    * with `$`.
    */
  private def resolve(name: String): Try[Class[?]] =
    Try(Class.forName(name)).recoverWith { case _ =>
      val lastDot = name.lastIndexOf('.')
      if (lastDot < 0) Try(Class.forName(name))
      else Try(Class.forName(name.updated(lastDot, '$')))
    }

  def compile(names: List[String]): Either[String, ConstructMatcher] = {
    val attempts = names.map(n => n -> resolve(n))
    attempts.collectFirst { case (raw, scala.util.Failure(e)) =>
      s"unknown Scalameta tree class '$raw': ${e.getMessage}"
    } match {
      case Some(err) => Left(err)
      case None      => Right(new ConstructMatcher(attempts.map(_._2.get)))
    }
  }
}
