# Local Development Guide

[繁體中文](HELP.md) | **English**

`README.md` is the original assignment prompt and is kept unchanged. This guide documents the Makefile targets. Docker and local app targets are separate; no `MODE` variable is needed.

## Initialize

```bash
make init
```

This creates `.env` from `.env.example` if needed, pulls the MySQL, Redis, RocketMQ, and RocketMQ Console images, builds the app Docker image, and packages the Spring Boot JAR for local execution.

## Run offline unit tests and package

Unit tests use fake or mock repositories, publishers, and caches. The Spring Boot context test explicitly excludes DataSource, JdbcTemplate, Flyway, and Redis auto-configuration, so it does not connect to MySQL, Redis, or RocketMQ. A build machine without these services can run:

```bash
./mvnw -B package
```

This runs unit tests and creates `target/demo-0.0.1-SNAPSHOT.jar`. It requires JDK 21 and the Maven dependencies used by the wrapper, but does not require Docker or any application services.

## Start all services

Run the app and its dependencies in Docker:

```bash
make up-docker
```

Run the app locally and its dependencies in Docker:

```bash
make up-local
```

Local mode occupies the current terminal after readiness succeeds. Press `Ctrl+C` to stop the app.

To rebuild the app image and restart only the app container in Docker mode after changing the app:

```bash
make rebuild-app-docker
```

## Start only the infrastructure

```bash
make up-infra-docker
make up-infra-local
```

The local variant configures the RocketMQ Broker to advertise an address reachable by the app running on the host.

## Stop services

Use one command to stop the Docker app, local app, and all dependency containers regardless of where the app is running:

```bash
make down
```

Stop only the Docker app:

```bash
make down-app-docker
```

Stop only the local app:

```bash
make down-app-local
```

These commands preserve the MySQL and Redis volumes. Compose settings and sample ports/development credentials are in `docker-compose.yaml` and `.env.example`.

App health endpoint: <http://localhost:8080/actuator/health>. RocketMQ Console: <http://localhost:8088>.

### Clear the RocketMQ task topic

Stop any app that publishes or consumes messages, then run:

```bash
make clear-task-schedule-topic
```

This enters the RocketMQ Broker container and removes the `task-schedule-topic` topic configuration and route from `DefaultCluster`. It does not immediately erase old messages from the Broker's shared CommitLog; disk space is reclaimed according to RocketMQ's retention and cleanup process. If the app is still running, a later publish may recreate the topic.

## API and scheduled publishing

The [scheduler flow diagram](docs/scheduler-flow.html) shows the full flow, including class/method calls, SQL conditions, wakeup, retries, and crash recovery.

The service supports task creation, lookup, listing, schedule updates, and cancellation. Tasks are stored in MySQL. Detail lookups use Redis and fall back to MySQL when Redis is unavailable. MySQL is the scheduler's source of truth. The scheduler dynamically waits for the earliest task, and task API writes wake the local scheduler after commit.

```bash
curl -i -X POST http://localhost:8080/tasks \
  -H 'Content-Type: application/json' \
  -d '{"taskId":"abc-123","executeAt":"2027-07-21T15:00:00Z","payload":{"type":"email","target":"hello@example.com"}}'

curl http://localhost:8080/tasks/abc-123
curl 'http://localhost:8080/tasks?status=pending&page=0&size=20'
curl -i -X PUT http://localhost:8080/tasks/abc-123 \
  -H 'Content-Type: application/json' \
  -d '{"executeAt":"2027-07-22T15:00:00Z","payload":{"type":"email","target":"hello@example.com"}}'
curl -i -X DELETE http://localhost:8080/tasks/abc-123
```

The scheduler processes up to 50 tasks per batch by default. MySQL claims leases in a short transaction, the tasks are moved to `PUBLISHING` in a batch, and RocketMQ receives batch sends. After ACK, the database marks the tasks `TRIGGERED` in a batch. Redis detail-cache Lua fence invalidations are also pipelined after state changes. RocketMQ imposes a total message-size limit, so a scheduler batch may be split into smaller sends. If sending fails or the ACK outcome is unknown, the entire affected batch enters retry wait; subsequent attempts claim and send one task at a time with a new lease to isolate failures. Messages already accepted by the Broker may be sent again. Expired leases are recovered after an app restart.

