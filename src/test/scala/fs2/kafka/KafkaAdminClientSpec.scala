package fs2.kafka

import cats.effect.IO
import cats.implicits._
import org.apache.kafka.common.TopicPartition

final class KafkaAdminClientSpec extends BaseKafkaSpec {
  describe("KafkaAdminClient") {
    it("should support all defined functionality") {
      withKafka { (config, topic) =>
        createCustomTopic(topic, partitions = 3)
        val produced = (0 until 100).map(n => s"key-$n" -> s"value->$n")
        publishToKafka(topic, produced)

        consumerStream[IO]
          .using(consumerSettings(config))
          .evalTap(_.subscribe(topic.r))
          .flatMap(_.stream)
          .take(produced.size.toLong)
          .map(_.committableOffset)
          .to(commitBatch)
          .compile
          .lastOrError
          .unsafeRunSync

        adminClientResource[IO](adminClientSettings(config)).use { adminClient =>
          for {
            consumerGroupIds <- adminClient.listConsumerGroups.groupIds
            _ <- IO(assert(consumerGroupIds.size == 1))
            consumerGroupListings <- adminClient.listConsumerGroups.listings
            _ <- IO(assert(consumerGroupListings.size == 1))
            describedConsumerGroups <- adminClient.describeConsumerGroups(consumerGroupIds)
            _ <- IO(assert(describedConsumerGroups.size == 1))
            consumerGroupOffsets <- consumerGroupIds.parTraverse { groupId =>
              adminClient
                .listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata
                .map((groupId, _))
            }
            _ <- IO(assert(consumerGroupOffsets.size == 1))
            consumerGroupOffsetsPartitions <- consumerGroupIds.parTraverse { groupId =>
              adminClient
                .listConsumerGroupOffsets(groupId)
                .forPartitions(List.empty[TopicPartition])
                .partitionsToOffsetAndMetadata
                .map((groupId, _))
            }
            _ <- IO(assert(consumerGroupOffsetsPartitions.size == 1))
            topicNames <- adminClient.listTopics.names
            _ <- IO(assert(topicNames.size == 1))
            topicListings <- adminClient.listTopics.listings
            _ <- IO(assert(topicListings.size == 1))
            topicNamesToListings <- adminClient.listTopics.namesToListings
            _ <- IO(assert(topicNamesToListings.size == 1))
            topicNamesInternal <- adminClient.listTopics.includeInternal.names
            _ <- IO(assert(topicNamesInternal.size == 2))
            topicListingsInternal <- adminClient.listTopics.includeInternal.listings
            _ <- IO(assert(topicListingsInternal.size == 2))
            topicNamesToListingsInternal <- adminClient.listTopics.includeInternal.namesToListings
            _ <- IO(assert(topicNamesToListingsInternal.size == 2))
            describedTopics <- adminClient.describeTopics(topicNames.toList)
            _ <- IO(assert(describedTopics.size == 1))
            _ <- IO {
              adminClient.toString should startWith("KafkaAdminClient$")
            }
            _ <- IO {
              adminClient.listTopics.toString should startWith("ListTopics$")
            }
            _ <- IO {
              adminClient.listTopics.includeInternal.toString should
                startWith("ListTopicsIncludeInternal$")
            }
            _ <- IO {
              adminClient.listConsumerGroups.toString should
                startWith("ListConsumerGroups$")
            }
            _ <- IO {
              adminClient
                .listConsumerGroupOffsets("group")
                .toString shouldBe "ListConsumerGroupOffsets(groupId = group)"
            }
            _ <- IO {
              adminClient
                .listConsumerGroupOffsets("group")
                .forPartitions(List(new TopicPartition("topic", 0)))
                .toString shouldBe "ListConsumerGroupOffsetsForPartitions(groupId = group, partitions = List(topic-0))"
            }
          } yield ()
        }.unsafeRunSync
      }
    }
  }
}
