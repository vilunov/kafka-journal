package com.evolutiongaming.kafka.journal.replicator

import cats.data.{NonEmptyList => Nel, NonEmptyMap => Nem, NonEmptySet => Nes}
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.implicits._
import com.evolutiongaming.catshelper.{BracketThrowable, FromTry, Log}
import com.evolutiongaming.kafka.journal.util.ResourceOf
import com.evolutiongaming.kafka.journal.util.SkafkaHelper._
import com.evolutiongaming.kafka.journal.{ConsRecord, KafkaConsumerOf}
import com.evolutiongaming.skafka.consumer.{AutoOffsetReset, ConsumerConfig}
import com.evolutiongaming.skafka.{Offset, Partition, Topic}
import scodec.bits.ByteVector

import scala.concurrent.duration._

trait KafkaSingleton[F[_], A] {

  def get: F[Option[A]]
}

object KafkaSingleton {

  def of[F[_] : Concurrent : Timer : KafkaConsumerOf : FromTry, A](
    topic: Topic,
    groupId: String,
    singleton: Resource[F, A],
    consumerConfig: ConsumerConfig,
    log: Log[F]
  ): Resource[F, KafkaSingleton[F, A]] = {
    val consumerConfig1 = consumerConfig.copy(
      autoOffsetReset = AutoOffsetReset.Latest,
      groupId = groupId.some,
      autoCommit = false)
    val consumer = KafkaConsumerOf[F]
      .apply[String, ByteVector](consumerConfig1)
      .map { consumer => TopicConsumer(topic, 10.millis, TopicCommit.empty[F], consumer) }
    of(topic, consumer, singleton, log)
  }
  

  def of[F[_] : Concurrent : Timer, A](
    topic: Topic,
    consumer: Resource[F, TopicConsumer[F]],
    singleton: Resource[F, A],
    log: Log[F],
  ): Resource[F, KafkaSingleton[F, A]] = {

    def a(ref: Ref[F, Option[A]]) = for {
      a <- singleton
      _ <- Resource.liftF(ref.set(a.some))
      _ <- Resource(((), ref.set(none[A])).pure[F])
    } yield {}

    for {
      ref <- Resource.liftF(Ref[F].of(none[A]))
      _   <- ResourceOf(ConsumeTopic(topic, consumer, topicFlowOf(a(ref)), log).start)
    } yield {
      new KafkaSingleton[F, A] {
        def get = ref.get
      }
    }
  }


  def topicFlowOf[F[_] : Sync, A](a: Resource[F, Unit]): TopicFlowOf[F] = {
    _: Topic => {
      Resource
        .make {
          Ref[F].of(none[F[Unit]])
        } { ref =>
          ref
            .getAndSet(none[F[Unit]])
            .flatMap { _.foldMapM(identity) }
            .uncancelable
        }
        .map { ref => topicFlow(ref, a) }
    }
  }


  def topicFlow[F[_] : BracketThrowable](
    ref: Ref[F, Option[F[Unit]]],
    a: Resource[F, Unit]
  ): TopicFlow[F] = {
    val partition = Partition.min
    new TopicFlow[F] {

      def assign(partitions: Nes[Partition]) = {
        if (partitions contains_ partition) {
          a
            .allocated
            .flatMap { case (_, release) =>
              ref
                .set(release.some)
                .handleErrorWith { e => release *> e.raiseError[F, Unit] }
            }
            .uncancelable
        } else {
          ().pure[F]
        }
      }

      def apply(records: Nem[Partition, Nel[ConsRecord]]) = {
        Map.empty[Partition, Offset].pure[F]
      }

      def revoke(partitions: Nes[Partition]) = {
        if (partitions contains_ partition) {
          ref
            .getAndSet(none[F[Unit]])
            .flatMap { _.foldMapM(identity) }
            .uncancelable
        } else {
          ().pure[F]
        }
      }
    }
  }
}
