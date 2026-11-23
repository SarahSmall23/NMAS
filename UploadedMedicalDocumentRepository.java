package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.UploadedMedicalDocument;

import java.util.List;

public interface UploadedMedicalDocumentRepository extends JpaRepository<UploadedMedicalDocument, Long> {
    List<UploadedMedicalDocument> findByPatientIdOrderByUploadDateDesc(Long patientId);
}
