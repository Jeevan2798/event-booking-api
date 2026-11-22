# Event Booking System — Backend API (Spring Boot)

Java 17 · Spring Boot 3.3 (Web, Data JPA, Mail) · H2 file DB · JWT (jjwt) · BCrypt · `@Async` worker-pool job queue · real SMTP email.

**Live:** `https://<your-app>.onrender.com` · Health: `/health`

## Run locally
```bash
export SMTP_USER=you@gmail.com SMTP_PASS=<gmail-app-password> JWT_SECRET=<at-least-32-characters>
mvn spring-boot:run        # http://localhost:8080
```

## Roles & access control
| Role | Can do |
|---|---|
| Public | browse events, register, login |
| **ORGANIZER** | create / update / delete **own** events, view bookings of own events |
| **CUSTOMER** | book tickets, view own bookings |

The JWT carries `{id, role}`. Controller methods are annotated `@RequireRole(Role.ORGANIZER)` and checked by `AuthInterceptor`: **401** for a missing or invalid token, **403** for the wrong role. Ownership checks return 403 if an organizer touches another organizer's event.

## API
| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/auth/register` | – | `{name,email,password,role:"organizer"/"customer"}` → `{user, token}` |
| POST | `/auth/login` | – | `{email,password}` |
| GET | `/events?page&size` | – | browse |
| GET | `/events/{id}` | – | |
| POST | `/events` | organizer | `{title,startsAt,capacity,venue?,description?,price?}` |
| PUT | `/events/{id}` | organizer (owner) | partial update → **Event Update Notification** |
| DELETE | `/events/{id}` | organizer (owner) | |
| GET | `/events/{id}/bookings` | organizer (owner) | bookings + `ticketsSold` + `oversold` audit flag |
| POST | `/events/{id}/book` | customer | `{quantity:1-10}` **optimised** → **Booking Confirmation** |
| POST | `/events/{id}/book-naive` | customer | initial LLM version (kept for comparison) |
| GET | `/bookings/me` | customer | |
| GET | `/health` | – | job queue stats |

## Background tasks
Mechanism: Spring `@Async("jobExecutor")` on a `ThreadPoolTaskExecutor` (5 workers, queue capacity 100k). Each job retries 3 times with exponential backoff (2 s, 4 s).
1. **Booking Confirmation:** after the booking transaction commits, `sendBookingConfirmation()` is queued. It sends a **real HTML email** (Brevo HTTPS API in production, because Render's free tier blocks SMTP ports; SMTP via `JavaMailSender` as a local fallback) with event, venue, time, tickets, total and booking ID.
2. **Event Update Notification:** `PUT /events/{id}` queues `notifyEventUpdated()`. It loads every customer with a confirmed booking for that event and sends each one a real email listing the changed fields.

**Load-test accounts:** emails ending in `@loadtest.dev` are not sent for real, so a stress run can't spam people or get Gmail to block the account. Instead they go through a model of the real SMTP limit: a semaphore of 5 connections × 400 ms each (`SIM_SMTP_CONNECTIONS`, `SIM_SMTP_LATENCY_MS`). Real users always get real email.

## Known limitations / honest notes
- `startsAt` is stored as an ISO-8601 string, so ordering by date works only for ISO-8601 values (e.g. `2030-01-01T18:00:00Z`).
- Deleting an event that already has bookings is blocked (409), so no orphaned bookings and nobody is left un-notified.
- The job queue is in-memory: queued emails are lost if the process restarts (a DB outbox or a broker fixes this; noted under further ideas).
- `JWT_SECRET` (>= 32 chars) is required; the app refuses to start without it.
- The load test uses `@loadtest.dev` accounts, which do **not** send real email. They go through a model of a provider with 5 concurrent connections x 400 ms. The naive endpoint and the background queue share that model, so `StressTest` waits for `/health` to show an empty queue before each level.
- The naive endpoint is the deliberately weak "initial LLM implementation" baseline.
- Numbers depend on the instance size (Render free = 0.1 CPU, `-Xmx350m`).

## Design decisions
- **Denormalised `available` column** on `events`: O(1) availability. Organizer edits use `@DynamicUpdate`, so they never overwrite it.
- **Atomic reservation:** `UPDATE Event SET available = available - :qty WHERE id = :id AND available >= :qty` plus the booking `INSERT` in one short `@Transactional`. 0 rows updated means 409 sold out. No read-modify-write window, so no overselling, and no pessimistic locks or optimistic-lock retry storms.
- **Capacity change** is also one atomic UPDATE and is refused if it would go below tickets already sold.
- **`open-in-view=false`**, so DB connections are held only inside transactions. Hikari pool 20, Tomcat 200 threads / accept-count 1000, and indexes on `bookings(eventId)`, `bookings(customerId)`.
- **H2 file DB** (PostgreSQL mode): zero-ops for the demo. Point `spring.datasource.url` at Postgres for production; the same queries work.
- **Queue choice:** an in-process executor needs no extra infra on the free tier. The upgrade path for multiple instances or durability is RabbitMQ/Kafka/Redis (BullMQ-style) or a DB outbox table.

## Performance / stress test
`StressTest.java` (no build needed: `java StressTest.java …`) registers 100 customers, creates an event with **capacity = 80% of buyers**, then fires N bookings **at the same instant**. A request is **DROPPED** if there's no response within 10 s. Afterwards it audits the DB for overselling.
```bash
java StressTest.java https://<app>.onrender.com naive 50 100 200 400
java StressTest.java https://<app>.onrender.com optimized 200 500 1000 2000
```
**V1 naive (`/book-naive`):** reads `available`, inserts the booking, sends the email **synchronously inside the request**, then saves `available = staleValue - qty`.
- Lost updates: it **oversells** at every level.
- Every request holds a Tomcat thread while waiting for one of 5 SMTP connections, so latency ≈ N × 400 ms / 5. The breaking point is where requests start timing out and being dropped (**measured numbers go in the table below**).

**V2 optimised (`/book`):** email moved to the background queue (the request returns in ms), atomic conditional UPDATE in a transaction, open-in-view off, pooled connections and indexes. Measured results are in the table below.

| | Breaking point (first drops) | Max with 0 drops | Oversell |
|---|---|---|---|
| Render, naive | _fill in_ | _fill in_ | _fill in_ |
| Render, optimised | _fill in_ | _fill in_ | never |

**Further ideas:** Postgres plus horizontal scaling behind the load balancer, a Redis `DECRBY` counter as a pre-check for flash sales, a message broker for jobs, per-user rate limiting or a waiting room, idempotency keys, virtual threads (Java 21), and a bigger instance than the free 0.1 CPU.

## Deploy on Render
New → Web Service → connect the repo → Runtime **Docker** (uses the `Dockerfile`). Env vars: `JWT_SECRET` (32+ chars), `BREVO_API_KEY`, `MAIL_FROM` (a sender verified in Brevo). Render's free tier blocks SMTP ports 25/465/587, which is why email goes over HTTPS. The free tier's disk is ephemeral (DB resets on redeploy), which is fine for the demo.
