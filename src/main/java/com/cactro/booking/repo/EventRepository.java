package com.cactro.booking.repo;

import com.cactro.booking.model.Event;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {
    List<Event> findAllByOrderByStartsAtAsc(org.springframework.data.domain.Pageable p);

    /** Atomic conditional decrement: succeeds only if enough tickets remain. No read-modify-write race. */
    @Modifying(clearAutomatically = true)
    @Query("update Event e set e.available = e.available - :qty where e.id = :id and e.available >= :qty")
    int reserve(@Param("id") Long id, @Param("qty") int qty);

    @Modifying(clearAutomatically = true)
    @Query("update Event e set e.available = e.available + (:cap - e.capacity), e.capacity = :cap " +
           "where e.id = :id and :cap >= (e.capacity - e.available)")
    int changeCapacity(@Param("id") Long id, @Param("cap") int cap);
}
