package com.cactro.booking.web;

import com.cactro.booking.model.Booking;
import com.cactro.booking.model.Event;
import com.cactro.booking.repo.BookingRepository;
import com.cactro.booking.repo.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Map;

@Service
public class BookingService {
    private final EventRepository events;
    private final BookingRepository bookings;

    public BookingService(EventRepository events, BookingRepository bookings) {
        this.events = events;
        this.bookings = bookings;
    }

    /** V2 optimised: atomic conditional UPDATE + INSERT in one short transaction. Never oversells. */
    @Transactional
    public Booking book(Long eventId, Long customerId, int qty) {
        if (events.reserve(eventId, qty) == 0) {
            if (!events.existsById(eventId)) throw new ApiException(404, "Event not found");
            throw new ApiException(409, "Not enough tickets available");
        }
        Booking b = new Booking();
        b.eventId = eventId;
        b.customerId = customerId;
        b.quantity = qty;
        return bookings.save(b);
    }

    @Transactional
    public Event update(Long eventId, Map<String, Object> changes) {
        // validate types first -> 400 (never a ClassCastException / 500)
        Integer cap = changes.containsKey("capacity") ? asInt(changes.get("capacity"), "capacity") : null;
        if (cap != null && cap <= 0) throw new ApiException(400, "capacity must be positive");
        String title = changes.containsKey("title") ? asString(changes.get("title"), "title") : null;
        String description = changes.containsKey("description") ? asString(changes.get("description"), "description") : null;
        String venue = changes.containsKey("venue") ? asString(changes.get("venue"), "venue") : null;
        String startsAt = changes.containsKey("startsAt") ? asString(changes.get("startsAt"), "startsAt") : null;
        Double price = changes.containsKey("price") ? asDouble(changes.get("price"), "price") : null;
        if (title != null && title.isBlank()) throw new ApiException(400, "title cannot be blank");

        if (cap != null && events.changeCapacity(eventId, cap) == 0) {
            if (!events.existsById(eventId)) throw new ApiException(404, "Event not found");
            throw new ApiException(400, "capacity cannot be below tickets already sold");
        }
        Event e = events.findById(eventId).orElseThrow(() -> new ApiException(404, "Event not found"));
        if (title != null) e.title = title;
        if (description != null) e.description = description;
        if (venue != null) e.venue = venue;
        if (startsAt != null) e.startsAt = startsAt;
        if (price != null) e.price = price;
        e.updatedAt = Instant.now();
        return events.saveAndFlush(e);
    }

    private static int asInt(Object o, String f) {
        if (o instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())) return n.intValue();
        throw new ApiException(400, f + " must be an integer");
    }

    private static double asDouble(Object o, String f) {
        if (o instanceof Number n && n.doubleValue() >= 0) return n.doubleValue();
        throw new ApiException(400, f + " must be a non-negative number");
    }

    private static String asString(Object o, String f) {
        if (o instanceof String s) return s;
        throw new ApiException(400, f + " must be a string");
    }
}
