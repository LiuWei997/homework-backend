package com.example.demo.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RocketMqTaskMessagePublisherTests {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DefaultMQProducer producer = mock(DefaultMQProducer.class);

    private RocketMqTaskMessagePublisher publisher() {
        when(producer.getMaxMessageSize()).thenReturn(4 * 1024 * 1024);
        return new RocketMqTaskMessagePublisher(producer, mapper, "test-topic");
    }

    private TaskTriggerEvent event(String id, int bytes) {
        return new TaskTriggerEvent(id + ":1", "TASK_TRIGGERED", id, Instant.EPOCH, Instant.EPOCH,
                mapper.createObjectNode().put("text", "x".repeat(bytes)));
    }

    @Test
    void splitsByEncodedMessageSizeAndSendsAllEvents() throws Exception {
        RocketMqTaskMessagePublisher publisher = publisher();
        SendResult ack = new SendResult();
        ack.setSendStatus(SendStatus.SEND_OK);
        List<Integer> sends = new ArrayList<>();
        when(producer.send(anyCollection())).thenAnswer(call -> {
            Collection<Message> batch = call.getArgument(0);
            sends.add(batch.size());
            return ack;
        });
        when(producer.send(any(Message.class))).thenAnswer(call -> { sends.add(1); return ack; });
        publisher.publishBatch(List.of(event("a", 300000), event("b", 300000),
                event("c", 300000), event("d", 300000)));
        assertThat(sends).containsExactly(3, 1);
    }

    @Test
    void rejectsAnOversizedEventBeforeSendingAnyPartOfTheBatch() throws Exception {
        RocketMqTaskMessagePublisher publisher = publisher();
        assertThatThrownBy(() -> publisher.publishBatch(List.of(event("ok", 10), event("bad", 950000))))
                .isInstanceOf(TaskPublishException.class);
        verify(producer, never()).send(anyCollection());
        verify(producer, never()).send(any(Message.class));
    }

    @Test
    void treatsNonOkSendStatusAsFailure() throws Exception {
        RocketMqTaskMessagePublisher publisher = publisher();
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        when(producer.send(any(Message.class))).thenReturn(result);
        assertThatThrownBy(() -> publisher.publishBatch(List.of(event("a", 10))))
                .isInstanceOf(TaskPublishException.class);
    }
}
