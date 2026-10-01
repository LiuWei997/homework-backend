package com.example.demo.messaging;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Component
public class RocketMqTaskMessagePublisher implements TaskMessagePublisher {
    private static final int MAX_BATCH_BODY_BYTES = 900 * 1024;
    private final DefaultMQProducer producer;
    private final ObjectMapper objectMapper;
    private final String topic;

    public RocketMqTaskMessagePublisher(DefaultMQProducer producer, ObjectMapper objectMapper,
                                        @Value("${rocketmq.topic:task-schedule-topic}") String topic) {
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void publishBatch(List<TaskTriggerEvent> events) {
        if (events.isEmpty()) return;
        int byteLimit = Math.min(MAX_BATCH_BODY_BYTES, producer.getMaxMessageSize() - 1024);
        List<Message> messages = new ArrayList<>(events.size());
        List<Integer> encodedSizes = new ArrayList<>(events.size());
        try {
            // Validate the complete batch before sending any sub-batch.
            for (TaskTriggerEvent event : events) {
                byte[] body = objectMapper.writeValueAsBytes(event);
                Message message = new Message(topic, "task-triggered", event.eventId(), body);
                int encodedBytes = MessageDecoder.encodeMessage(message).length;
                if (encodedBytes > byteLimit) {
                    throw new IllegalArgumentException("Task event exceeds encoded message limit of " + byteLimit + " bytes");
                }
                messages.add(message);
                encodedSizes.add(encodedBytes);
            }
            List<Message> batch = new ArrayList<>();
            int batchBytes = 0;
            for (int index = 0; index < messages.size(); index++) {
                int encodedBytes = encodedSizes.get(index);
                if (!batch.isEmpty() && batchBytes + encodedBytes > byteLimit) {
                    send(batch);
                    batch.clear();
                    batchBytes = 0;
                }
                batch.add(messages.get(index));
                batchBytes += encodedBytes;
            }
            if (!batch.isEmpty()) send(batch);
        } catch (JsonProcessingException exception) {
            throw new TaskPublishException("Could not serialize task trigger event", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TaskPublishException("RocketMQ send interrupted", exception);
        } catch (Exception exception) {
            throw new TaskPublishException("RocketMQ send failed", exception);
        }
    }

    private void send(List<Message> messages) throws Exception {
        SendResult result = messages.size() == 1
                ? producer.send(messages.getFirst())
                : producer.send(messages);
        if (result.getSendStatus() != SendStatus.SEND_OK) {
            throw new TaskPublishException("RocketMQ did not acknowledge the message batch: "
                    + result.getSendStatus(), new IllegalStateException(result.getSendStatus().name()));
        }
    }
}
