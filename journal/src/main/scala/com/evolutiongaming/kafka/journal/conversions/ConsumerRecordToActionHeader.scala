package com.evolutiongaming.kafka.journal.conversions

import cats.implicits._
import com.evolutiongaming.catshelper.ApplicativeThrowable
import com.evolutiongaming.kafka.journal._
import scodec.bits.ByteVector

trait ConsumerRecordToActionHeader[F[_]] {

  def apply(consumerRecord: ConsRecord): Option[F[ActionHeader]]
}

object ConsumerRecordToActionHeader {

  implicit def apply[F[_] : ApplicativeThrowable](implicit
    fromBytes: FromBytes[F, ActionHeader]
  ): ConsumerRecordToActionHeader[F] = {

    consumerRecord: ConsRecord => {
      for {
        header <- consumerRecord.headers.find { _.key == ActionHeader.key }
      } yield {
        val byteVector = ByteVector.view(header.value)
        val actionHeader = fromBytes(byteVector)
        actionHeader.handleErrorWith { cause =>
          JournalError(s"ConsumerRecordToActionHeader failed for $consumerRecord: $cause", cause.some).raiseError[F, ActionHeader]
        }
      }
    }
  }
}
