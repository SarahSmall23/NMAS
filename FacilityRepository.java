package za.co.nmas.nmas.repository;

import za.co.nmas.nmas.entity.Facility;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FacilityRepository extends JpaRepository<Facility, Long> {
    Optional<Facility> findByRegistrationNumber(String registrationNumber);
}