There are at most three individual retries after the initial publish attempt, with delays of 2, 4, and 8 seconds (the backoff formula is capped at 16 seconds). If the third retry also fails, the task becomes `FAILED`, and `next_attempt_at` and the lease are cleared. Because retries are finite, delivery is not guaranteed during an arbitrarily long RocketMQ outage. A `FAILED` task may still have been accepted by the Broker if its ACK was lost. The stable `eventId` is `{taskId}:{scheduleVersion}`; consumers should deduplicate by this ID. The API only publishes trigger events; it does not execute the business operation in the payload.

To ask the scheduler to query MySQL again and recalculate its next sleep time:

```bash
curl -i -X POST http://localhost:8080/scheduler/wakeup
```

The endpoint returns `204 No Content`. It only wakes the scheduler; it does not publish a task directly. Due-time checks and the database CAS still determine whether a task can enter the publish flow.

The default scheduler batch size is 50 for initial due-task claims and expired-lease scans. Failure-isolation retries claim one task at a time. Configure `scheduler.batch-size` in `application.yaml` or override it with `SCHEDULER_BATCH_SIZE`; the value must be positive. `scheduler.publish-lease` defaults to 2 minutes. Set it long enough for the whole batch, based on batch size, payload sizes, and RocketMQ send timeout. RocketMQ sends may also be split by serialized message size. If a send result is unknown, the affected batch falls back to individual retries, so consumers must handle duplicate `eventId` values idempotently.

Keep the MySQL volume when restarting the app or development machine. On startup, the scheduler recovers pending tasks and expired leases from the database. `make down` preserves MySQL and Redis volumes. Do not run `docker compose down -v` unless you intend to delete persistent data. Flyway applies versioned migrations at app startup.

### Full Postman API flow

Import the [Task Scheduling Service Postman Collection](postman/Task_Scheduling_Service.postman_collection.json), then run it in Postman's Collection Runner in its default order. The collection uses `http://localhost:8080` by default; change the `baseUrl` collection variable if the app uses another port.

The collection uses a generated task ID and a future execution time to run these steps:

1. Create a task with `POST /tasks`.
2. Retrieve it with `GET /tasks/{taskId}`.
3. List future pending tasks with `GET /tasks?status=pending&page=0&size=20`.
4. List all task statuses with `GET /tasks?page=0&size=20`.
5. Update the schedule and payload with `PUT /tasks/{taskId}`, then retrieve it again.
6. Cancel it with `DELETE /tasks/{taskId}`, then retrieve it to verify the status.
7. Verify duplicate task IDs return `409`, past execution times return `400`, repeated cancellation returns `409`, and missing tasks return `404`.

Each request includes a Postman Tests script that checks the HTTP status, response body, pagination fields, `Location`, `scheduleVersion` increments, error codes, and the empty `204` response from `POST /scheduler/wakeup`. Start the app and infrastructure before running the collection. The default task is scheduled 10 minutes in the future. To check RocketMQ publishing, set `createExecuteAt` to a nearer future time in Postman and inspect `task-schedule-topic` in RocketMQ Console.

### Task ID, cache, and payload rules

The service uses the client-provided `taskId`. All entry points convert it to lowercase; the service does not trim it or generate a replacement ID. IDs must be 1–128 ASCII letters, digits, `.`, `_`, `:`, or `-`, and must start with a letter or digit. IDs that differ only by case are treated as the same ID. Whitespace and slashes return `400 INVALID_TASK_ID`.

The Redis v2 namespace uses the base64url-encoded lowercase ID. Keys are `task:v2:{encodedId}:detail` and `task:v2:{encodedId}:fence`, preventing collisions between task IDs and fence keys. Old keys are no longer read and expire according to their existing TTL.

Payload size is measured in serialized UTF-8 bytes. Create and update requests default to a maximum of 262144 bytes (256 KiB); larger payloads return `413 PAYLOAD_TOO_LARGE`. Configure the limit with `TASK_MAX_PAYLOAD_BYTES` or `task.max-payload-bytes`; the allowed range is 1–524288 bytes. The app rejects larger configured limits at startup. RocketMQ batches by the SDK-encoded message size, including properties, and validates the entire batch before sending its first sub-batch.
