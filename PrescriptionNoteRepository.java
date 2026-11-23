package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.PrescriptionNote;
import java.util.List;

public interface PrescriptionNoteRepository extends JpaRepository<PrescriptionNote, Long> {

    List<PrescriptionNote> findByPrescriptionId(Long prescriptionId);

    List<PrescriptionNote> findByPrescriptionIdOrderByCreatedAtDesc(Long prescriptionId);
}
