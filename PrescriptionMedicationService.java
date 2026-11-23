package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Medication;
import za.co.nmas.nmas.entity.Prescription;
import za.co.nmas.nmas.entity.PrescriptionMedication;
import za.co.nmas.nmas.repository.MedicationRepository;
import za.co.nmas.nmas.repository.PrescriptionMedicationRepository;
import za.co.nmas.nmas.repository.PrescriptionRepository;

import java.util.List;

@Service
public class PrescriptionMedicationService {

    private final PrescriptionMedicationRepository prescriptionMedicationRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final MedicationRepository medicationRepository;
    private final AuditLogService auditLogService;
    private final DispensaryService dispensaryService;
    private final MedicationService medicationService;

    public PrescriptionMedicationService(PrescriptionMedicationRepository prescriptionMedicationRepository,
                                         PrescriptionRepository prescriptionRepository,
                                         MedicationRepository medicationRepository,
                                         AuditLogService auditLogService,
                                         DispensaryService dispensaryService,
                                         MedicationService medicationService) {
        this.medicationService = medicationService;
        this.prescriptionMedicationRepository = prescriptionMedicationRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.medicationRepository = medicationRepository;
        this.auditLogService = auditLogService;
        this.dispensaryService = dispensaryService;
    }

    public PrescriptionMedication addMedication(Long prescriptionId, Long medicationId,
                                                 String dosage, String frequency, String duration,
                                                 String route, String mealInstruction,
                                                 boolean morning, boolean afternoon,
                                                 boolean evening, boolean night,
                                                 String instructions) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new RuntimeException("Prescription not found"));
        Medication medication = medicationRepository.findById(medicationId)
                .orElseThrow(() -> new RuntimeException("Medication not found"));
        medicationService.assertPrescribable(medication,
                prescription.getDoctor() != null ? prescription.getDoctor().getFacility() : null);

        PrescriptionMedication pm = new PrescriptionMedication();
        pm.setPrescription(prescription);
        pm.setMedication(medication);
        pm.setDosage(dosage);
        pm.setFrequency(frequency);
        pm.setDuration(duration);
        pm.setRoute(route);
        pm.setMealInstruction(mealInstruction);
        pm.setMorning(morning);
        pm.setAfternoon(afternoon);
        pm.setEvening(evening);
        pm.setNight(night);
        pm.setInstructions(instructions);

        PrescriptionMedication saved = prescriptionMedicationRepository.save(pm);
        dispensaryService.createForPrescriptionMedication(prescription, saved);
        auditLogService.log("PRESCRIPTION_MEDICATION_ADDED", "PrescriptionMedication", saved.getId(),
                "Rx #" + prescriptionId + " — " + medication.getMedicationName() + " " + medication.getStrength());
        return saved;
    }

    public void removeMedication(Long prescriptionMedicationId) {
        prescriptionMedicationRepository.findById(prescriptionMedicationId).ifPresent(pm -> {
            String detail = "Rx #" + pm.getPrescription().getId()
                    + " — " + pm.getMedication().getMedicationName()
                    + " " + pm.getMedication().getStrength();
            prescriptionMedicationRepository.delete(pm);
            auditLogService.log("PRESCRIPTION_MEDICATION_REMOVED", "PrescriptionMedication",
                    prescriptionMedicationId, detail);
        });
    }

    public List<PrescriptionMedication> findByPrescriptionId(Long prescriptionId) {
        return prescriptionMedicationRepository.findByPrescriptionId(prescriptionId);
    }
}
