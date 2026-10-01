package com.example.demo.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

@Component
public class InfrastructureStartupCheck implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(InfrastructureStartupCheck.class);
    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;
    private final String nameServerAddress;
    private final String brokerAddress;

    public InfrastructureStartupCheck(JdbcTemplate jdbcTemplate,
                                      RedisConnectionFactory redisConnectionFactory,
                                      @Value("${rocketmq.name-server}") String nameServerAddress,
                                      @Value("${rocketmq.broker-address}") String brokerAddress) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
        this.nameServerAddress = nameServerAddress;
        this.brokerAddress = brokerAddress;
    }

    @Override
    public void run(String... args) throws IOException {
        jdbcTemplate.queryForObject("""
                SELECT 1
                """, Integer.class);
        try (var connection = redisConnectionFactory.getConnection()) {
            if (!"PONG".equalsIgnoreCase(connection.ping())) {
                log.warn("Redis did not respond to PING; detail cache will use MySQL fallback");
            }
        } catch (DataAccessException exception) {
            log.warn("Redis is not reachable at startup; detail cache will use MySQL fallback");
        }
        warnIfTcpUnavailable("RocketMQ NameServer", nameServerAddress);
        warnIfTcpUnavailable("RocketMQ Broker", brokerAddress);
    }

    private void warnIfTcpUnavailable(String serviceName, String address) {
        int separator = address.lastIndexOf(':');
        if (separator < 1 || separator == address.length() - 1) {
            log.warn("Invalid {} address; scheduler will retry publishing when configuration is corrected",
                    serviceName);
            return;
        }
        String host = address.substring(0, separator);
        int port = Integer.parseInt(address.substring(separator + 1));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
        } catch (IOException e) {
            log.warn("{} is not reachable at startup; scheduled tasks will remain durable and retry", serviceName);
        }
    }
}
