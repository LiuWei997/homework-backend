
# 📬 Task Scheduling Service – Backend Homework

This is a technical assignment for backend engineer candidates. You are expected to build a RESTful schedule-task service using **Spring Boot**, integrating **MySQL**, **Redis**, and **RocketMQ**.


---

## 🎯 Objective

Build a RESTful Task Scheduling API that lets users create a task with a specific future execution time.
The system must:
- Store scheduled tasks
- Publish task trigger messages at the specified time
- Allow querying and managing scheduled tasks
- Utilize Redis or in-memory queue as delay buffer (if desired)
- Integrate with RocketMQ for downstream processing

---

## 🧰 Tech Requirements

You **must use** the following technologies:

- **Java 21+**
- **Spring Boot**
- **MySQL** (for persistence)
- **Redis** (for caching)
- **RocketMQ** (for event messaging)

You may use starter dependencies such as:
- Spring Web
- Spring Data JPA
- Spring Cache
- RocketMQ Spring Boot Starter

---

## 🔧 Features to Implement

### 1️⃣ Create Scheduled Task

**Endpoint:** `POST /tasks`

```json
{
  "taskId": "abc-123",
  "executeAt": "2025-07-21T15:00:00Z",
  "payload": {
    "type": "email",
    "target": "hello@example.com",
    "message": "This is a scheduled task!"
  }
}
```

**Expected Behavior:**
- Store the task record in MySQL
- Optionally insert into a Redis delay queue or sorted set
- Ensure the task will be published to RocketMQ (`task-schedule-topic`) when `executeAt` is reached

---

### 2️⃣ Poll & Trigger Task Execution

**Mechanism:**
- A background service should check for due tasks (`executeAt <= now()`)
- Once due, publish the `payload` to MQ
- Mark the task as `triggered` in MySQL

---

### 3️⃣ Get Task by ID

**Endpoint:** `GET /tasks/{taskId}`

**Expected Behavior:**
- Return task details including status, payload, and scheduled time

---

### 4️⃣ Cancel Scheduled Task

**Endpoint:** `DELETE /tasks/{taskId}`

**Expected Behavior:**
- Mark the task as `cancelled` in DB
- Prevent MQ publishing if it hasn’t been triggered

---

### 5️⃣ List Pending Tasks

**Endpoint:** `GET /tasks?status=pending`

**Expected Behavior:**
- Return future scheduled tasks with pagination

⸻

🧪 Bonus (Optional)
- Use Spring Cache abstraction or RedisTemplate encapsulation
- Apply proper error handling with meaningful status codes
- Define your own DTO and message format for RocketMQ
- Use consistent and modular code structure (controller, service, repository, config, etc.)
- Test case coverage: as much as possible

⸻

🐳 Environment Setup

Use the provided docker-compose.yaml file to start required services:

Service	Port  
MySQL	3306  
Redis	6379  
RocketMQ Namesrv	9876  
RocketMQ Broker	10911  
RocketMQ Console	8088

To start the services:

```commandline
docker-compose up -d
```

MySQL credentials:
- User: taskuser
- Password: taskpass
- Database: taskdb

You may edit init.sql to create required tables automatically.

⸻

🚀 Getting Started

To run the application:

./mvn spring-boot:run

Make sure to update your application.yml with the proper connections for:
- spring.datasource.url
- spring.redis.host
- rocketmq.name-server

⸻

📤 Submission

Please submit a `public Github repository` that includes:
- ✅ Complete and executable source code
- ✅ README.md (this file)
- ✅ Any necessary setup or data scripts please add them in HELP.md
- ✅ Optional: Postman collection or curl samples

⸻

📌 Notes
- Focus on API correctness, basic error handling, and proper use of each technology
- You may use tools like Vibe Coding / ChatGPT to assist, but please write and understand your own code
- The expected time to complete is around 3 hours

Good luck!

---
