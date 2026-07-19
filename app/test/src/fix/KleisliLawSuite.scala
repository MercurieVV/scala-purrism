package fix

import cats.data.Kleisli
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

final class KleisliLawSuite extends ScalaCheckSuite {
  property("simple Kleisli apply rewrite preserves run behavior for Option") {
    forAll {
      (
          input: Int,
          outputs: Map[Int, Option[String]],
          fallback: Option[String]
      ) =>
        def original(value: Int): Option[String] =
          outputs.getOrElse(value, fallback)

        val refactored: Kleisli[Option, Int, String] =
          Kleisli.apply { value =>
            original(value)
          }

        assertEquals(refactored.run(input), original(input))
    }
  }

  property(
    "direct Kleisli composition rewrite preserves flatMap behavior for Option"
  ) {
    forAll {
      (
          input: Int,
          firstOutputs: Map[Int, Option[Int]],
          firstFallback: Option[Int],
          secondOutputs: Map[Int, Option[String]],
          secondFallback: Option[String]
      ) =>
        val fetch: Kleisli[Option, Int, Int] =
          Kleisli(value => firstOutputs.getOrElse(value, firstFallback))
        val profile: Kleisli[Option, Int, String] =
          Kleisli(value => secondOutputs.getOrElse(value, secondFallback))

        val original: Option[String] =
          fetch.run(input).flatMap(user => profile.run(user))
        val refactored: Option[String] =
          fetch.andThen(profile).run(input)

        assertEquals(refactored, original)
    }
  }

  property(
    "explicit Kleisli run composition rewrite preserves flatMap behavior for Option"
  ) {
    forAll {
      (
          input: Int,
          firstOutputs: Map[Int, Option[Int]],
          firstFallback: Option[Int],
          secondOutputs: Map[Int, Option[String]],
          secondFallback: Option[String]
      ) =>
        val fetch: Kleisli[Option, Int, Int] =
          Kleisli(value => firstOutputs.getOrElse(value, firstFallback))
        val profile: Kleisli[Option, Int, String] =
          Kleisli(value => secondOutputs.getOrElse(value, secondFallback))

        val original: Option[String] =
          fetch.run(input).flatMap(user => profile.run(user))
        val refactored: Option[String] =
          fetch.andThen(profile).run(input)

        assertEquals(refactored, original)
    }
  }

  property(
    "split Kleisli body rewrite preserves setup before final Kleisli call"
  ) {
    forAll {
      (
          input: Int,
          offset: Int,
          outputs: Map[Int, Option[String]],
          fallback: Option[String]
      ) =>
        val profile: Kleisli[Option, Int, String] =
          Kleisli(value => outputs.getOrElse(value, fallback))

        val original: Kleisli[Option, Int, String] =
          Kleisli.apply { (value: Int) =>
            val user = value + offset
            profile.run(user)
          }
        val refactored: Kleisli[Option, Int, String] =
          profile.local { (value: Int) =>
            val user = value + offset
            user
          }

        assertEquals(refactored.run(input), original.run(input))
    }
  }
}
