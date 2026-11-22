package com.cactro.booking.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bookings", indexes = {
        @Index(name = "idx_booking_event", columnList = "event_id"),
        @Index(name = "idx_booking_customer", columnList = "customer_id")})
public class Booking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "event_id", nullable = false)
    public Long eventId;
    @Column(name = "customer_id", nullable = false)
    public Long customerId;
    @Column(nullable = false)
    public Integer quantity;
    public String status = "CONFIRMED";
    public Instant createdAt = Instant.now();
}
