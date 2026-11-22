# Event Booking System — Backend API (Spring Boot)

Backend APIs for an event booking system with two roles: **Event Organizers** (manage events) and **Customers** (browse events and book tickets).

- **Live:** https://event-booking-api-2rpr.onrender.com  (health check: `/health`)
- **Code:** https://github.com/Jeevan2798/event-booking-api

> **Free-tier note:** the app is hosted on Render's free tier. It sleeps after ~15 minutes of inactivity, so the first request can take 1–2 minutes to wake it. The database is a file on the instance's temporary disk, so data resets when the service restarts or redeploys.

## Stack
Java 17 · Spring Boot 3.3 (Web, Data JPA, Mail) · H2 file database · JWT (jjwt) · BCrypt · `@Async` worker-pool job queue · Brevo HTTPS email API · Docker (deployed on Render).

## Run locally
```bash
export JWT_SECRET=<at-least-32-random-characters>
export BREVO_API_KEY=<brevo api key>          # real email via HTTPS (recommended)
export MAIL_FROM=<sender email verified in Brevo>
mvn spring-boot:run                           # http://localhost:8080
```
Without `BREVO_API_KEY` the app falls back to SMTP (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS`), which works locally but is blocked on Render's free tier.

### Environment variables
| Variable | Required | Purpose |
|---|---|---|
| `JWT_SECRET` | yes | JWT signing key, at least 32 characters. The app refuses to start without it. |
| `BREVO_API_KEY` | for email | API key for Brevo's transactional email API. |
| `MAIL_FROM` | for email | Plain email address, verified as a sender in Brevo. |
| `PORT` | no | Set by Render (default 8080). |
| `SIM_SMTP_CONNECTIONS`, `SIM_SMTP_LATENCY_MS` | no | Parameters of the simulated email provider used only for `@loadtest.dev` accounts (default 5 and 400). |

## Roles and access control
| Role | Can do |
|---|---|
| Public | browse events, register, log in |
| **ORGANIZER** | create / update / delete **own** events, view bookings of own events |
| **CUSTOMER** | book tickets, view own bookings |

The JWT carries the user id and role. Controller methods are annotated with `@RequireRole(...)` and enforced by `AuthInterceptor`:
- **401** for a missing or invalid token
- **403** for the wrong role
- **403** when an organizer touches another organizer's event

## API
| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/auth/register` | – | `{name, email, password, role: "organizer" or "customer"}` → `{user, token}` |
| POST | `/auth/login` | – | `{email, password}` → `{user, token}` |
| GET | `/events?page&size` | – | browse events |
| GET | `/events/{id}` | – | event details and `available` tickets |
| POST | `/events` | organizer | `{title, startsAt, capacity, venue?, description?, price?}` |
| PUT | `/events/{id}` | organizer (owner) | partial update; triggers **Event Update Notification** |
| DELETE | `/events/{id}` | organizer (owner) | blocked (409) if the event already has bookings |
| GET | `/events/{id}/bookings` | organizer (owner) | bookings, `ticketsSold` and an `oversold` audit flag |
| POST | `/events/{id}/book` | customer | `{quantity: 1-10}`; **optimized**; triggers **Booking Confirmation** |
| POST | `/events/{id}/book-naive` | customer | the initial implementation, kept as the performance baseline |
| GET | `/bookings/me` | customer | own bookings |
| GET | `/health` | – | status and background-job counters |

Errors are returned as `{"error": "..."}` with the right status (400 validation / malformed JSON, 401, 403, 404, 405, 409 conflict or sold out).

### Example
```bash
BASE=https://event-booking-api-2rpr.onrender.com
curl -X POST $BASE/auth/register -H "Content-Type: application/json" \
  -d '{"name":"Org","email":"org@example.com","password":"pass1234","role":"organizer"}'
curl -X POST $BASE/events -H "Authorization: Bearer <organizer token>" -H "Content-Type: application/json" \
  -d '{"title":"Rock Night","startsAt":"2030-01-01T18:00:00Z","venue":"Hall A","capacity":100,"price":500}'
curl -X POST $BASE/events/1/book -H "Authorization: Bearer <customer token>" -H "Content-Type: application/json" \
  -d '{"quantity":2}'
```

## Background tasks
Mechanism: Spring `@Async("jobExecutor")` on a `ThreadPoolTaskExecutor` (5 workers, queue capacity 100,000). Each job retries up to 3 times with exponential backoff (2 s, 4 s). `/health` shows `queued`, `running`, `processed` and `failed` counters.

1. **Booking Confirmation:** after the booking transaction commits, `sendBookingConfirmation()` is queued. A worker sends a **real HTML email** to the customer with event, venue, time, ticket count, total and booking ID.
2. **Event Update Notification:** `PUT /events/{id}` queues `notifyEventUpdated()`. A worker finds every customer with a confirmed booking for that event and sends each one a **real email** listing the fields that changed.

**Email transport:** the Brevo HTTPS API (`POST https://api.brevo.com/v3/smtp/email`). Render's free tier blocks outbound SMTP ports 25/465/587, so SMTP cannot be used there. An SMTP fallback (`JavaMailSender`) remains for local use.

**Load-test accounts:** emails ending in `@loadtest.dev` never send real mail, so a stress test cannot spam anyone or burn the email quota. They go through a model of a provider with 5 concurrent connections and about 400 ms per email. The naive endpoint and the background queue share this model, so `StressTest` waits for `/health` to show an empty queue before each level. Real users always get real email.

## Design decisions
- **Denormalised `available` column** on `events`: availability is an O(1) read. Organizer edits use `@DynamicUpdate`, so they never overwrite it.
- **Atomic reservation:** `UPDATE Event SET available = available - :qty WHERE id = :id AND available >= :qty` plus the booking `INSERT` in one short `@Transactional`. If 0 rows are updated, the event is sold out (409). There is no read-modify-write window, so no overselling, and no locks or retry loops are needed.
- **Capacity changes** are also one atomic UPDATE and are refused if they would go below the tickets already sold.
- **Event deletion** is blocked when bookings exist, so there are no orphaned bookings and nobody is left un-notified.
- **Connection handling:** `open-in-view=false` so DB connections are held only inside transactions; Hikari pool of 20; Tomcat 200 threads with an accept queue of 1000; indexes on the booking foreign keys.
- **Why a queue instead of sending email in the request:** an email provider call takes hundreds of milliseconds and has limited concurrency. Doing it inside the request holds a Tomcat thread and makes latency grow with load.
- **H2 file database (PostgreSQL mode):** zero-ops for this task. Pointing `spring.datasource.url` at Postgres is the production path.
- **In-process job queue:** needs no extra infrastructure on the free tier. For durability or multiple instances, use a broker (RabbitMQ/Kafka/Redis) or a DB outbox table.
- **Security:** BCrypt for passwords (cost 8), JWT expiry of 7 days, emails stored lowercase, startup fails without a strong `JWT_SECRET`.

## Performance / stress test
`StressTest.java` needs no build tool (Java 17+): it registers 100 customers, creates an event with capacity = 80% of the buyers, and fires N booking requests **at the same instant**. A request counts as **DROPPED** if there is no response within 10 seconds. Afterwards it audits the event for overselling.

```bash
java StressTest.java https://event-booking-api-2rpr.onrender.com naive 25 50 100 200
java StressTest.java https://event-booking-api-2rpr.onrender.com optimized 100 300
```

### V1 — naive (`/book-naive`), the initial implementation
Reads `available`, inserts the booking, **sends the email synchronously inside the request**, then writes `available = staleValue - qty`.
- It **oversells** (lost updates from the read-modify-write race).
- Every request holds a Tomcat thread while it waits for a scarce email connection, so latency grows with load until requests time out.

### V2 — optimized (`/book`)
1. Email moved to the background queue, so the request returns without waiting for it.
2. Atomic conditional UPDATE inside a transaction, so exactly `capacity` tickets are sold and everyone else gets a clean 409.
3. Short transactions, pooled connections and indexes.

### Measured results (Render free instance: 0.1 CPU, 512 MB)
| Version | Concurrent users | Dropped (>10 s) | Oversold |
|---|---|---|---|
| Naive | 25 | 0 | Yes (25 sold, capacity 20) |
| Naive | 50 | 0 | Yes (50 sold, capacity 40) |
| Naive | 100 | 8 (first drops) | Yes (100 sold, capacity 80) |
| Naive | 200 | 125 (62.5%) | Yes (200 sold, capacity 160) |
| Optimized | 100 | 0 | No (80 sold, capacity 80) |
| Optimized | 300 | 151 (~50%) | No (203 sold, capacity 240) |

- The naive version **breaks between 50 and 100 concurrent users** and oversells at every level.
- The optimized version **never oversold** and handled at least 100 concurrent users with zero drops. It degrades by 300 users; the limit comes mainly from the free instance's 0.1 CPU (even 100 users show multi-second latency), not from the design.

### How this can be pushed further
- A larger instance: more CPU is the first bottleneck on the free tier.
- Postgres instead of the H2 file, and horizontal scaling behind the load balancer.
- A Redis counter (`DECRBY`) as a pre-check in front of the database for flash sales.
- A message broker for the email jobs, so they survive restarts.
- Per-user rate limiting or a waiting room, and idempotency keys for client retries.
- Virtual threads (Java 21) to hold more concurrent waiting requests.

## Known limitations
- `startsAt` is stored as an ISO-8601 string, so ordering by date only works for ISO-8601 values (for example `2030-01-01T18:00:00Z`).
- The job queue is in memory: emails still queued are lost if the process restarts.
- The database resets on redeploy or restart on Render's free tier.
- The naive endpoint is the deliberately weak baseline for the performance comparison, not production code.
- The load test uses simulated email for `@loadtest.dev` accounts; the results depend on the instance size.

## Deploy (Render)
New → Web Service → connect this repo → Runtime **Docker** (uses the `Dockerfile`) → Free instance. Set `JWT_SECRET`, `BREVO_API_KEY` and `MAIL_FROM`.
