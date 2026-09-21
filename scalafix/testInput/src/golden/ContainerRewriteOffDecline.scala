/*
rules = [PreferPolymorphicCollections]

PreferPolymorphicCollections.rewrite = false
 */
package golden

final class ContainerRewriteOffDecline {
  private def names(users: List[String]): List[String] = // assert: PreferPolymorphicCollections.rewrite-off
    users.map(user => user.toUpperCase)
}
