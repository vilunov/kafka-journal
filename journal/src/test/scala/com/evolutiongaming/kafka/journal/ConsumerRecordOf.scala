package com.evolutiongaming.kafka.journal

import cats.implicits._
import cats.data.{NonEmptyList => Nel}
import com.evolutiongaming.kafka.journal.Conversion.implicits._
import com.evolutiongaming.kafka.journal.KafkaConversions._
import com.evolutiongaming.skafka.consumer.{ConsumerRecord, ConsumerRecords, WithSize}
import com.evolutiongaming.skafka.producer.ProducerRecord
import com.evolutiongaming.skafka.{Offset, TimestampAndType, TimestampType, TopicPartition}
import scodec.bits.ByteVector

import scala.util.Try

object ConsumerRecordOf {

  def apply(
    action: Action,
    topicPartition: TopicPartition,
    offset: Offset)(implicit
    fromAttempt: FromAttempt[Try]
  ): ConsumerRecord[Id, ByteVector] = {
    
    val producerRecord = action.convert[Try, ProducerRecord[Id, ByteVector]].get
    val timestampAndType = TimestampAndType(action.timestamp, TimestampType.Create)
    ConsumerRecord[Id, ByteVector](
      topicPartition = topicPartition,
      offset = offset,
      timestampAndType = Some(timestampAndType),
      key = producerRecord.key.map(bytes => WithSize(bytes, bytes.length)),
      value = producerRecord.value.map(bytes => WithSize(bytes, bytes.length.toInt)),
      headers = producerRecord.headers)
  }
}

object ConsumerRecordsOf {

  def apply[K, V](records: List[ConsumerRecord[K, V]]): ConsumerRecords[K, V] = {
    val records1 = for {
      (topicPartition, records) <- records.groupBy(_.topicPartition)
      records                   <- Nel.fromList(records)
    } yield {
      (topicPartition, records)
    }
    ConsumerRecords(records1)
  }
}
