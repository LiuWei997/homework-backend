SHELL := /bin/sh
.DEFAULT_GOAL := init
.PHONY: init up-docker up-local up-infra-docker up-infra-local rebuild-app-docker clear-task-schedule-topic down down-app-docker down-app-local

COMPOSE := docker compose
APP_PROFILE := --profile app-docker
INFRA_SERVICES := mysql redis rocketmq-namesrv rocketmq-broker rocketmq-console
APP_JAR := target/demo-0.0.1-SNAPSHOT.jar

-include .env

MYSQL_ROOT_PASSWORD ?= root
MYSQL_USER ?= taskuser
MYSQL_PASSWORD ?= taskpass
MYSQL_PORT ?= 3306
REDIS_PORT ?= 6379
ROCKETMQ_NAMESRV_PORT ?= 9876
ROCKETMQ_BROKER_PORT ?= 10911
ROCKETMQ_BROKER_VIP_PORT ?= 10909
ROCKETMQ_CONSOLE_PORT ?= 8088
APP_PORT ?= 8080
SCHEDULER_BATCH_SIZE ?= 50
TASK_MAX_PAYLOAD_BYTES ?= 262144

export MYSQL_ROOT_PASSWORD MYSQL_USER MYSQL_PASSWORD MYSQL_PORT REDIS_PORT
export ROCKETMQ_NAMESRV_PORT ROCKETMQ_BROKER_PORT ROCKETMQ_BROKER_VIP_PORT
export ROCKETMQ_CONSOLE_PORT APP_PORT SCHEDULER_BATCH_SIZE TASK_MAX_PAYLOAD_BYTES

init:
	@if [ ! -f .env ]; then cp .env.example .env; echo 'Created .env from .env.example'; fi
	@docker info >/dev/null 2>&1 || { echo 'Docker is not running or is unavailable.' >&2; exit 1; }
	@docker compose version >/dev/null 2>&1 || { echo 'Docker Compose plugin (docker compose) is required.' >&2; exit 1; }
	@docker compose pull $(INFRA_SERVICES)
	@docker compose $(APP_PROFILE) build app
	@./mvnw -B -DskipTests package

up-docker: up-infra-docker
	@docker compose $(APP_PROFILE) up -d app
	@attempt=0; while [ $$attempt -lt 120 ]; do \
	  id="$$(docker compose $(APP_PROFILE) ps -q app)"; \
	  health="$$(docker inspect --format '{{.State.Health.Status}}' "$$id" 2>/dev/null || true)"; \
	  [ "$$health" = healthy ] && { echo 'App is healthy at http://localhost:'"$(APP_PORT)"; exit 0; }; \
	  [ "$$health" = unhealthy ] && break; sleep 1; attempt=$$((attempt + 1)); \
	done; docker compose $(APP_PROFILE) logs --tail=100 app >&2; exit 1

up-local: up-infra-local
	@command -v java >/dev/null 2>&1 || { echo 'Java 21 or later is required for MODE=local.' >&2; exit 1; }
	@java -version 2>&1 | awk -F '[".]' '/version/ { if ($$2 < 21) exit 1; found=1 } END { if (!found) exit 1 }' || { echo 'Java 21 or later is required for MODE=local.' >&2; exit 1; }
	@command -v curl >/dev/null 2>&1 || { echo 'curl is required for local health checks.' >&2; exit 1; }
	@set -eu; \
	  broker_ip="$$(awk '/^brokerIP1/{print $$3}' .broker.local.conf)"; \
	  env SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:$(MYSQL_PORT)/taskdb?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true&preserveInstants=true" \
	    SPRING_DATASOURCE_USERNAME="$(MYSQL_USER)" SPRING_DATASOURCE_PASSWORD="$(MYSQL_PASSWORD)" \
	    SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT="$(REDIS_PORT)" \
	    ROCKETMQ_NAME_SERVER="localhost:$(ROCKETMQ_NAMESRV_PORT)" SERVER_PORT="$(APP_PORT)" \
	    ROCKETMQ_BROKER_ADDRESS="$$broker_ip:$(ROCKETMQ_BROKER_PORT)" \
	    ROCKETMQ_PRODUCER_GROUP=task-scheduler-bootstrap \
	    SCHEDULER_BATCH_SIZE="$(SCHEDULER_BATCH_SIZE)" \
	    java -jar "$(APP_JAR)" > .local-app.log 2>&1 & \
	  app_pid=$$!; echo "$$app_pid" > .local-app.pid; \
	  trap 'kill "$$app_pid" 2>/dev/null || true; rm -f .local-app.pid' EXIT INT TERM; \
	  attempt=0; \
	  while [ $$attempt -lt 120 ]; do \
	    if curl -fsS "http://localhost:$(APP_PORT)/actuator/health/readiness" >/dev/null 2>&1; then break; fi; \
	    if ! kill -0 "$$app_pid" 2>/dev/null; then echo 'Local app exited during startup.' >&2; tail -100 .local-app.log >&2; exit 1; fi; \
	    sleep 1; attempt=$$((attempt + 1)); \
	  done; \
	  if [ $$attempt -ge 120 ]; then echo 'Local app did not become healthy.' >&2; tail -100 .local-app.log >&2; exit 1; fi; \
	  echo 'App is healthy at http://localhost:'"$(APP_PORT)"' (Ctrl+C to stop)'; \
	  wait "$$app_pid"

