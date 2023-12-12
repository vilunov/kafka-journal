package com.evolutiongaming.kafka.journal

import cats.effect.kernel.Resource.ExitCase
import cats.{Applicative, Defer, Functor}
import cats.syntax.all._
import cats.effect.syntax.all._
import cats.effect.{Async, Clock, Deferred, Ref, Resource, Temporal}

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.duration.FiniteDuration

trait Damper[F[_]] {

  def acquire: F[Unit]

  def release: F[Unit]
}


object Damper {

  type Acquired = Int

  def of[F[_]: Async](delayOf: Acquired => FiniteDuration): F[Damper[F]] = {

    sealed trait State

    type Entry = F[Unit]
    type Delay = FiniteDuration
    type WakeUp = Deferred[F, Option[Entry]]

    object State {
      final case class Idle(acquired: Acquired) extends State
      final case class Busy(acquired: Acquired, entries: Queue[Entry], wakeUp: WakeUp) extends State
    }

    def delayOf1(acquired: Acquired) = delayOf(acquired.max(0))

    Ref[F]
      .of(State.Idle(acquired = 0): State)
      .map { ref =>

        type Result = (State, F[Either[(Entry, Delay, WakeUp), Unit]])

        def idle(acquired: Acquired, effect: F[Unit]): Result = {
          (
            State.Idle(acquired),
            effect.map { _.asRight[(Entry, Delay, WakeUp)] }
          )
        }

        @tailrec def idleOrBusy(acquired: Acquired, entries: Queue[Entry], effect: F[Unit]): Result = {
          entries.dequeueOption match {
            case Some((entry, entries)) =>
              val delay = delayOf1(acquired)
              if (delay.length == 0) {
                idleOrBusy(
                  acquired + 1,
                  entries,
                  effect.productR { entry })
              } else {
                val wakeUp = Deferred.unsafe[F, Option[Entry]]
                (
                  State.Busy(acquired, entries, wakeUp),
                  effect.as { (entry, delay, wakeUp).asLeft[Unit] }
                )
              }

            case None =>
              idle(acquired, effect)
          }
        }

        def start(entry: Entry, delay: Delay, wakeUp: WakeUp): F[Unit] = {
          (entry, delay, wakeUp)
            .tailRecM { case (entry, delay, wakeUp) =>

              def busy(delay: Delay, acquired: Acquired, entries: Queue[Entry]) = {
                val wakeUp = Deferred.unsafe[F, Option[Entry]]
                (
                  State.Busy(acquired, entries, wakeUp),
                  (entry, delay, wakeUp).asLeft[Unit].pure[F]
                )
              }

              def acquire = {
                ref.modify {
                  case state: State.Idle => idle(state.acquired + 1, entry)
                  case state: State.Busy => idleOrBusy(state.acquired + 1, state.entries, entry)
                }
              }

              for {
                start  <- Clock[F].realTime
                result <- wakeUp
                  .get
                  .race { Temporal[F].sleep(delay) }
                result <- result match {
                  case Left(Some(`entry`)) =>
                    acquire
                  case Left(_) =>
                    Clock[F]
                      .realTime
                      .flatMap { end =>
                        val slept = end - start
                        ref.modify {
                          case state: State.Idle =>
                            val acquired = state.acquired
                            val delay = delayOf1(acquired)
                            if (delay <= slept) {
                              idle(acquired + 1, entry)
                            } else {
                              busy(delay - slept, acquired, Queue.empty)
                            }
                          case state: State.Busy =>
                            val acquired = state.acquired
                            val delay = delayOf1(acquired)
                            if (delay <= slept) {
                              idleOrBusy(acquired + 1, state.entries, entry)
                            } else {
                              busy(delay - slept, acquired, state.entries)
                            }
                        }
                      }

                  case Right(()) =>
                    acquire
                }
                result <- result
              } yield result
            }
            .start
            .void
        }

        class Main
        new Main with Damper[F] {

          def acquire = {
            Deferred[F, Unit].flatMap { deferred =>
              val entry = deferred.complete(()).void

              def await(filter: Boolean) = {

                def wakeUp(state: State.Busy) = {
                  (
                    state.copy(acquired = state.acquired - 1),
                    state.wakeUp.complete1(entry.some)
                  )
                }

                deferred
                  .get
                  .onCancel {
                    ref
                      .modify {
                        case State.Idle(acquired) =>
                          (State.Idle(acquired = acquired - 1), ().pure[F])
                        case state: State.Busy    =>
                          if (filter) {
                            val entries = state.entries.filter(_ != entry)
                            if (state.entries.sizeCompare(entries) == 0) {
                              wakeUp(state)
                            } else {
                              (state.copy(entries = entries), ().pure[F])
                            }
                          } else {
                            wakeUp(state)
                          }
                      }
                      .flatten
                  }
              }

              ref
                .modify {
                  case state: State.Idle =>
                    val acquired = state.acquired
                    val delay = delayOf1(acquired)
                    if (delay.length == 0) {
                      (
                        state.copy(acquired = acquired + 1),
                        ().pure[F].pure[F]
                      )
                    } else {
                      val wakeUp = Deferred.unsafe[F, Option[Entry]]
                      (
                        State.Busy(acquired, Queue.empty, wakeUp),
                        Defer[F].defer { start(entry, delay, wakeUp).as { await(filter = false) } }
                      )
                    }
                  case state: State.Busy =>
                    (
                      state.copy(entries = state.entries.enqueue(entry)),
                      Defer[F].defer { await(filter = true).pure[F] }
                    )
                }
                .flatten
                .uncancelable
                .flatten
            }
          }

          def release = {
            ref
              .modify {
                case State.Idle(acquired) =>
                  (
                    State.Idle(acquired - 1),
                    ().pure[F]
                  )
                case state: State.Busy    =>
                  (
                    state.copy(acquired = state.acquired - 1),
                    state.wakeUp.complete1(none)
                  )
              }
              .flatten
              .uncancelable
          }
        }
      }
  }

  implicit class DamperOps[F[_]](val self: Damper[F]) extends AnyVal {

    def resource(implicit F: Functor[F]): Resource[F, Unit] = {
      Resource.applyFull { poll =>
        poll
          .apply { self.acquire }
          .map { a => (a, (_: ExitCase) => self.release) }
      }
    }
  }

  private implicit class DeferredOps[F[_], A](val self: Deferred[F, A]) extends AnyVal {
    def complete1(a: A)(implicit F: Applicative[F]): F[Unit] = {
      self
        .complete(a)
        .void // cats-effect-3
    }
  }
}