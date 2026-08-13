/*
rules = [PreferEffectIdioms]
 */
package golden

import java.util.concurrent.atomic.AtomicReference

final class IdiomMutableReference {
  def counter(): AtomicReference[Int] =
    new AtomicReference(0) // assert: PreferEffectIdioms
}
