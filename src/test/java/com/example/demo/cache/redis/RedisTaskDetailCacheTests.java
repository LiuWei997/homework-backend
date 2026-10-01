package com.example.demo.cache.redis;

import com.example.demo.cache.TaskDetailCache.Invalidation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.ArrayList;
import org.springframework.data.redis.core.script.RedisScript;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTaskDetailCacheTests {
    @Test
    void detailAndFenceNamespacesCannotCollideWithAnotherId() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        List<List<String>> keys = new ArrayList<>();
        doAnswer(call -> {
            keys.add(call.getArgument(1));
            return 1L;
        }).when(redis).execute(any(RedisScript.class), anyList(), anyString(), anyString());
        RedisTaskDetailCache cache = new RedisTaskDetailCache(redis, new ObjectMapper());
        cache.invalidate("A", 1);
        cache.invalidate("fence:a", 1);
        assertThat(keys.get(0)).containsExactly("task:v2:{YQ}:detail", "task:v2:{YQ}:fence");
        assertThat(keys.get(1)).containsExactly("task:v2:{ZmVuY2U6YQ}:detail", "task:v2:{ZmVuY2U6YQ}:fence");
        assertThat(keys.stream().flatMap(List::stream).toList()).doesNotHaveDuplicates();
    }

    @Test
    void invalidatesMultipleTasksUsingOneRedisPipeline() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisScriptingCommands scripting = mock(RedisScriptingCommands.class);
        when(connection.scriptingCommands()).thenReturn(scripting);
        doAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            callback.doInRedis(connection);
            return List.of(1L, 1L);
        }).when(redis).executePipelined(any(RedisCallback.class));
        RedisTaskDetailCache cache = new RedisTaskDetailCache(redis, new ObjectMapper());

        cache.invalidateAll(List.of(new Invalidation("task-a", 5), new Invalidation("task-b", 8)));

        verify(redis).executePipelined(any(RedisCallback.class));
        verify(scripting, times(2)).eval(any(byte[].class), eq(ReturnType.INTEGER), eq(2), any(byte[][].class));
    }
}
