/*
rules = [DisableSyntax]
 */
package hkt

private object UsageAnalyzerSmoke {
  private def mapOnly(values: List[Int]): List[Int] =
    values.map(identity)

  private def headOnly(values: List[Int]): Int =
    values.head
}
