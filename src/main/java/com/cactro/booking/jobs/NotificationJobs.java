package com.cactro.booking.jobs;

import com.cactro.booking.model.Event;
import com.cactro.booking.model.User;
import com.cactro.booking.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Background tasks. Each @Async call is a job on the jobExecutor queue; failures retry with backoff. */
@Service
public class NotificationJobs {
    private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);
    private static final int MAX_ATTEMPTS = 3;
    private final EmailService email;
    private final UserRepository users;
    public final AtomicLong processed = new AtomicLong(), failed = new AtomicLong();

    public NotificationJobs(EmailService email, UserRepository users) {
        this.email = email;
        this.users = users;
    }

    public record BookingInfo(Long bookingId, String customerName, String customerEmail, String title,
                              String venue, String startsAt, int quantity, double price) {}

    /** Background Task 1: booking confirmation email. */
    @Async("jobExecutor")
    public void sendBookingConfirmation(BookingInfo b) {
        withRetry("booking-confirmation #" + b.bookingId(), () -> email.send(b.customerEmail(),
                "Booking confirmed: " + b.title() + " (#" + b.bookingId() + ")",
                "<h2>Hi " + b.customerName() + ", your booking is confirmed!</h2>"
                        + "<p><b>Event:</b> " + b.title() + "<br><b>Venue:</b> " + nz(b.venue())
                        + "<br><b>When:</b> " + b.startsAt() + "<br><b>Tickets:</b> " + b.quantity()
                        + "<br><b>Total:</b> Rs. " + String.format("%.2f", b.price() * b.quantity())
                        + "<br><b>Booking ID:</b> " + b.bookingId() + "</p>"));
    }

    /** Background Task 2: notify every customer who booked this event (fan-out, one email each). */
    @Async("jobExecutor")
    public void notifyEventUpdated(Event e, Map<String, Object> changes) {
        List<User> customers = users.findCustomersWithBookings(e.id);
        String list = changes.entrySet().stream()
                .map(c -> "<li><b>" + c.getKey() + "</b>: " + c.getValue() + "</li>")
                .collect(Collectors.joining());
        log.info("event-update #{}: notifying {} customers", e.id, customers.size());
        for (User u : customers) {
            withRetry("event-update #" + e.id + " -> " + u.email, () -> email.send(u.email,
                    "Update: \"" + e.title + "\" has changed",
                    "<h2>Hi " + u.name + ",</h2><p>An event you booked has been updated:</p><ul>" + list + "</ul>"
                            + "<p><b>Current details:</b> " + e.title + ", " + nz(e.venue) + ", " + e.startsAt + "</p>"));
        }
    }

    private void withRetry(String name, Runnable task) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                task.run();
                processed.incrementAndGet();
                return;
            } catch (Exception ex) {
                log.warn("job {} attempt {} failed: {}", name, attempt, ex.getMessage());
                if (attempt == MAX_ATTEMPTS) { failed.incrementAndGet(); return; }
                try { Thread.sleep(1000L << attempt); } catch (InterruptedException ie) { return; } // 2s, 4s
            }
        }
    }

    private static String nz(String s) { return s == null ? "TBA" : s; }
}
