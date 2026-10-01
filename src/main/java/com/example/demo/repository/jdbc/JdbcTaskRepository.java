package com.example.demo.repository.jdbc;

import com.example.demo.model.ScheduledTask;
import com.example.demo.model.TaskStatus;
import com.example.demo.model.TaskRetryPolicy;
import com.example.demo.repository.TaskRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcTaskRepository implements TaskRepository {
    private static final String SELECT_COLUMNS = """
            id,
            task_id,
            run_timestamp,
            payload,
            status,
            lock_version,
            schedule_version,
            created_at,
            updated_at,
            triggered_at,
            next_attempt_at,
            lease_token,
            lease_until,
            publish_attempts
            """.strip();

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<ScheduledTask> rowMapper;

    private ScheduledTask mapRow(ResultSet rs, int rowNum) throws SQLException {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rs.getString("payload"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Database contains invalid task JSON", exception);
        }
        Timestamp triggeredAt = rs.getTimestamp("triggered_at");
        Timestamp nextAttemptAt = rs.getTimestamp("next_attempt_at");
        Timestamp leaseUntil = rs.getTimestamp("lease_until");
        return new ScheduledTask(
                rs.getLong("id"), rs.getString("task_id"), rs.getTimestamp("run_timestamp").toInstant(),
                payload, TaskStatus.valueOf(rs.getString("status")), rs.getLong("lock_version"),
                rs.getLong("schedule_version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), triggeredAt == null ? null : triggeredAt.toInstant(),
                nextAttemptAt == null ? null : nextAttemptAt.toInstant(), rs.getString("lease_token"),
                leaseUntil == null ? null : leaseUntil.toInstant(), rs.getInt("publish_attempts"));
    }

    public JdbcTaskRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rowMapper = this::mapRow;
    }

    @Override
    public ScheduledTask insert(String taskId, Instant executeAt, String payloadJson, Instant now) {
        String sql = """
                INSERT INTO scheduled_task (
                    task_id,
                    run_timestamp,
                    payload,
                    status,
                    lock_version,
                    schedule_version,
                    created_at,
                    updated_at
                )
                VALUES (
                    :taskId,
                    :executeAt,
                    CAST(:payload AS JSON),
                    'PENDING',
                    1,
                    1,
                    :now,
                    :now
                )
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("executeAt", Timestamp.from(executeAt))
                .addValue("payload", payloadJson)
                .addValue("now", Timestamp.from(now));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, parameters, keyHolder, new String[]{"id"});
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("MySQL did not return the generated task id");
        }
        return findByTaskId(taskId).orElseThrow(() -> new IllegalStateException("Inserted task could not be loaded"));
    }

    @Override
    public Optional<ScheduledTask> findByTaskId(String taskId) {
        String sql = """
                SELECT
                    %s
                FROM scheduled_task
                WHERE task_id = :taskId
                """.formatted(SELECT_COLUMNS.replace("\n", "\n                    "));
        List<ScheduledTask> rows = jdbc.query(sql, new MapSqlParameterSource("taskId", taskId), rowMapper);
        return rows.stream().findFirst();
    }

    @Override
    public List<ScheduledTask> findPage(TaskStatus status, boolean futureOnly, Instant now, int limit, int offset) {
        Query query = query(status, futureOnly, now);
        query.sql.append("""
                ORDER BY created_at DESC, task_id ASC
                LIMIT :limit OFFSET :offset
                """);
        query.parameters.addValue("limit", limit).addValue("offset", offset);
        String sql = """
                SELECT
                    %s
                FROM scheduled_task
                %s
                """.formatted(SELECT_COLUMNS.replace("\n", "\n                    "), query.sql);
        return jdbc.query(sql, query.parameters, rowMapper);
    }

    @Override
    public long count(TaskStatus status, boolean futureOnly, Instant now) {
        Query query = query(status, futureOnly, now);
        String sql = """
                SELECT COUNT(*)
                FROM scheduled_task
                %s
                """.formatted(query.sql);
        Long count = jdbc.queryForObject(sql, query.parameters, Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public boolean update(String taskId, long expectedLockVersion, Instant executeAt, String payloadJson, Instant now) {
        String sql = """
                UPDATE scheduled_task
                SET run_timestamp = :executeAt,
                    payload = CAST(:payload AS JSON),
                    status = 'PENDING',
                    schedule_version = schedule_version + 1,
                    lock_version = lock_version + 1,
                    updated_at = :now,
                    next_attempt_at = NULL,
                    lease_token = NULL,
                    lease_until = NULL,
                    last_error = NULL
                WHERE task_id = :taskId
                  AND lock_version = :lockVersion
                  AND status IN ('PENDING', 'PROCESSING')
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("executeAt", Timestamp.from(executeAt)).addValue("payload", payloadJson)
                .addValue("now", Timestamp.from(now)).addValue("taskId", taskId)
                .addValue("lockVersion", expectedLockVersion);
        return jdbc.update(sql, parameters) == 1;
    }

    @Override
    public boolean cancel(String taskId, long expectedLockVersion, Instant now) {
        String sql = """
                UPDATE scheduled_task
                SET status = 'CANCELLED',
                    lock_version = lock_version + 1,
                    updated_at = :now,
                    lease_token = NULL,
                    lease_until = NULL
                WHERE task_id = :taskId
                  AND lock_version = :lockVersion
                  AND status IN ('PENDING', 'PROCESSING')
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now)).addValue("taskId", taskId)
                .addValue("lockVersion", expectedLockVersion);
        return jdbc.update(sql, parameters) == 1;
    }

    @Override
    public Optional<Instant> findNextWakeAt() {
        String sql = """
                SELECT MIN(wake_at)
                FROM (
                    SELECT MIN(run_timestamp) AS wake_at FROM scheduled_task WHERE status = 'PENDING'
                    UNION ALL
                    SELECT MIN(next_attempt_at) AS wake_at FROM scheduled_task WHERE status = 'PUBLISH_RETRY_WAIT'
                    UNION ALL
                    SELECT MIN(lease_until) AS wake_at FROM scheduled_task WHERE status = 'PROCESSING'
                    UNION ALL
                    SELECT MIN(lease_until) AS wake_at FROM scheduled_task WHERE status = 'PUBLISHING'
                ) AS wake_times
                WHERE wake_at IS NOT NULL
                """;
        Timestamp value = jdbc.queryForObject(sql, new MapSqlParameterSource(), Timestamp.class);
        return Optional.ofNullable(value).map(Timestamp::toInstant);
    }

    @Override
    @Transactional
    public List<ScheduledTask> claimDuePendingBatch(Instant cutoff, int limit, String leaseToken,
                                                    Instant leaseUntil, Instant now) {
        List<ScheduledTask> due = selectDueForUpdate("PENDING", "run_timestamp", cutoff, limit);
        if (due.isEmpty()) return List.of();
        List<Long> ids = due.stream().map(ScheduledTask::id).toList();
        String sql = """
                UPDATE scheduled_task
                SET status = 'PROCESSING', lease_token = :leaseToken,
                    lease_until = :leaseUntil, lock_version = lock_version + 1, updated_at = :now
                WHERE id IN (:ids) AND status = 'PENDING' AND run_timestamp <= :cutoff
                """;
        int changed = jdbc.update(sql, new MapSqlParameterSource().addValue("leaseToken", leaseToken)
                .addValue("leaseUntil", Timestamp.from(leaseUntil)).addValue("now", Timestamp.from(now))
                .addValue("ids", ids).addValue("cutoff", Timestamp.from(cutoff)));
        if (changed != ids.size()) throw new IllegalStateException("Could not claim the complete task batch");
        return findBatchByIds(ids, "PROCESSING", leaseToken);
    }

    @Transactional
    public List<ScheduledTask> beginInitialPublishBatch(List<Long> ids, String leaseToken,
                                                        Instant leaseUntil, Instant now) {
        if (ids.isEmpty()) return List.of();
        String sql = """
                UPDATE scheduled_task
                SET status = 'PUBLISHING', publish_attempts = publish_attempts + 1,
                    lease_until = :leaseUntil, lock_version = lock_version + 1, updated_at = :now
                WHERE id IN (:ids) AND status = 'PROCESSING' AND lease_token = :leaseToken
                  AND lease_until > :now
                """;
        jdbc.update(sql, new MapSqlParameterSource().addValue("leaseUntil", Timestamp.from(leaseUntil))
                .addValue("now", Timestamp.from(now)).addValue("ids", ids).addValue("leaseToken", leaseToken));
        return findBatchByIds(ids, "PUBLISHING", leaseToken);
    }

    @Transactional
    public List<ScheduledTask> claimDueRetryBatch(Instant cutoff, int limit, String leaseToken,
                                                  Instant leaseUntil, Instant now) {
        List<ScheduledTask> due = selectDueForUpdate("PUBLISH_RETRY_WAIT", "next_attempt_at", cutoff, limit);
        if (due.isEmpty()) return List.of();
        List<Long> ids = due.stream().map(ScheduledTask::id).toList();
        String sql = """
                UPDATE scheduled_task
                SET status = 'PUBLISHING', publish_attempts = publish_attempts + 1,
                    lease_token = :leaseToken, lease_until = :leaseUntil, next_attempt_at = NULL,
                    lock_version = lock_version + 1, updated_at = :now
                WHERE id IN (:ids) AND status = 'PUBLISH_RETRY_WAIT' AND next_attempt_at <= :cutoff
                """;
        int changed = jdbc.update(sql, new MapSqlParameterSource().addValue("leaseToken", leaseToken)
                .addValue("leaseUntil", Timestamp.from(leaseUntil)).addValue("now", Timestamp.from(now))
                .addValue("ids", ids).addValue("cutoff", Timestamp.from(cutoff)));
        if (changed != ids.size()) throw new IllegalStateException("Could not claim the complete retry batch");
        return findBatchByIds(ids, "PUBLISHING", leaseToken);
    }

    private List<ScheduledTask> selectDueForUpdate(String status, String timeColumn, Instant cutoff, int limit) {
        String sql = """
                SELECT %s
                FROM scheduled_task
                WHERE status = :status AND %s <= :cutoff
                ORDER BY %s ASC, id ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """.formatted(SELECT_COLUMNS, timeColumn, timeColumn);
        return jdbc.query(sql, new MapSqlParameterSource().addValue("status", status)
                .addValue("cutoff", Timestamp.from(cutoff)).addValue("limit", limit), rowMapper);
    }

    private List<ScheduledTask> findBatchByIds(List<Long> ids, String status, String leaseToken) {
        if (ids.isEmpty()) return List.of();
        String sql = """
                SELECT %s FROM scheduled_task
                WHERE id IN (:ids) AND status = :status AND lease_token = :leaseToken
                ORDER BY run_timestamp ASC, id ASC
                """.formatted(SELECT_COLUMNS);
        return jdbc.query(sql, new MapSqlParameterSource().addValue("ids", ids)
                .addValue("status", status).addValue("leaseToken", leaseToken), rowMapper);
    }

    @Override
    @Transactional
    public int markTriggeredBatch(List<Long> ids, String leaseToken, Instant triggeredAt) {
        if (ids.isEmpty()) return 0;
        String sql = """
                UPDATE scheduled_task
                SET status = 'TRIGGERED', triggered_at = :now,
                    mq_message_key = CONCAT(task_id, ':', schedule_version),
                    lease_token = NULL, lease_until = NULL, lock_version = lock_version + 1, updated_at = :now
                WHERE id IN (:ids) AND status = 'PUBLISHING' AND lease_token = :token AND lease_until > :now
                """;
        return jdbc.update(sql, new MapSqlParameterSource().addValue("now", Timestamp.from(triggeredAt))
                .addValue("ids", ids).addValue("token", leaseToken));
    }

    @Override
    @Transactional
    public int markPublishFailureBatch(List<Long> ids, String leaseToken, Instant now, String error) {
        if (ids.isEmpty()) return 0;
        String sql = """
                UPDATE scheduled_task
                SET %s
                WHERE id IN (:ids) AND status = 'PUBLISHING' AND lease_token = :token AND lease_until > :now
                """.formatted(publishFailureAssignments());
        return jdbc.update(sql, failureParameters(now, error).addValue("ids", ids).addValue("token", leaseToken));
    }

    private String publishFailureAssignments() {
        return """
                status = CASE WHEN publish_attempts >= :maxAttempts THEN 'FAILED' ELSE 'PUBLISH_RETRY_WAIT' END,
                next_attempt_at = CASE WHEN publish_attempts >= :maxAttempts THEN NULL ELSE
                    TIMESTAMPADD(SECOND, CAST(POW(2,
                        LEAST(:maxExponent, GREATEST(1, publish_attempts))) AS UNSIGNED), :now) END,
                last_error = :error, lease_token = NULL, lease_until = NULL,
                lock_version = lock_version + 1, updated_at = :now
                """.strip();
    }

    private MapSqlParameterSource failureParameters(Instant now, String error) {
        return new MapSqlParameterSource().addValue("now", Timestamp.from(now)).addValue("error", error)
                .addValue("maxAttempts", TaskRetryPolicy.MAX_PUBLISH_ATTEMPTS)
                .addValue("maxExponent", TaskRetryPolicy.MAX_BACKOFF_EXPONENT);
    }

    @Override
    @Transactional
    public List<ScheduledTask> recoverExpiredProcessingBatch(Instant now, int limit) {
        List<ScheduledTask> expired = selectDueForUpdate("PROCESSING", "lease_until", now, limit);
        if (expired.isEmpty()) return List.of();
        List<Long> ids = expired.stream().map(ScheduledTask::id).toList();
        String sql = """
                UPDATE scheduled_task
                SET status = 'PENDING', lease_token = NULL, lease_until = NULL,
                    lock_version = lock_version + 1, updated_at = :now
                WHERE id IN (:ids) AND status = 'PROCESSING' AND lease_until <= :now
                """;
        int changed = jdbc.update(sql, new MapSqlParameterSource().addValue("now", Timestamp.from(now))
                .addValue("ids", ids));
        if (changed != ids.size()) throw new IllegalStateException("Could not recover the complete processing batch");
        return expired;
    }

    @Override
    @Transactional
    public List<ScheduledTask> recoverExpiredPublishingBatch(Instant now, int limit, String error) {
        List<ScheduledTask> expired = selectDueForUpdate("PUBLISHING", "lease_until", now, limit);
        if (expired.isEmpty()) return List.of();
        List<Long> ids = expired.stream().map(ScheduledTask::id).toList();
        String sql = """
                UPDATE scheduled_task
                SET %s
                WHERE id IN (:ids) AND status = 'PUBLISHING' AND lease_until <= :now
                """.formatted(publishFailureAssignments());
        int changed = jdbc.update(sql, failureParameters(now, error).addValue("ids", ids));
        if (changed != ids.size()) throw new IllegalStateException("Could not recover the complete publishing batch");
        return expired;
    }

    private Query query(TaskStatus status, boolean futureOnly, Instant now) {
        StringBuilder sql = new StringBuilder("""
                WHERE 1 = 1
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (status != null) {
            sql.append("""
                    AND status = :status
                    """);
            parameters.addValue("status", status.name());
        }
        if (futureOnly) {
            sql.append("""
                    AND run_timestamp > :now
                    """);
            parameters.addValue("now", Timestamp.from(now));
        }
        return new Query(sql, parameters);
    }

    private record Query(StringBuilder sql, MapSqlParameterSource parameters) {
    }
}
