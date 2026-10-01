# 本機開發操作說明

README.md 保留作業提供的原始題目與要求。以下列出 Makefile 的操作；Docker/local 的啟停 target 已分開，不需要設定 `MODE`。

## 初始化

```bash
make init
```

建立 `.env`（若尚未存在）、拉取 MySQL、Redis、RocketMQ、Console 映像，建置 app Docker image，並打包本機執行用的 Spring Boot jar。

## 離線單元測試與打包

測試使用 fake／mock repository、publisher 和 cache；Spring Boot context 測試明確排除 DataSource、JdbcTemplate、Flyway 與 Redis 自動配置，不會連線 MySQL、Redis 或 RocketMQ。沒有這些服務的打包機可以直接執行：

```bash
./mvnw -B package
```

此命令會先執行單元測試，再產生 `target/demo-0.0.1-SNAPSHOT.jar`。測試需要 JDK 21 與 Maven wrapper 所需的 Maven dependencies；不需要啟動 Docker 或任何應用外部服務。

## 啟動全部服務

App 和依賴都在 Docker：

```bash
make up-docker
```

App 在本機、依賴在 Docker：

```bash
make up-local
```

本機模式會佔用目前終端，readiness 通過後持續運行。按 `Ctrl+C` 停止 app。

Docker 模式下，修改 app 後可單獨重建 image 並重啟 app container：

```bash
make rebuild-app-docker
```

## 只啟動依賴服務

```bash
make up-infra-docker
make up-infra-local
```

local 版本會設定 RocketMQ Broker 對本機 app 公告可連線的位址。

## 關閉服務

無論 app 目前在哪裡執行，都可用同一個命令關閉 Docker app、本機 app 及所有依賴容器：

```bash
make down
```

只關閉 Docker app：

```bash
make down-app-docker
```

只關閉本機 app：

```bash
make down-app-local
```

關閉操作會保留 MySQL 與 Redis volumes。Compose 設定及連線埠／開發帳密範例位於 `docker-compose.yaml` 和 `.env.example`。

app health endpoint：<http://localhost:8080/actuator/health>。RocketMQ Console：<http://localhost:8088>。

### 清除 RocketMQ 排程 topic

停止會發布或消費訊息的 app 後，執行：

```bash
make clear-task-schedule-topic
```

此命令會進入 RocketMQ Broker container，從 `DefaultCluster` 刪除 `task-schedule-topic` 的 topic 設定與路由。它不會立即物理抹除 Broker 共用 CommitLog 中的舊訊息；磁碟空間依 RocketMQ 的訊息保留與清理機制回收。若 app 仍在執行，後續發布可能重新建立 topic。

## API 與排程發布

Scheduler 全流程圖（包含 class/method 呼叫鏈、SQL 條件、wakeup、重試和 crash recovery）：[docs/scheduler-flow.html](docs/scheduler-flow.html)。

服務提供 task 建立、查詢、列表、調整與取消 API。任務保存在 MySQL，detail 查詢使用 Redis cache；Redis 不可用時會回退 MySQL。Scheduler 以 MySQL 為唯一排程來源，動態等待最早任務，API 寫入 commit 後會喚醒本機 scheduler。

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

Scheduler 每批預設最多處理 50 筆：MySQL 在短 transaction 內批次 claim lease，接著批次轉為 `PUBLISHING`，以 RocketMQ 批次 API 發送，收到 ACK 後批次更新為 `TRIGGERED`。狀態改變後，Redis detail cache 的 Lua fence invalidation commands 也會經 pipeline 批次送出。MQ 批次同時受訊息總位元組數限制，超過時會拆成較小的發送批次。發送失敗或 ACK 結果不明時，該批全部進入重試等待，後續每次只 claim / send 一筆，使用新的 lease 做故障隔離；先前已被 Broker 收下的訊息可能因此重送。lease 到期也會在 app 重啟後恢復。首次發布之外最多逐筆重試 3 次，分別等待 2 / 4 / 8 秒（退避公式上限 16 秒），第 3 次重試仍失敗就 FAILED，並清空 next_attempt_at / lease。因為有重試上限，不能保證任意長度 MQ 故障後每個任務必定送達；FAILED 也可能是 Broker 收件但 ACK 遺失。`eventId` 固定為 `{taskId}:{scheduleVersion}`；下游應用 `eventId` 去重。API 只負責發出 trigger event，不會執行 payload 內的業務內容。

