package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Consultation;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.Medication;
import za.co.nmas.nmas.entity.Prescription;
import za.co.nmas.nmas.entity.PrescriptionMedication;
import za.co.nmas.nmas.repository.ConsultationRepository;
import za.co.nmas.nmas.repository.MedicationRepository;
import za.co.nmas.nmas.repository.PrescriptionMedicationRepository;
import za.co.nmas.nmas.repository.PrescriptionRepository;

import java.time.LocalDate;
import java.util.List;

@Service
public class PrescriptionService {

    private final PrescriptionRepository prescriptionRepository;
    private final ConsultationRepository consultationRepository;
    private final MedicationRepository medicationRepository;
    private final PrescriptionMedicationRepository prescriptionMedicationRepository;
    private final AuditLogService auditLogService;
    private final DispensaryService dispensaryService;
    private final PrescriptionNumberService prescriptionNumberService;
    private final MedicationService medicationService;

    public PrescriptionService(PrescriptionRepository prescriptionRepository,
                               ConsultationRepository consultationRepository,
                               MedicationRepository medicationRepository,
                               PrescriptionMedicationRepository prescriptionMedicationRepository,
                               AuditLogService auditLogService,
                               DispensaryService dispensaryService,
                               PrescriptionNumberService prescriptionNumberService,
                               MedicationService medicationService) {
        this.medicationService = medicationService;
        this.prescriptionRepository = prescriptionRepository;
        this.consultationRepository = consultationRepository;
        this.medicationRepository = medicationRepository;
        this.prescriptionMedicationRepository = prescriptionMedicationRepository;
        this.auditLogService = auditLogService;
        this.dispensaryService = dispensaryService;
        this.prescriptionNumberService = prescriptionNumberService;
    }

