package com.example.demo.config;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfrastructureConfiguration {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    DefaultMQProducer rocketMqProducer(
            @Value("${rocketmq.producer.group}") String producerGroup,
            @Value("${rocketmq.name-server}") String nameServer) {
        DefaultMQProducer producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        producer.setSendMsgTimeout(3000);
        producer.setRetryTimesWhenSendFailed(0);
        return producer;
    }
}
