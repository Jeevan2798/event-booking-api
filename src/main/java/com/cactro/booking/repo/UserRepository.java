package com.cactro.booking.repo;

import com.cactro.booking.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    @Query("select u from User u where u.id in (select b.customerId from Booking b where b.eventId = :eventId and b.status = 'CONFIRMED')")
    List<User> findCustomersWithBookings(@Param("eventId") Long eventId);
}
