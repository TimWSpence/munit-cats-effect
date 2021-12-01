/*
 * Copyright 2021 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package munit

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, SyncIO}
import cats.syntax.all._

import scala.concurrent.{ExecutionContext, Future}

abstract class CatsEffectSuite
    extends FunSuite
    with CatsEffectSuitePlatform
    with CatsEffectAssertions
    with CatsEffectFixtures
    with CatsEffectFunFixtures {

  implicit def munitIoRuntime: IORuntime = IORuntime.global

  override implicit val munitExecutionContext: ExecutionContext = munitIoRuntime.compute

  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms ++ List(munitIOTransform, munitSyncIOTransform)

  //  "fail",
  //     { t =>
  //       if (t.tags(Fail)) {
  //         t.withBodyMap(
  //           _.transformCompat {
  //             case Success(value) =>
  //               Failure(
  //                 throw new FailException(
  //                   munitLines.formatLine(
  //                     t.location,
  //                     "expected failure but test passed"
  //                   ),
  //                   t.location
  //                 )
  //               )
  //             case Failure(exception) =>
  //               Success(())
  //           }(munitExecutionContext)
  //         )
  //       } else {
  //         t
  //       }
  //     }
  //   )

  // def munitFlakyOK: Boolean = "true" == System.getenv("MUNIT_FLAKY_OK")
  // final def munitFlakyTransform: TestTransform =
  //   new TestTransform(
  //     "flaky",
  //     { t =>
  //       if (t.tags(Flaky)) {
  //         t.withBodyMap(_.transformCompat {
  //           case Success(value) => Success(value)
  //           case Failure(exception) =>
  //             if (munitFlakyOK) {
  //               Success(new TestValues.FlakyFailure(exception))
  //             } else {
  //               throw exception
  //             }
  //         }(munitExecutionContext))
  //       } else {
  //         t
  //       }
  //     }

  private val munitIOTransform: ValueTransform =
    new ValueTransform(
      "IO",
      { case e: IO[_] => checkNestingIO(e).unsafeToFuture() }
    )

  private val munitSyncIOTransform: ValueTransform =
    new ValueTransform(
      "SyncIO",
      { case e: SyncIO[_] => Future(checkNestingSyncIO(e).unsafeRunSync())(munitExecutionContext) }
    )

  // MUnit works by automatically chaining value transforms of shape `Any => Future[Any]`,
  // and we rely on this behavior to chain our `IO ~> Future` transform into the rest of MUnit.
  //
  // Unfortunately, this has an unforeseen consequence in CatsEffectSuite:
  // if you return `IO[IO[A]]` by accident, for example by using `map` instead of `flatMap`,
  // MUnit will execute both the inner and outer `IO` by applying our `IO` transform twice.
  //
  // This breaks the `IO` mental model, and can lead to very surprising behaviour, e.g see:
  //  https://github.com/typelevel/munit-cats-effect/issues/159.
  //
  // This method checks for such a case, and fails the test with an actionable message.
  private def checkNestingIO(fa: IO[_]): IO[Any] = {
    def err(msg: String) = IO.raiseError[Any](new Exception(msg))

    fa.flatMap {
      case _: IO[_] =>
        err(
          "your test returns an `IO[IO[_]]`, which means the inner `IO` will not execute." ++
            "Call `.flatten` if you want it to execute, or `.void` if you want to discard it"
        )
      case _: SyncIO[_] =>
        err(
          "your test returns an `IO[SyncIO[_]]`, which means the inner `SyncIO` will not execute." ++
            "Call `.flatMap(_.to[IO]) if you want it to execute, or `.void` if you want to discard it"
        )
      case _: Future[_] =>
        err(
          "your test returns an `IO[Future[_]]`, which means the inner `Future` might not execute" ++
            "Surround it with `IO.fromFuture` if you want it to execute, or call `.void` if you want to discard it"
        )
      case v => v.pure[IO]
    }
  }

  // same as above, but for SyncIO
  private def checkNestingSyncIO(fa: SyncIO[_]): SyncIO[Any] = {
    def err(msg: String) = SyncIO.raiseError[Any](new Exception(msg))

    fa.flatMap {
      case _: IO[_] =>
        err(
          "your test returns a `SyncIO[IO[_]]`, which means the inner `IO` will not execute." ++
            "Call `.to[IO].flatten` if you want it to execute, or `.void` if you want to discard it"
        )
      case _: SyncIO[_] =>
        err(
          "your test returns a `SyncIO[SyncIO[_]]`, which means the inner `SyncIO` will not execute." ++
            "Call `.flatten` if you want it to execute, or `.void` if you want to discard it"
        )
      case _: Future[_] =>
        err(
          "your test returns a `SyncIO[Future[_]]`, which means the inner `Future` might not execute." ++
            "Change it to `IO.fromFuture(yourTest.to[IO])` if you want it to execute, or call `.void` if you want to discard it"
        )
      case v => v.pure[SyncIO]
    }
  }
}

object CatsEffectSuite {
  private[munit] type Deferred[F[_], A] = cats.effect.kernel.Deferred[F, A]
  private[munit] val Deferred = cats.effect.kernel.Deferred

}
