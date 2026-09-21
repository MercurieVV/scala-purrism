package fix

/** Entry point invoked by `build.mill`'s `scalafix.findingsTsv` task:
  * `java -cp <classpath> fix.FindingsTsvMain <version>` prints the rendered TSV
  * to stdout.
  */
object FindingsTsvMain {
  def main(args: Array[String]): Unit =
    print(
      FindingsTsv.render(args.headOption.getOrElse("dev"), FindingCatalog.all)
    )
}
