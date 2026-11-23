package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.PrescriptionMedication;
import java.util.List;

public interface PrescriptionMedicationRepository extends JpaRepository<PrescriptionMedication, Long> {

    List<PrescriptionMedication> findByPrescriptionId(Long prescriptionId);

    void deleteByPrescriptionId(Long prescriptionId);
}
