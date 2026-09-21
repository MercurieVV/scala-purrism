
package golden

final class ContainerNoCapabilityDecline {
  // The parameter never touches the container as a collection -- returned
  // verbatim, no map/filter/fold -- so the solver's required-op set is empty
  // and there is no Cats capability to widen to.
  private def identity(xs: List[Int]): List[Int] = xs 
}
