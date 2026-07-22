package fix

import cats.data.Kleisli
import cats.syntax.apply._
import cats.syntax.arrow._
import cats.syntax.choice._
import cats.syntax.compose._
import cats.syntax.flatMap._
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
    forAll { (input: Int, firstValue: Int) =>
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

  property(
    "PreferArrow arity-3 fan-out matches the for-comprehension, including the tuple nesting"
  ) {
    forAll {
      (
          input: Int,
          aOut: Map[Int, Option[Int]],
          aFallback: Option[Int],
          bOut: Map[Int, Option[Int]],
          bFallback: Option[Int],
          cOut: Map[Int, Option[Int]],
          cFallback: Option[Int]
      ) =>
        val ka: Kleisli[Option, Int, Int] =
          Kleisli(v => aOut.getOrElse(v, aFallback))
        val kb: Kleisli[Option, Int, Int] =
          Kleisli(v => bOut.getOrElse(v, bFallback))
        val kc: Kleisli[Option, Int, Int] =
          Kleisli(v => cOut.getOrElse(v, cFallback))

        val original: Option[(Int, Int, Int)] =
          for {
            a <- ka.run(input)
            b <- kb.run(input)
            c <- kc.run(input)
          } yield (a, b, c)

        // Exactly what the rule emits: `ka &&& kb &&& kc` nests as `((a, b), c)`,
        // and the trailing `.map` flattens it back to `(a, b, c)`.
        val refactored: Option[(Int, Int, Int)] =
          (ka &&& kb &&& kc).map { case ((a, b), c) => (a, b, c) }.run(input)

        assertEquals(refactored, original)
    }
  }

  property(
    "PreferArrow arity-3 fan-out short-circuits on the middle failure"
  ) {
    forAll { (input: Int) =>
      val ka: Kleisli[Option, Int, Int] = Kleisli(_ => Some(1))
      val kb: Kleisli[Option, Int, Int] = Kleisli(_ => None)
      val kc: Kleisli[Option, Int, Int] = Kleisli(_ => Some(3))

      val original: Option[(Int, Int, Int)] =
        for {
          a <- ka.run(input)
          b <- kb.run(input)
          c <- kc.run(input)
        } yield (a, b, c)
      val refactored: Option[(Int, Int, Int)] =
        (ka &&& kb &&& kc).map { case ((a, b), c) => (a, b, c) }.run(input)

      assertEquals(refactored, original)
      assertEquals(refactored, None)
    }
  }

  property(
    "PreferArrow aggressive: ask &&& liftK fan-out matches the input-capturing for"
  ) {
    forAll {
      (
          input: Int,
          aOut: Map[Int, Option[Int]],
          aFallback: Option[Int],
          bOut: Map[Int, Option[String]],
          bFallback: Option[String]
      ) =>
        // Plain effectful functions of the input -- not Kleislis -- exactly the
        // shape aggressive mode lifts. The `yield` captures the input too, so
        // the point-free form must retain it with a leading `Kleisli.ask`.
        def effA(i: Int): Option[Int] = aOut.getOrElse(i, aFallback)
        def effB(i: Int): Option[String] = bOut.getOrElse(i, bFallback)

        val original: Option[(Int, Int, String)] =
          for {
            a <- effA(input)
            b <- effB(input)
          } yield (input, a, b)

        // Exactly what aggressive mode emits: each generator lifted into a
        // `Kleisli`, fanned out with `&&&`, the input kept by `Kleisli.ask`,
        // and the nested tuple destructured back in a trailing `.map`.
        val refactored: Option[(Int, Int, String)] =
          (Kleisli.ask[Option, Int]
            &&& Kleisli((i: Int) => effA(i))
            &&& Kleisli((i: Int) => effB(i)))
            .map { case ((i, a), b) => (i, a, b) }
            .run(input)

        assertEquals(refactored, original)
    }
  }

  property(
    "PreferArrow aggressive: ask &&& liftK fan-out short-circuits on the first failure"
  ) {
    forAll { (input: Int) =>
      def effA(i: Int): Option[Int] = None
      def effB(i: Int): Option[String] = Some(i.toString)

      val original: Option[(Int, Int, String)] =
        for {
          a <- effA(input)
          b <- effB(input)
        } yield (input, a, b)
      val refactored: Option[(Int, Int, String)] =
        (Kleisli.ask[Option, Int]
          &&& Kleisli((i: Int) => effA(i))
          &&& Kleisli((i: Int) => effB(i)))
          .map { case ((i, a), b) => (i, a, b) }
          .run(input)

      assertEquals(refactored, original)
      assertEquals(refactored, None)
    }
  }

  property(
    "PreferArrow aggressive: an arm that ignores the input still matches the for, with no reshape at arity 2"
  ) {
    forAll {
      (
          input: Int,
          sizes: Map[Int, Option[Int]],
          sizeFallback: Option[Int],
          total: Option[Int]
      ) =>
        def size(i: Int): Option[Int] = sizes.getOrElse(i, sizeFallback)

        val original: Option[(Int, Int)] =
          for {
            s <- size(input)
            t <- total
          } yield (s, t)

        // Exactly what aggressive mode emits: the second arm ignores the input,
        // and `yield (s, t)` is the arms in order, so no trailing `.map`.
        val refactored: Option[(Int, Int)] =
          (Kleisli((i: Int) => size(i)) &&& Kleisli((_: Int) => total))
            .run(input)

        assertEquals(refactored, original)
    }
  }

  property(
    "PreferArrow Pattern E: Either branching matches k >>> (onLeft ||| onRight)"
  ) {
    forAll {
      (
          input: Int,
          classifyOut: Map[Int, Option[Either[Int, String]]],
          classifyFallback: Option[Either[Int, String]],
          leftOut: Map[Int, Option[Boolean]],
          leftFallback: Option[Boolean],
          rightOut: Map[String, Option[Boolean]],
          rightFallback: Option[Boolean]
      ) =>
        val classify: Kleisli[Option, Int, Either[Int, String]] =
          Kleisli(v => classifyOut.getOrElse(v, classifyFallback))
        val onLeft: Kleisli[Option, Int, Boolean] =
          Kleisli(v => leftOut.getOrElse(v, leftFallback))
        val onRight: Kleisli[Option, String, Boolean] =
          Kleisli(v => rightOut.getOrElse(v, rightFallback))

        val original: Option[Boolean] =
          classify.run(input).flatMap {
            case Left(code)  => onLeft.run(code)
            case Right(name) => onRight.run(name)
          }
        val refactored: Option[Boolean] =
          (classify >>> (onLeft ||| onRight)).run(input)

        assertEquals(refactored, original)
    }
  }

  property(
    "PreferArrow aggressive: a leading discard generator matches announce *> work"
  ) {
    forAll {
      (
          input: Int,
          announced: Map[Int, Option[Unit]],
          announceFallback: Option[Unit],
          sizes: Map[Int, Option[Int]],
          sizeFallback: Option[Int]
      ) =>
        def announce(i: Int): Option[Unit] =
          announced.getOrElse(i, announceFallback)
        def size(i: Int): Option[Int] = sizes.getOrElse(i, sizeFallback)

        val original: Option[Int] =
          for {
            _ <- announce(input)
            s <- size(input)
          } yield s

        // What aggressive mode emits for a leading `_ <- ...`: both sides are
        // fed the same input, the left result is dropped, and short-circuiting
        // still happens on the left first -- which is why a `None` from
        // `announce` must suppress `size` here exactly as it does in the `for`.
        val refactored: Option[Int] =
          (Kleisli((i: Int) => announce(i)) *> Kleisli((i: Int) => size(i)))
            .run(input)

        assertEquals(refactored, original)
    }
  }

  property(
    "PreferArrow aggressive: a trailing discard generator matches (a &&& b).flatTap(...)"
  ) {
    forAll {
      (
          input: Int,
          sizes: Map[Int, Option[Int]],
          sizeFallback: Option[Int],
          actives: Map[Int, Option[Boolean]],
          activeFallback: Option[Boolean],
          records: Map[Int, Option[Unit]],
          recordFallback: Option[Unit]
      ) =>
        def size(i: Int): Option[Int] = sizes.getOrElse(i, sizeFallback)
        def active(i: Int): Option[Boolean] =
          actives.getOrElse(i, activeFallback)
        def record(s: Int): Option[Unit] = records.getOrElse(s, recordFallback)

        val original: Option[(Int, Boolean)] =
          for {
            s <- size(input)
            a <- active(input)
            _ <- record(s)
          } yield (s, a)

        // The tap runs last and keeps the fanned-out pair, so a `None` from
        // `record` still fails the whole arrow -- `flatTap` discards the
        // *value*, not the effect.
        val refactored: Option[(Int, Boolean)] =
          (Kleisli((i: Int) => size(i)) &&& Kleisli((i: Int) => active(i)))
            .flatTap { case (s, _) =>
              Kleisli.liftF[Option, Int, Unit](record(s))
            }
            .run(input)

        assertEquals(refactored, original)
    }
  }
}
