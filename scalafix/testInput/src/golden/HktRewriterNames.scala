/*
rules = [DisableSyntax]
 */
package golden

object HktRewriterNames {
  private def clean(values: List[Int]): List[Int] = values
}

final class HktRewriterEnclosingName[G[_]] {
  private def enclosing(values: List[Int]): List[Int] = values
}

final class HktRewriterAllNames[G[_]] {
  private def allTaken[H, K](values: List[Int]): List[Int] = values
}
