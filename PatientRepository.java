package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import za.co.nmas.nmas.entity.Patient;

import java.util.List;
import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    Optional<Patient> findByIdNumber(String idNumber);

    Optional<Patient> findByEmail(String email);

    List<Patient> findByFullNameContainingIgnoreCase(String fullName);

    List<Patient> findByPhoneNumberContaining(String phoneNumber);

    Optional<Patient> findByPassportNumber(String passportNumber);

    List<Patient> findByPreferredFacilityIgnoreCase(String preferredFacility);

    @Query("SELECT DISTINCT p.preferredFacility FROM Patient p WHERE p.preferredFacility IS NOT NULL AND p.preferredFacility <> '' ORDER BY p.preferredFacility")
    List<String> findDistinctFacilities();

    /** Cross-facility lookup by name, SA ID number or passport number (partial match). */
    @Query("SELECT p FROM Patient p WHERE LOWER(p.fullName) LIKE LOWER(CONCAT('%',:q,'%')) " +
           "OR p.idNumber LIKE CONCAT('%',:q,'%') OR LOWER(p.passportNumber) LIKE LOWER(CONCAT('%',:q,'%')) " +
           "ORDER BY p.fullName")
    List<Patient> searchAllByNameOrId(@Param("q") String q);

    @Query("SELECT p FROM Patient p WHERE " +
           "(:facility IS NULL OR :facility = '' OR LOWER(p.preferredFacility) = LOWER(:facility)) AND " +
           "(:query IS NULL OR :query = '' OR " +
           "LOWER(p.fullName) LIKE LOWER(CONCAT('%',:query,'%')) OR " +
           "LOWER(p.idNumber) LIKE LOWER(CONCAT('%',:query,'%')) OR " +
           "LOWER(p.email) LIKE LOWER(CONCAT('%',:query,'%')) OR " +
           "LOWER(p.phoneNumber) LIKE LOWER(CONCAT('%',:query,'%')))")
    List<Patient> searchByFacilityAndQuery(@Param("facility") String facility, @Param("query") String query);
}
