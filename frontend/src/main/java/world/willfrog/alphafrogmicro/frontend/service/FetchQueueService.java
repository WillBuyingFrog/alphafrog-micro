package world.willfrog.alphafrogmicro.frontend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class FetchQueueService {

    private static final String FETCH_TOPIC = "fetch_topic";
    private static final String FETCH_GROUP = "alphafrog-micro";
    private static final long ADMIN_TIMEOUT_SECONDS = 10;

    private final KafkaAdmin kafkaAdmin;

    public FetchQueueStats getFetchQueueStats() {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            List<TopicPartition> partitions = describeFetchPartitions(adminClient);
            if (partitions.isEmpty()) {
                return new FetchQueueStats(FETCH_TOPIC, FETCH_GROUP, 0L, 0);
            }

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                    listOffsets(adminClient, partitions, OffsetSpec.latest());
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> earliestOffsets =
                    listOffsets(adminClient, partitions, OffsetSpec.earliest());

            Map<TopicPartition, OffsetAndMetadata> committedOffsets = listCommittedOffsets(adminClient);

            long pending = 0;
            for (TopicPartition partition : partitions) {
                long end = endOffsets.get(partition).offset();
                long earliest = earliestOffsets.get(partition).offset();
                OffsetAndMetadata committed = committedOffsets.get(partition);
                long committedOffset = committed != null ? committed.offset() : earliest;
                long normalized = Math.max(committedOffset, earliest);
                long lag = end - normalized;
                if (lag > 0) {
                    pending += lag;
                }
            }

            return new FetchQueueStats(FETCH_TOPIC, FETCH_GROUP, pending, partitions.size());
        } catch (Exception e) {
            log.error("Failed to fetch queue stats", e);
            throw new RuntimeException("Failed to fetch queue stats", e);
        }
    }

    private List<TopicPartition> describeFetchPartitions(AdminClient adminClient) throws Exception {
        Map<String, org.apache.kafka.clients.admin.TopicDescription> descriptions =
                adminClient.describeTopics(Collections.singletonList(FETCH_TOPIC))
                        .all()
                        .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        org.apache.kafka.clients.admin.TopicDescription desc = descriptions.get(FETCH_TOPIC);
        if (desc == null) {
            return Collections.emptyList();
        }
        List<TopicPartition> partitions = new ArrayList<>();
        desc.partitions().forEach(partitionInfo ->
                partitions.add(new TopicPartition(FETCH_TOPIC, partitionInfo.partition())));
        return partitions;
    }

    private Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> listOffsets(
            AdminClient adminClient,
            List<TopicPartition> partitions,
            OffsetSpec offsetSpec
    ) throws Exception {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        for (TopicPartition partition : partitions) {
            request.put(partition, offsetSpec);
        }
        return adminClient.listOffsets(request)
                .all()
                .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private Map<TopicPartition, OffsetAndMetadata> listCommittedOffsets(AdminClient adminClient) {
        try {
            return adminClient.listConsumerGroupOffsets(FETCH_GROUP)
                    .partitionsToOffsetAndMetadata()
                    .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to fetch committed offsets for group {}", FETCH_GROUP, e);
            return Collections.emptyMap();
        }
    }

    public record FetchQueueStats(String topic, String groupId, long pending, int partitions) {
    }
}
