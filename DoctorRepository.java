package za.co.nmas.nmas.repository;

import za.co.nmas.nmas.entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {

    Optional<Doctor> findByEmail(String email);

    boolean existsByHpcsaNumber(String hpcsaNumber);

    List<Doctor> findBySpecializationIgnoreCaseContaining(String specialization);
}