import mill._, scalalib._

object app extends ScalaModule {
  def scalaVersion = "3.8.4"
  def scalacOptions = Seq(
    "-Ysemanticdb",
    "-P:wartremover:traverser:org.wartremover.warts.Unsafe",
    "-Wunused:imports",
    "-Werror"
  )
  def ivyDeps = Agg(
    // [dependencies-start]
    ivy"org.typelevel::cats-core:2.13.0"
    // [dependencies-end]
  )

  def scalacPluginIvyDeps = Agg(
    // [plugins-start]
    // [plugins-end]
  )

  object test extends ScalaTests {
    def testFramework = "munit.Framework"
    def ivyDeps = Agg(
      // [test-dependencies-start]
      ivy"org.scalameta::munit:1.3.4"
      // [test-dependencies-end]
    )
  }
}

object docs extends ScalaModule {
  def scalaVersion = "3.3.4"
  def ivyDeps = Agg(ivy"org.scalameta::mdoc:2.9.0")
}

def prePush() = T.command {
  app.compile()
  app.test.test()()
}
