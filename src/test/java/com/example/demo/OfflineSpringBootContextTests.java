package com.example.demo;

import com.example.demo.messaging.TaskMessagePublisher;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import com.example.demo.repository.TaskRepository;
import com.example.demo.scheduler.SchedulerWakeup;
import com.example.demo.scheduler.TaskCacheFence;
import com.example.demo.scheduler.TaskDispatchService;
import com.example.demo.scheduler.TaskRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = OfflineSpringBootContextTests.OfflineTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "management.endpoint.health.group.readiness.include=readinessState")
class OfflineSpringBootContextTests {
    @Autowired
    private SchedulerWakeup schedulerWakeup;

    @Autowired
    private TaskDispatchService taskDispatchService;

    @Autowired
    private TaskRecoveryService taskRecoveryService;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    void loadsSelectedApplicationServicesWithoutExternalInfrastructure() {
        assertThat(schedulerWakeup).isNotNull();
        assertThat(taskDispatchService).isNotNull();
        assertThat(taskRecoveryService).isNotNull();
        assertThat(applicationContext.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(org.springframework.data.redis.connection.RedisConnectionFactory.class))
                .isEmpty();
        assertThat(applicationContext.getBeansOfType(DefaultMQProducer.class)).isEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class
    })
    @Import({TaskDispatchService.class, TaskRecoveryService.class, SchedulerWakeup.class,
            OfflineDependencies.class})
    static class OfflineTestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OfflineDependencies {
        @Bean
        TaskRepository taskRepository() {
            return mock(TaskRepository.class);
        }

        @Bean
        TaskMessagePublisher taskMessagePublisher() {
            return mock(TaskMessagePublisher.class);
        }

        @Bean
        TaskCacheFence taskCacheFence() {
            return mock(TaskCacheFence.class);
        }

        @Bean
        Clock applicationClock() {
            return Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
