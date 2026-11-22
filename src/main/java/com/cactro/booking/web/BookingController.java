package com.cactro.booking.web;

import com.cactro.booking.jobs.EmailService;
import com.cactro.booking.jobs.NotificationJobs;
import com.cactro.booking.jobs.NotificationJobs.BookingInfo;
import com.cactro.booking.model.Booking;
import com.cactro.booking.model.Event;
import com.cactro.booking.model.Role;
import com.cactro.booking.repo.BookingRepository;
import com.cactro.booking.repo.EventRepository;
import com.cactro.booking.security.AuthInterceptor;
import com.cactro.booking.security.AuthUser;
import com.cactro.booking.security.RequireRole;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
public class BookingController {
    private final BookingService service;
    private final EventRepository events;
    private final BookingRepository bookings;
    private final NotificationJobs jobs;
    private final EmailService email;

    public BookingController(BookingService service, EventRepository events, BookingRepository bookings,
                             NotificationJobs jobs, EmailService email) {
        this.service = service;
        this.events = events;
        this.bookings = bookings;
        this.jobs = jobs;
        this.email = email;
    }

    public record BookReq(Integer quantity) {
        int qty() {
            int q = quantity == null ? 1 : quantity;
            if (q < 1 || q > 10) throw new ApiException(400, "quantity must be 1-10");
            return q;
        }
    }

    /** V2 — OPTIMISED booking. */
    @PostMapping("/events/{id}/book")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireRole(Role.CUSTOMER)
    public Map<String, Object> book(@PathVariable Long id, @RequestBody(required = false) BookReq r,
                                    @RequestAttribute(AuthInterceptor.ATTR) AuthUser user) {
        int qty = r == null ? 1 : r.qty();
        Booking b = service.book(id, user.id(), qty); // committed when this returns
        Event e = events.findById(id).orElseThrow();
        jobs.sendBookingConfirmation(new BookingInfo(b.id, user.name(), user.email(), e.title, e.venue,
                e.startsAt, qty, e.price)); // Background Task 1 (async, returns immediately)
        return Map.of("bookingId", b.id, "eventId", id, "quantity", qty, "status", "CONFIRMED", "email", "queued");
    }

    /**
     * V1 — NAIVE (initial LLM-generated version, kept for the performance demo).
     * Bugs: read-modify-write race (oversells) + email sent synchronously inside the request,
     * so every request holds a Tomcat thread while waiting for a scarce SMTP connection.
     */
    @PostMapping("/events/{id}/book-naive")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireRole(Role.CUSTOMER)
    public Map<String, Object> bookNaive(@PathVariable Long id, @RequestBody(required = false) BookReq r,
                                         @RequestAttribute(AuthInterceptor.ATTR) AuthUser user) {
        int qty = r == null ? 1 : r.qty();
        Event e = events.findById(id).orElseThrow(() -> new ApiException(404, "Event not found"));
        if (e.available < qty) throw new ApiException(409, "Not enough tickets available");
        Booking b = new Booking();
        b.eventId = id;
        b.customerId = user.id();
        b.quantity = qty;
        bookings.save(b);
        email.send(user.email(), "Booking confirmed: " + e.title,
                "<p>Hi " + user.name() + ", you booked " + qty + " ticket(s) for " + e.title + ".</p>");
        e.available = e.available - qty; // stale value read before the email -> lost updates
        events.save(e);
        return Map.of("bookingId", b.id, "eventId", id, "quantity", qty, "status", "CONFIRMED");
    }

    @GetMapping("/bookings/me")
    @RequireRole(Role.CUSTOMER)
    public List<Booking> mine(@RequestAttribute(AuthInterceptor.ATTR) AuthUser user) {
        return bookings.findByCustomerIdOrderByIdDesc(user.id());
    }
}
