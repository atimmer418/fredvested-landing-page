package com.fredvested.web.repository;

import com.fredvested.web.model.WaitlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {
   
    boolean existsByEmail(String email);

    WaitlistEntry findByEmail(String email);
   
    long countByStatus(WaitlistEntry.WaitlistStatus status);

    @Query("SELECT AVG(w.freedomAge) FROM WaitlistEntry w")
    Double getAverageFreedomAge();
}