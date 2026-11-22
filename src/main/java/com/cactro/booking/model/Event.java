package com.cactro.booking.model;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import java.time.Instant;

@Entity
@DynamicUpdate // only changed columns are written -> organizer edits never overwrite `available`
@Table(name = "events")
public class Event {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public Long organizerId;
    @Column(nullable = false)
    public String title;
    @Column(length = 2000)
    public String description;
    public String venue;
    @Column(nullable = false)
    public String startsAt;
    @Column(nullable = false)
    public Integer capacity;
    @Column(nullable = false)
    public Integer available; // denormalised counter: O(1) availability, atomic decrement
    public Double price = 0.0;
    public Instant updatedAt = Instant.now();
}
