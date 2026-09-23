package com.fredvested.web.repository;

import com.fredvested.web.model.EmailEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailEventRepository extends JpaRepository<EmailEvent, Long> {

    boolean existsBySvixId(String svixId);
}
