package com.softwaremill.react.kafka

import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import com.softwaremill.react.kafka.KafkaActorPublisher.Poll
import kafka.consumer.{ConsumerTimeoutException, KafkaConsumer}

import scala.annotation.tailrec
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

private[kafka] class KafkaActorPublisher[T](consumer: KafkaConsumer[T]) extends ActorPublisher[T] {

  val iterator = consumer.iterator()

  override def receive = {
    case ActorPublisherMessage.Request(_) | Poll => readDemandedItems()
    case ActorPublisherMessage.Cancel | ActorPublisherMessage.SubscriptionTimeoutExceeded =>
      cleanupResources()
      context.stop(self)
  }

  private def demand_? : Boolean = totalDemand > 0

  override def postStop(): Unit = {
    cleanupResources()
    super.postStop()
  }

  private def tryReadingSingleElement(): Try[Option[T]] = {
    Try {
      val bytes = if (iterator.hasNext() && demand_?) Option(iterator.next().message()) else None
      bytes.map(consumer.props.decoder.fromBytes)
    } recover {
      // We handle timeout exceptions as normal 'end of the queue' cases
      case _: ConsumerTimeoutException => None
    }
  }

  @tailrec
  private def readDemandedItems(): Unit = {
    tryReadingSingleElement() match {
      case Success(None) =>
        if (demand_?) self ! Poll
      case Success(Some(element)) =>
        onNext(element)
        if (demand_?) readDemandedItems()
      case Failure(ex) => onError(ex)
    }
  }

  private def cleanupResources(): Unit = {
    consumer.close()
  }
}

private[kafka] object KafkaActorPublisher {
  case object Poll
}