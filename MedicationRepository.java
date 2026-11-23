package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.Medication;
import java.util.List;

public interface MedicationRepository extends JpaRepository<Medication, Long> {

    List<Medication> findByActiveTrue();

    List<Medication> findByMedicationNameContainingIgnoreCase(String name);

    List<Medication> findByDosageForm(String dosageForm);

    boolean existsByMedicationCode(String code);

    java.util.Optional<Medication> findByMedicationCode(String code);
}
