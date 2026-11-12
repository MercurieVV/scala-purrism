package fix.weakening

import java.nio.file.Paths

import fix.TypeclassWeakening
import fix.TypelevelPurrism
import metaconfig.Conf
import metaconfig.Configured
import scalafix.v1.Configuration

final class TypeclassWeakeningConfigSuite extends munit.FunSuite {

  private val home = Paths.get(System.getProperty("user.home"))

  private def decode(conf: Conf): Configured[TypeclassWeakeningConfig] =
    TypeclassWeakeningConfig.decoder.read(conf)

  test("defaults: home cache dir, debug off") {
    assertEquals(
      decode(Conf.Obj()).get,
      TypeclassWeakeningConfig(TypeclassWeakeningConfig.DefaultCacheDir, false)
    )
    assertEquals(
      TypeclassWeakeningConfig.default.cacheConfig.dir,
      Some(home.resolve(".cache/scala-purrism/tasty-index"))
    )
  }

  test("an empty cacheDir disables the disk cache") {
    val config = decode(Conf.Obj("cacheDir" -> Conf.Str(""))).get
    assertEquals(config.cacheConfig.dir, None)
  }

  test("cacheDir expands ~ and keeps other paths; debug is read") {
    val config = decode(
      Conf.Obj("cacheDir" -> Conf.Str("~/idx"), "debug" -> Conf.Bool(true))
    ).get
    assertEquals(config.cacheConfig.dir, Some(home.resolve("idx")))
    assert(config.cacheConfig.debug)
    assertEquals(
      decode(Conf.Obj("cacheDir" -> Conf.Str("/tmp/idx"))).get.cacheConfig.dir,
      Some(Paths.get("/tmp/idx"))
    )
  }

  test("the rule and the umbrella both decode the TypeclassWeakening block") {
    val bad = Configuration().withConf(
      Conf.Obj("TypeclassWeakening" -> Conf.Obj("debug" -> Conf.Str("nope")))
    )
    assert(new TypeclassWeakening().withConfiguration(bad).isNotOk)
    assert(new TypelevelPurrism().withConfiguration(bad).isNotOk)
    val good = Configuration().withConf(
      Conf.Obj("TypeclassWeakening" -> Conf.Obj("cacheDir" -> Conf.Str("")))
    )
    assert(new TypeclassWeakening().withConfiguration(good).isOk)
    assert(new TypelevelPurrism().withConfiguration(good).isOk)
  }
}
