package fix

import cats.data.Kleisli
import cats.syntax.arrow._
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

  property(
    "PreferArrow Pattern A: 3-step chain rewrite preserves run behavior for Option"
  ) {
    forAll {
      (
          input: Int,
          firstOutputs: Map[Int, Option[Int]],
          firstFallback: Option[Int],
          secondOutputs: Map[Int, Option[Int]],
          secondFallback: Option[Int],
          thirdOutputs: Map[Int, Option[String]],
          thirdFallback: Option[String]
      ) =>
        val step1: Kleisli[Option, Int, Int] =
          Kleisli(value => firstOutputs.getOrElse(value, firstFallback))
        val step2: Kleisli[Option, Int, Int] =
          Kleisli(value => secondOutputs.getOrElse(value, secondFallback))
        val step3: Kleisli[Option, Int, String] =
          Kleisli(value => thirdOutputs.getOrElse(value, thirdFallback))

        val original: Option[String] =
          step1.run(input).flatMap { v1 =>
            step2.run(v1).flatMap(v2 => step3.run(v2))
          }
        val refactored: Option[String] =
          step1.andThen(step2).andThen(step3).run(input)

        assertEquals(refactored, original)
    }
  }

  property(
    "PreferArrow Pattern A: preserves short-circuiting on None in middle step"
  ) {
    forAll {
      (input: Int, firstValue: Int) =>
        val step1: Kleisli[Option, Int, Int] =
          Kleisli(_ => Some(firstValue))
        val step2: Kleisli[Option, Int, Int] =
          Kleisli(_ => None)
        val step3: Kleisli[Option, Int, String] =
          Kleisli(value => Some(value.toString))

        val original: Option[String] =
          step1.run(input).flatMap { v1 =>
            step2.run(v1).flatMap(v2 => step3.run(v2))
          }
        val refactored: Option[String] =
          step1.andThen(step2).andThen(step3).run(input)

        assertEquals(refactored, original)
        assertEquals(refactored, None)
    }
  }

  property(
    "PreferArrow Pattern B: run(x).map(f) rewrite preserves behavior for Option"
  ) {
    forAll {
      (input: Int, outputs: Map[Int, Option[Int]], fallback: Option[Int]) =>
        val kleisli: Kleisli[Option, Int, Int] =
          Kleisli(value => outputs.getOrElse(value, fallback))

        val original: Option[String] =
          kleisli.run(input).map(_.toString)
        val refactored: Option[String] =
          kleisli.map(_.toString).run(input)

        assertEquals(refactored, original)
    }
  }

  property(
    "PreferArrow Pattern B: map preserves None case in Option"
  ) {
    forAll { (input: Int) =>
      val kleisli: Kleisli[Option, Int, Int] =
        Kleisli(_ => None)

      val original: Option[String] =
        kleisli.run(input).map(_.toString)
      val refactored: Option[String] =
        kleisli.map(_.toString).run(input)

      assertEquals(refactored, original)
      assertEquals(refactored, None)
    }
  }

  property(
    "PreferArrow Pattern C: fan-out rewrite preserves run behavior for Option"
  ) {
    forAll {
      (
          input: Int,
          firstOutputs: Map[Int, Option[Int]],
          firstFallback: Option[Int],
          secondOutputs: Map[Int, Option[String]],
          secondFallback: Option[String]
      ) =>
        val first: Kleisli[Option, Int, Int] =
          Kleisli(value => firstOutputs.getOrElse(value, firstFallback))
        val second: Kleisli[Option, Int, String] =
          Kleisli(value => secondOutputs.getOrElse(value, secondFallback))

        val original: Option[(Int, String)] =
          first.run(input).flatMap(v1 => second.run(input).map(v2 => (v1, v2)))
        val refactored: Option[(Int, String)] =
          (first &&& second).run(input)

        assertEquals(refactored, original)
    }
  }

  property(
    "PreferArrow Pattern C: fan-out short-circuits on first failure"
  ) {
    forAll { (input: Int) =>
      val first: Kleisli[Option, Int, Int] =
        Kleisli(_ => None)
      val second: Kleisli[Option, Int, String] =
        Kleisli(_ => Some("value"))

      val original: Option[(Int, String)] =
        first.run(input).flatMap(v1 => second.run(input).map(v2 => (v1, v2)))
      val refactored: Option[(Int, String)] =
        (first &&& second).run(input)

      assertEquals(refactored, original)
      assertEquals(refactored, None)
    }
  }
}
