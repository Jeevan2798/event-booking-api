package com.cactro.booking.web;

import com.cactro.booking.jobs.NotificationJobs;
import com.cactro.booking.model.Event;
import com.cactro.booking.model.Role;
import com.cactro.booking.repo.BookingRepository;
import com.cactro.booking.repo.EventRepository;
import com.cactro.booking.security.AuthInterceptor;
import com.cactro.booking.security.AuthUser;
import com.cactro.booking.security.RequireRole;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/events")
public class EventController {
    private static final Set<String> EDITABLE = Set.of("title", "description", "venue", "startsAt", "price", "capacity");
    private final EventRepository events;
    private final BookingRepository bookings;
    private final BookingService service;
    private final NotificationJobs jobs;

    public EventController(EventRepository events, BookingRepository bookings, BookingService service, NotificationJobs jobs) {
        this.events = events;
        this.bookings = bookings;
        this.service = service;
        this.jobs = jobs;
    }

    // ---- public browse ----
    @GetMapping
    public List<Event> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return events.findAllByOrderByStartsAtAsc(PageRequest.of(page, Math.min(size, 100)));
    }

    @GetMapping("/{id}")
    public Event get(@PathVariable Long id) {
        return events.findById(id).orElseThrow(() -> new ApiException(404, "Event not found"));
    }

    // ---- organizer only ----
    public record CreateEventReq(String title, String description, String venue, String startsAt, Integer capacity, Double price) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireRole(Role.ORGANIZER)
    public Event create(@RequestBody CreateEventReq r, @RequestAttribute(AuthInterceptor.ATTR) AuthUser user) {
        if (r.title() == null || r.startsAt() == null || r.capacity() == null || r.capacity() <= 0)
            throw new ApiException(400, "title, startsAt and positive capacity required");
        Event e = new Event();
        e.organizerId = user.id();
        e.title = r.title();
        e.description = r.description();
        e.venue = r.venue();
        e.startsAt = r.startsAt();
        e.capacity = r.capacity();
        e.available = r.capacity();
        e.price = r.price() == null ? 0.0 : r.price();
        return events.save(e);
    }

    @PutMapping("/{id}")
    @RequireRole(Role.ORGANIZER)
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                      @RequestAttribute(AuthInterceptor.ATTR) AuthUser user) {
        Event current = owned(id, user);
        Map<String, Object> changes = new LinkedHashMap<>();
        body.forEach((k, v) -> { if (EDITABLE.contains(k) && v != null) changes.put(k, v); });
        if (changes.isEmpty()) throw new ApiException(400, "No editable fields supplied: " + EDITABLE);
        Event updated = service.update(current.id, changes);
        jobs.notifyEventUpdated(updated, changes); // Background Task 2 (async)
        return Map.of("event", updated, "notification", "queued");
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireRole(Role.ORGANIZER)
    public void delete(@PathVariable Long id, @RequestAttribute(AuthInterceptor.ATTR) AuthUser user) {
        Event e = owned(id, user);
        if (bookings.existsByEventId(id))
            throw new ApiException(409, "Cannot delete an event that already has bookings");
        events.delete(e);
    }

    @GetMapping("/{id}/bookings")
    @RequireRole(Role.ORGANIZER)
    public Map<String, Object> eventBookings(@PathVariable Long id, @RequestAttribute(AuthInterceptor.ATTR) AuthUser user) {
        Event e = owned(id, user);
        long sold = bookings.ticketsSold(id);
        var list = bookings.findByEventIdOrderById(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", id);
        out.put("capacity", e.capacity);
        out.put("available", e.available);
        out.put("ticketsSold", sold);
        out.put("bookingsCount", list.size());
        out.put("oversold", sold > e.capacity);
        out.put("bookings", list);
        return out;
    }

    private Event owned(Long id, AuthUser user) {
        Event e = events.findById(id).orElseThrow(() -> new ApiException(404, "Event not found"));
        if (!e.organizerId.equals(user.id())) throw new ApiException(403, "You can only manage your own events");
        return e;
    }
}