    // Original method — preserved for backward compatibility
    public Prescription save(Long consultationId, String medicationName, String dosage,
                             String frequency, String duration, String instructions,
                             Doctor doctor) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() -> new RuntimeException("Consultation not found"));

        Prescription prescription = new Prescription();
        prescription.setPatient(consultation.getPatient());
        prescription.setConsultation(consultation);
        if (doctor != null) prescription.setDoctor(doctor);
        prescription.setMedicationName(medicationName);
        prescription.setDosage(dosage);
        prescription.setFrequency(frequency);
        prescription.setDuration(duration);
        prescription.setInstructions(instructions);
        prescription.setPrescriptionDate(LocalDate.now());
        prescription.setStatus("ACTIVE");

        prescriptionNumberService.assign(prescription);
        applyValidity(prescription, null, duration);
        Prescription saved = prescriptionRepository.save(prescription);
        auditLogService.log("PRESCRIPTION_CREATED", "Prescription", saved.getId(),
                "Patient: " + consultation.getPatient().getFullName());
        return saved;
    }

    // New method — creates prescription + one structured medication line
    public Prescription saveWithMedication(Long consultationId, Long medicationId,
                                            String dosage, String frequency, String duration,
                                            String route, String mealInstruction,
                                            boolean morning, boolean afternoon,
                                            boolean evening, boolean night,
                                            String instructions, Doctor doctor) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() -> new RuntimeException("Consultation not found"));
        Medication medication = medicationRepository.findById(medicationId)
                .orElseThrow(() -> new RuntimeException("Medication not found"));
        medicationService.assertPrescribable(medication, doctor != null ? doctor.getFacility() : null);

        Prescription prescription = new Prescription();
        prescription.setPatient(consultation.getPatient());
        prescription.setConsultation(consultation);
        if (doctor != null) prescription.setDoctor(doctor);
        prescription.setPrescriptionDate(LocalDate.now());
        prescription.setStatus("ACTIVE");
        prescription.setInstructions(instructions);

        prescriptionNumberService.assign(prescription);
        applyValidity(prescription, null, duration);
        Prescription saved = prescriptionRepository.save(prescription);

        PrescriptionMedication pm = new PrescriptionMedication();
        pm.setPrescription(saved);
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
        PrescriptionMedication savedPm = prescriptionMedicationRepository.save(pm);
        dispensaryService.createForPrescriptionMedication(saved, savedPm);

        auditLogService.log("PRESCRIPTION_CREATED", "Prescription", saved.getId(),
                "Patient: " + consultation.getPatient().getFullName()
                + " — " + medication.getMedicationName() + " " + medication.getStrength());
        return saved;
    }

    public List<Prescription> findAll() {
        return prescriptionRepository.findAll();
    }

    public List<Prescription> findByPatientId(Long patientId) {
        return prescriptionRepository.findByPatientId(patientId);
    }

    public List<Prescription> findByFacilityId(Long facilityId) {
        return prescriptionRepository.findByDoctorFacilityIdOrderByIdDesc(facilityId);
    }

    public long countByFacilityId(Long facilityId) {
        return prescriptionRepository.countByDoctorFacilityId(facilityId);
    }

    public long countByFacilityIdAndStatus(Long facilityId, String status) {
        return prescriptionRepository.countByDoctorFacilityIdAndStatus(facilityId, status);
    }

    /** Sets how many days from issue the prescription stays valid (default 30, longer for chronic/repeat courses). */
    public void applyValidity(Prescription p, Integer requestedDays, String firstDuration) {
        int days = 30;
        if (requestedDays != null && requestedDays > 0) {
            days = Math.min(requestedDays, 365);
        } else if (firstDuration != null) {
            String d = firstDuration.toLowerCase();
            if (d.contains("ongoing") || d.contains("chronic") || d.contains("month")) days = 90;
        }
        java.time.LocalDate base = p.getPrescriptionDate() != null ? p.getPrescriptionDate() : LocalDate.now();
        p.setValidUntil(base.plusDays(days));
    }

    /** Prescriptions this patient received at OTHER facilities in the last N days (for the doctor's and pharmacist's awareness). */
    public List<Prescription> issuedElsewhere(Long patientId, Long facilityId, int days) {
        LocalDate cutoff = LocalDate.now().minusDays(days);
        return prescriptionRepository.findByPatientId(patientId).stream()
                .filter(p -> p.getPrescriptionDate() != null && !p.getPrescriptionDate().isBefore(cutoff))
                .filter(p -> p.getIssuingFacility() != null && (facilityId == null || !p.getIssuingFacility().getId().equals(facilityId)))
                .sorted(java.util.Comparator.comparing(Prescription::getPrescriptionDate).reversed())
                .toList();
    }

    public Prescription setValidityDays(Long id, int days) {
        Prescription p = findById(id);
        applyValidity(p, days, null);
        return prescriptionRepository.save(p);
    }

    public List<Prescription> findByDoctorId(Long doctorId) {
        return prescriptionRepository.findByDoctorId(doctorId);
    }

    public long count() {
        return prescriptionRepository.count();
    }

    public long countByStatus(String status) {
        return prescriptionRepository.countByStatus(status);
    }

    public Prescription update(Long id, String medicationName, String dosage,
                               String frequency, String duration, String instructions, String status) {
        Prescription prescription = prescriptionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Prescription not found"));
        prescription.setMedicationName(medicationName);
        prescription.setDosage(dosage);
        prescription.setFrequency(frequency);
        prescription.setDuration(duration);
        prescription.setInstructions(instructions);
        prescription.setStatus(status);
        prescriptionNumberService.assign(prescription);
        Prescription saved = prescriptionRepository.save(prescription);
        auditLogService.log("PRESCRIPTION_UPDATED", "Prescription", id, null);
        return saved;
    }

    public Prescription updateStatus(Long id, String instructions, String status) {
        Prescription prescription = prescriptionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Prescription not found"));
        if (instructions != null) prescription.setInstructions(instructions);
        prescription.setStatus(status);
        prescriptionNumberService.assign(prescription);
        Prescription saved = prescriptionRepository.save(prescription);
        auditLogService.log("PRESCRIPTION_UPDATED", "Prescription", id, "Status: " + status);
        return saved;
    }

    public Prescription findById(Long id) {
        return prescriptionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Prescription not found"));
    }

    public long countByDoctorId(Long doctorId) {
        return prescriptionRepository.countByDoctorId(doctorId);
    }
}