rebuild-app-docker: up-infra-docker
	@docker compose $(APP_PROFILE) build app
	@docker compose $(APP_PROFILE) up -d --no-deps --force-recreate app
	@attempt=0; while [ $$attempt -lt 120 ]; do id="$$(docker compose $(APP_PROFILE) ps -q app)"; health="$$(docker inspect --format '{{.State.Health.Status}}' "$$id" 2>/dev/null || true)"; if [ "$$health" = healthy ]; then echo 'App image rebuilt and healthy at http://localhost:'"$(APP_PORT)"; exit 0; fi; [ "$$health" = unhealthy ] && break; sleep 1; attempt=$$((attempt + 1)); done; docker compose $(APP_PROFILE) logs --tail=100 app >&2; exit 1

clear-task-schedule-topic:
	@$(COMPOSE) exec -T rocketmq-broker sh -c 'cd /home/rocketmq/rocketmq-5.1.4 && sh bin/mqadmin deleteTopic -n rocketmq-namesrv:9876 -c DefaultCluster -t task-schedule-topic'

up-infra-docker:
	@if [ ! -f .env ]; then cp .env.example .env; echo 'Created .env from .env.example'; fi
	@docker info >/dev/null 2>&1 || { echo 'Docker is not running or is unavailable.' >&2; exit 1; }
	@docker compose version >/dev/null 2>&1 || { echo 'Docker Compose plugin (docker compose) is required.' >&2; exit 1; }
	@docker compose up -d $(INFRA_SERVICES)

up-infra-local:
	@if [ ! -f .env ]; then cp .env.example .env; echo 'Created .env from .env.example'; fi
	@docker info >/dev/null 2>&1 || { echo 'Docker is not running or is unavailable.' >&2; exit 1; }
	@docker compose version >/dev/null 2>&1 || { echo 'Docker Compose plugin (docker compose) is required.' >&2; exit 1; }
	@case "$$(uname -s)" in \
	  Darwin) iface="$$(route -n get default | awk '/interface:/{print $$2}')"; host_ip="$$(ipconfig getifaddr "$$iface")" ;; \
	  Linux) host_ip="$$(ip route get 1.1.1.1 | awk '{for (i=1; i<=NF; i++) if ($$i == "src") {print $$(i+1); exit}}')" ;; \
	  *) echo 'Local app mode supports macOS and Linux.' >&2; exit 1 ;; \
	esac; \
	[ -n "$$host_ip" ] || { echo 'Could not determine the host IP for RocketMQ broker routing.' >&2; exit 1; }; \
	sed "s/^brokerIP1 = .*/brokerIP1 = $$host_ip/" broker.conf > .broker.local.conf
	@ROCKETMQ_BROKER_CONF=.broker.local.conf docker compose up -d $(INFRA_SERVICES)

down:
	@$(MAKE) down-app-docker
	@$(MAKE) down-app-local
	@docker compose $(APP_PROFILE) down

down-app-docker:
	@docker compose $(APP_PROFILE) stop app 2>/dev/null || true

down-app-local:
	@if [ -f .local-app.pid ]; then \
	  pid="$$(cat .local-app.pid)"; \
	  if kill -0 "$$pid" 2>/dev/null; then \
	    kill "$$pid"; attempt=0; \
	    while kill -0 "$$pid" 2>/dev/null && [ $$attempt -lt 15 ]; do sleep 1; attempt=$$((attempt + 1)); done; \
	    if kill -0 "$$pid" 2>/dev/null; then kill -9 "$$pid" 2>/dev/null || true; fi; \
	  fi; \
	  rm -f .local-app.pid; \
	fi
