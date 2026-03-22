package com.greennode.api_gateway.repository;

import com.greennode.api_gateway.entity.Job;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository providing out-of-the-box CRUD operations for our Job entities. */
@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {
    // Standard CRUD methods (save, findById) are automatically provided.
}
