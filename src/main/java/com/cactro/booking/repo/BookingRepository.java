package com.cactro.booking.repo;

import com.cactro.booking.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByCustomerIdOrderByIdDesc(Long customerId);
    List<Booking> findByEventIdOrderById(Long eventId);
    boolean existsByEventId(Long eventId);

    @Query("select coalesce(sum(b.quantity), 0) from Booking b where b.eventId = :eventId")
    long ticketsSold(@Param("eventId") Long eventId);
}
