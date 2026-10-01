package com.example.demo.cache.redis;

import com.example.demo.cache.TaskDetailCache;
import com.example.demo.dto.TaskResponse;
import com.example.demo.util.TaskIdCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Component
public class RedisTaskDetailCache implements TaskDetailCache {
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final Duration FENCE_TTL = Duration.ofSeconds(10);
    private static final DefaultRedisScript<Long> PUT_SCRIPT = new DefaultRedisScript<>("""
            local fence = tonumber(redis.call('GET', KEYS[2]) or '-1')
            if tonumber(ARGV[1]) < fence then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE_SCRIPT = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[2]) or '-1')
            local incoming = tonumber(ARGV[1])
            if incoming > current then current = incoming end
            redis.call('SET', KEYS[2], tostring(current), 'PX', ARGV[2])
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisTaskDetailCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CachedTaskResponse> get(String taskId) {
        String value = redis.opsForValue().get(cacheKey(taskId));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, CachedTaskResponse.class));
        } catch (JsonProcessingException exception) {
            redis.delete(cacheKey(taskId));
            return Optional.empty();
        }
    }

    @Override
    public void put(String taskId, long lockVersion, TaskResponse response) {
        try {
            String value = objectMapper.writeValueAsString(new CachedTaskResponse(lockVersion, response));
            redis.execute(PUT_SCRIPT, List.of(cacheKey(taskId), fenceKey(taskId)),
                    Long.toString(lockVersion), value, Long.toString(CACHE_TTL.toMillis()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize task detail cache value", exception);
        }
    }

    @Override
    public void invalidate(String taskId, long lockVersion) {
        invalidateAll(List.of(new Invalidation(taskId, lockVersion)));
    }

    @Override
    public void invalidateAll(List<Invalidation> invalidations) {
        if (invalidations.isEmpty()) return;
        if (invalidations.size() == 1) {
            invalidateOne(invalidations.getFirst());
            return;
        }
        byte[] script = INVALIDATE_SCRIPT.getScriptAsString().getBytes(StandardCharsets.UTF_8);
        StringRedisSerializer serializer = StringRedisSerializer.UTF_8;
        redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Invalidation invalidation : invalidations) {
                byte[][] keysAndArguments = {
                        serializer.serialize(cacheKey(invalidation.taskId())),
                        serializer.serialize(fenceKey(invalidation.taskId())),
                        serializer.serialize(Long.toString(invalidation.lockVersion())),
                        serializer.serialize(Long.toString(FENCE_TTL.toMillis()))
                };
                connection.scriptingCommands().eval(script, ReturnType.INTEGER, 2, keysAndArguments);
            }
            return null;
        });
    }

    private void invalidateOne(Invalidation invalidation) {
        redis.execute(INVALIDATE_SCRIPT, List.of(cacheKey(invalidation.taskId()), fenceKey(invalidation.taskId())),
                Long.toString(invalidation.lockVersion()), Long.toString(FENCE_TTL.toMillis()));
    }

    private String keyPrefix(String taskId) {
        String encodedId = Base64.getUrlEncoder().withoutPadding().encodeToString(
                TaskIdCodec.normalize(taskId).getBytes(StandardCharsets.UTF_8));
        return "task:v2:{" + encodedId + "}";
    }

    private String cacheKey(String taskId) {
        return keyPrefix(taskId) + ":detail";
    }

    private String fenceKey(String taskId) {
        return keyPrefix(taskId) + ":fence";
    }
}
