/*
rules = [PreferOptionIdioms]
 */
package golden

final class IdiomThrowingLookup(config: Map[String, String]) {
  def lookup(key: String): String =
    config.getOrElse(key, throw new NoSuchElementException(key)) // assert: PreferOptionIdioms
}
