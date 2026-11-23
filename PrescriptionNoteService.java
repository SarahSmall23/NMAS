package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.Prescription;
import za.co.nmas.nmas.entity.PrescriptionNote;
import za.co.nmas.nmas.repository.PrescriptionNoteRepository;
import za.co.nmas.nmas.repository.PrescriptionRepository;

import java.util.List;

@Service
public class PrescriptionNoteService {

    private final PrescriptionNoteRepository prescriptionNoteRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final AuditLogService auditLogService;

    public PrescriptionNoteService(PrescriptionNoteRepository prescriptionNoteRepository,
                                   PrescriptionRepository prescriptionRepository,
                                   AuditLogService auditLogService) {
        this.prescriptionNoteRepository = prescriptionNoteRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.auditLogService = auditLogService;
    }

    public PrescriptionNote addNote(Long prescriptionId, String noteType, String noteText, Doctor doctor) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new RuntimeException("Prescription not found"));

        PrescriptionNote note = new PrescriptionNote();
        note.setPrescription(prescription);
        note.setDoctor(doctor);
        note.setNoteType(noteType);
        note.setNoteText(noteText);

        PrescriptionNote saved = prescriptionNoteRepository.save(note);
        String truncated = noteText.length() > 80 ? noteText.substring(0, 80) + "..." : noteText;
        auditLogService.log("PRESCRIPTION_NOTE_ADDED", "PrescriptionNote", saved.getId(),
                "Rx #" + prescriptionId + " — " + noteType + ": " + truncated);
        return saved;
    }

    public void deleteNote(Long noteId, String requesterEmail) {
        PrescriptionNote note = prescriptionNoteRepository.findById(noteId)
                .orElseThrow(() -> new RuntimeException("Note not found"));
        if (!note.getDoctor().getEmail().equals(requesterEmail)) {
            throw new RuntimeException("Access denied: you can only delete your own notes");
        }
        String detail = "Rx #" + note.getPrescription().getId()
                + " — " + note.getNoteType()
                + " by Dr. " + note.getDoctor().getFullName();
        prescriptionNoteRepository.delete(note);
        auditLogService.log("PRESCRIPTION_NOTE_DELETED", "PrescriptionNote", noteId, detail);
    }

    public List<PrescriptionNote> findByPrescriptionId(Long prescriptionId) {
        return prescriptionNoteRepository.findByPrescriptionIdOrderByCreatedAtDesc(prescriptionId);
    }
}