手動要求 scheduler 立即重新查詢 MySQL、重算下次休眠時間：

```bash
curl -i -X POST http://localhost:8080/scheduler/wakeup
```

成功回 `204 No Content`。這只會喚醒 scheduler，不會直接發布某個任務；任務仍由到期條件及 DB CAS 決定是否進入發布流程。

Scheduler 的每批任務數預設為 50，涵蓋首次到期任務 claim 與過期 lease 掃描；故障隔離的重試每次 claim 1 筆。可在 `application.yaml` 調整 `scheduler.batch-size`，或用 `SCHEDULER_BATCH_SIZE` 環境變數覆寫；設定值必須大於 0。`scheduler.publish-lease` 預設為 2 分鐘，應依 batch-size、payload 大小及 RocketMQ send timeout 留足整批處理時間。RocketMQ 批次也會依序列化後訊息大小拆分；若任一批發送結果不明，該批會降級逐筆重試，因此消費端必須以穩定 `eventId` 冪等處理。

重啟 app 或整台開發機時保留 MySQL volume，啟動後 scheduler 會從 DB 恢復待處理任務與過期 lease。`make down` 會保留 MySQL／Redis volumes；不要使用 `docker compose down -v`，除非確定要刪除持久化資料。Flyway 會在 app 啟動時套用版本化 migration。

### Postman 完整 API 流程

可匯入 [Task Scheduling Service Postman Collection](postman/Task_Scheduling_Service.postman_collection.json)，然後在 Postman Collection Runner 依照預設順序執行。Collection 預設使用 `http://localhost:8080`；若 app 使用其他 port，修改 collection variable `baseUrl`。

Collection 會用動態 taskId 和未來時間執行以下流程：

1. `POST /tasks` 建立任務。
2. `GET /tasks/{taskId}` 查詢任務。
3. `GET /tasks?status=pending&page=0&size=20` 查未來待執行任務。
4. `GET /tasks?page=0&size=20` 查全部狀態。
5. `PUT /tasks/{taskId}` 調整時間與 payload，再次 GET 確認。
6. `DELETE /tasks/{taskId}` 取消任務，再次 GET 確認狀態。
7. 驗證重複 taskId 回 `409`、過去時間回 `400`、重複取消回 `409`、不存在的 taskId 回 `404`。

每個 request 都附有 Postman Tests 腳本，會檢查 HTTP status、回應 body、分頁欄位、Location、scheduleVersion 遞增、錯誤 code，以及 `POST /scheduler/wakeup` 的 `204` 空回應。先啟動 app 與基礎服務，再執行 collection。Collection 預設的建立任務時間為 10 分鐘後；若想驗證 RocketMQ 觸發流程，可在 Postman 變數把 `createExecuteAt` 設為較近的未來時間，並在 RocketMQ Console 查詢 `task-schedule-topic`。


### ID、cache 與 payload 規則

taskId 沿用 client 指定值，所有入口都只轉小寫、不 trim、不重新產生 ID。允許 1-128 個 ASCII 字母、數字、`.`、`_`、`:`、`-`，首字是字母或數字。大小寫不同視為相同 ID；空白或 slash 回 400 INVALID_TASK_ID。Redis v2 namespace 使用 base64url 編碼小寫 ID，key 為 `task:v2:{encodedId}:detail` / `task:v2:{encodedId}:fence`，避免任務 ID 和 fence namespace 相撞。舊 key 不再讀取，由既有 TTL 自行過期。

payload 按序列化後 UTF-8 bytes 計算，create / update 預設最多 262144（256 KiB），超過回 413 PAYLOAD_TOO_LARGE。可設定 `TASK_MAX_PAYLOAD_BYTES` 或 `task.max-payload-bytes`，範圍 1-524288；更大的上限在啟動時拒絕。MQ 根據 SDK 編碼後的完整 message bytes（包含 properties）拆批，且會在發送第一個子批前先驗證整批。