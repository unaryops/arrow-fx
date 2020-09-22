package arrow.fx.coroutines

import arrow.core.Either
import io.kotest.assertions.fail
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.coroutines.resume

class CancellableF : ArrowFxSpec(spec = {

  "cancelable works for immediate values" {
    checkAll(Arb.result(Arb.int())) { res ->
      Either.catch {
        cancellable<Int> { cont ->
          cont.resumeWith(res)
          CancelToken.unit
        }
      } shouldBe res.toEither()
    }
  }

  "cancelable gets canceled" {
    val cancelled = Promise<Boolean>()
    val latch = UnsafePromise<Unit>()

    val c = suspend {
      cancellable<Unit> {
        latch.complete(Result.success(Unit))
        CancelToken { cancelled.complete(true) }
      }
    }.startCoroutineCancellable(CancellableContinuation { })

    latch.join()
    c.invoke()

    cancelled.get() shouldBe true
  }

  "cancelableF works for immediate values" {
    checkAll(Arb.result(Arb.int())) { res ->
      Either.catch {
        cancellableF<Int> { cont ->
          cont.resumeWith(res)
          CancelToken.unit
        }
      } shouldBe res.toEither()
    }
  }

  "cancelableF works for async values" {
    checkAll(Arb.result(Arb.int())) { res ->
      Either.catch {
        cancellableF<Int> { cont ->
          Unit.suspend()
          cont.resumeWith(res)
          CancelToken.unit
        }
      } shouldBe res.toEither()
    }
  }

  "cancelableF can yield cancelable tasks" {
    checkAll(Arb.int()) { i ->
      val d = ConcurrentVar.empty<Int>()
      val latch = Promise<Unit>()
      val fiber = ForkConnected {
        cancellableF<Unit> { _ ->
          latch.complete(Unit)
          CancelToken { d.put(i) }
        }
      }

      latch.get()
      d.tryTake() shouldBe null
      fiber.cancel()
      d.take() shouldBe i
    }
  }

  "cancelableF executes generated task uninterruptedly" {
    checkAll(Arb.int()) { i ->
      val latch = Promise<Unit>()
      val start = Promise<Unit>()
      val done = Promise<Int>()

      val task = suspend {
        cancellableF<Unit> { cont ->
          latch.complete(Unit)
          start.get()
          cancelBoundary()
          cont.resumeWith(Result.success(Unit))
          done.complete(i)
          CancelToken.unit
        }
      }

      val p = UnsafePromise<Unit>()

      val cancel = task.startCoroutineCancellable(CancellableContinuation { r -> p.complete(r) })

      latch.get()

      ForkConnected { cancel.invoke() }

      // Let cancel schedule
      sleep(10.milliseconds)

      start.complete(Unit) // Continue cancellableF

      done.get() shouldBe i

      try {
        val res = p.join()
        fail("Should've exploded with cancellation event but found $res")
      } catch (e: CancellationException) {

      } catch (e: Throwable) {
        fail("Expected cancellation event but found $e")
      }
    }
  }
})
