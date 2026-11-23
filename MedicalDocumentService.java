package za.co.nmas.nmas.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import za.co.nmas.nmas.entity.UploadedMedicalDocument;
import za.co.nmas.nmas.repository.PatientRepository;
import za.co.nmas.nmas.repository.UploadedMedicalDocumentRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class MedicalDocumentService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final UploadedMedicalDocumentRepository documentRepository;
    private final PatientRepository patientRepository;

    @Value("${nmas.upload.directory}")
    private String uploadDirectory;

    public MedicalDocumentService(UploadedMedicalDocumentRepository documentRepository,
                                  PatientRepository patientRepository) {
        this.documentRepository = documentRepository;
        this.patientRepository = patientRepository;
    }

    public UploadedMedicalDocument upload(Long patientId, MultipartFile file,
                                          String documentType, String notes,
                                          String uploaderUsername) throws IOException {

        if (!ALLOWED_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException(
                    "File type not allowed. Accepted: PDF, JPG, PNG, DOCX.");
        }

        Path dir = Paths.get(uploadDirectory);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        String storedFileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path target = dir.resolve(storedFileName);
        file.transferTo(target.toFile());

        UploadedMedicalDocument doc = new UploadedMedicalDocument();
        doc.setPatient(patientRepository.findById(patientId)
                .orElseThrow(() -> new RuntimeException("Patient not found")));
        doc.setUploadedBy(uploaderUsername);
        doc.setDocumentType(documentType);
        doc.setFileName(file.getOriginalFilename());
        doc.setStoredFileName(storedFileName);
        doc.setContentType(file.getContentType());
        doc.setFilePath(target.toString());
        doc.setNotes(notes);

        return documentRepository.save(doc);
    }

    public List<UploadedMedicalDocument> findByPatient(Long patientId) {
        return documentRepository.findByPatientIdOrderByUploadDateDesc(patientId);
    }

    public UploadedMedicalDocument findById(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Document not found"));
    }

    public String getFilePath(Long documentId) {
        return findById(documentId).getFilePath();
    }
}
