/*
 * Copyright 2010 LinkedIn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package kafka.producer.async

import collection.mutable.HashMap
import collection.mutable.Map
import org.apache.log4j.Logger
import kafka.api.ProducerRequest
import kafka.serializer.Encoder
import java.util.Properties
import kafka.message.{NoCompressionCodec, ByteBufferMessageSet}
import kafka.producer.{ProducerConfig, SyncProducerConfigShared, SyncProducerConfig, SyncProducer}

private[kafka] class DefaultEventHandler[T](val config: ProducerConfig,
                                            val cbkHandler: CallbackHandler[T]) extends EventHandler[T] {

  private val logger = Logger.getLogger(classOf[DefaultEventHandler[T]])

  override def init(props: Properties) { }

  override def handle(events: Seq[QueueItem[T]], syncProducer: SyncProducer, serializer: Encoder[T]) {
    var processedEvents = events
    if(cbkHandler != null)
      processedEvents = cbkHandler.beforeSendingData(events)
    send(serialize(collate(processedEvents), serializer), syncProducer)
  }

  private def send(messagesPerTopic: Map[(String, Int), ByteBufferMessageSet], syncProducer: SyncProducer) {
    if(messagesPerTopic.size > 0) {
      val requests = messagesPerTopic.map(f => new ProducerRequest(f._1._1, f._1._2, f._2)).toArray
      syncProducer.multiSend(requests)
      if(logger.isTraceEnabled)
        logger.trace("kafka producer sent messages for topics " + messagesPerTopic)
    }
  }

  private def serialize(eventsPerTopic: Map[(String,Int), Seq[T]],
                        serializer: Encoder[T]): Map[(String, Int), ByteBufferMessageSet] = {
    import scala.collection.JavaConversions._
    val eventsPerTopicMap = eventsPerTopic.map(e => ((e._1._1, e._1._2) , e._2.map(l => serializer.toMessage(l))))
    val topicsAndPartitions = eventsPerTopic.map(e => e._1)
    /** enforce the compressed.topics config here.
     *  If the compression codec is anything other than NoCompressionCodec,
     *    Enable compression only for specified topics if any
     *    If the list of compressed topics is empty, then enable the specified compression codec for all topics
     *  If the compression codec is NoCompressionCodec, compression is disabled for all topics
     */
    val messages = eventsPerTopicMap.map(e => {
      config.compressionCodec match {
        case NoCompressionCodec =>
          if(logger.isDebugEnabled)
            logger.debug("Sending %d messages with no compression".format(e._2.size))
          new ByteBufferMessageSet(NoCompressionCodec, e._2: _*)
        case _ =>
          config.compressedTopics.size match {
            case 0 =>
              if(logger.isDebugEnabled)
                logger.debug("Sending %d messages with compression %d".format(e._2.size, config.compressionCodec.codec))
              new ByteBufferMessageSet(config.compressionCodec, e._2: _*)
            case _ =>
              if(config.compressedTopics.contains(e._1._1)) {
                if(logger.isDebugEnabled)
                  logger.debug("Sending %d messages with compression %d".format(e._2.size, config.compressionCodec.codec))
                new ByteBufferMessageSet(config.compressionCodec, e._2: _*)
              }
              else {
                if(logger.isDebugEnabled)
                  logger.debug("Sending %d messages with no compression as %s is not in compressed.topics - %s"
                    .format(e._2.size, e._1._1, config.compressedTopics.toString))
                new ByteBufferMessageSet(NoCompressionCodec, e._2: _*)
              }
          }
      }
    })
    topicsAndPartitions.zip(messages)
  }

  private def collate(events: Seq[QueueItem[T]]): Map[(String,Int), Seq[T]] = {
    val collatedEvents = new HashMap[(String, Int), Seq[T]]
    val distinctTopics = events.map(e => e.getTopic).toSeq.distinct
    val distinctPartitions = events.map(e => e.getPartition).distinct

    var remainingEvents = events
    distinctTopics foreach { topic =>
      val topicEvents = remainingEvents partition (e => e.getTopic.equals(topic))
      remainingEvents = topicEvents._2
      distinctPartitions.foreach { p =>
        val topicPartitionEvents = topicEvents._1 partition (e => (e.getPartition == p))
        collatedEvents += ( (topic, p) -> topicPartitionEvents._1.map(q => q.getData).toSeq)
      }
    }
    collatedEvents
  }

  override def close = {
  }
}
