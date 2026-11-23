package za.co.nmas.nmas.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.nmas.nmas.entity.*;
import za.co.nmas.nmas.repository.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * One-time demo data: creates five chronic (6-month, monthly-collection) prescriptions at
 * Themba Hospital, issued by Dr. Thabo Nkosi and Dr. Ahmed Patel (both General Practitioners
 * there), for patients each doctor has already seen (an existing appointment with that
 * doctor). A pill collection record is created directly for each one (rather than waiting
 * for the normal 1st-of-the-month generator) so the demo shows collections spread across
 * the month instead of all bunched on day one: two land on the 19th of this month, the
 * rest on the 20th and 21st.
 *
 * Runs once at startup; skips itself on every later run by checking a marker it leaves
 * behind (a note on the first prescription it creates).
 */
@Service
@Order(200) // after DataSeeder (patients/doctors/medications/appointments) and StockBaselineService (inventory)
public class ChronicPrescriptionDemoSeeder implements ApplicationRunner {

    private static final String MARKER = "CHRONIC_DEMO_SEED_V2";
    private static final String FACILITY_NAME = "Themba Hospital";
    private static final List<String> DOCTOR_NAMES = List.of("Thabo Nkosi", "Ahmed Patel");
    /** Collection window day-of-month for each of the five demo prescriptions, in order. */
    private static final int[] WINDOW_DAYS = {19, 19, 20, 20, 21};

    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final FacilityRepository facilityRepository;
    private final AppointmentRepository appointmentRepository;
    private final MedicationRepository medicationRepository;
    private final ConsultationRepository consultationRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final PrescriptionMedicationRepository prescriptionMedicationRepository;
    private final PrescriptionNumberService prescriptionNumberService;
    private final DispensaryService dispensaryService;
    private final PillCollectionRepository pillCollectionRepository;

    public ChronicPrescriptionDemoSeeder(PatientRepository patientRepository,
                                         DoctorRepository doctorRepository,
                                         FacilityRepository facilityRepository,
                                         AppointmentRepository appointmentRepository,
                                         MedicationRepository medicationRepository,
                                         ConsultationRepository consultationRepository,
                                         PrescriptionRepository prescriptionRepository,
                                         PrescriptionMedicationRepository prescriptionMedicationRepository,
                                         PrescriptionNumberService prescriptionNumberService,
                                         DispensaryService dispensaryService,
                                         PillCollectionRepository pillCollectionRepository) {
        this.patientRepository = patientRepository;
        this.doctorRepository = doctorRepository;
        this.facilityRepository = facilityRepository;
        this.appointmentRepository = appointmentRepository;
        this.medicationRepository = medicationRepository;
        this.consultationRepository = consultationRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.prescriptionMedicationRepository = prescriptionMedicationRepository;
        this.prescriptionNumberService = prescriptionNumberService;
        this.dispensaryService = dispensaryService;
        this.pillCollectionRepository = pillCollectionRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            if (prescriptionRepository.findAll().stream()
                    .anyMatch(p -> MARKER.equals(p.getInstructions()))) {
                return; // already seeded on an earlier startup
            }

            Facility themba = facilityRepository.findAll().stream()
                    .filter(f -> FACILITY_NAME.equalsIgnoreCase(f.getFacilityName()))
                    .findFirst().orElse(null);
            if (themba == null) {
                System.err.println("[NMAS] Chronic prescription demo seeding skipped: "
                        + FACILITY_NAME + " not found.");
                return;
            }

            List<Doctor> doctors = doctorRepository.findAll().stream()
                    .filter(d -> themba.getId().equals(d.getFacility() != null ? d.getFacility().getId() : null))
                    .filter(d -> DOCTOR_NAMES.contains(d.getFullName()))
                    .toList();
            if (doctors.isEmpty()) {
                System.err.println("[NMAS] Chronic prescription demo seeding skipped: "
                        + String.join(" / ", DOCTOR_NAMES) + " not found at " + FACILITY_NAME + ".");
                return;
            }

            List<Medication> medications = medicationRepository.findByActiveTrue();
            if (medications.isEmpty()) return;

            // Only patients each doctor has actually seen before (an existing appointment
            // with that doctor) — round-robin across the available doctors so both are used.
            record Pair(Doctor doctor, Patient patient) {}
            List<Pair> pairs = new java.util.ArrayList<>();
            for (Doctor doc : doctors) {
                for (Patient p : appointmentRepository.findPatientsByDoctorId(doc.getId())) {
                    pairs.add(new Pair(doc, p));
                }
            }
            if (pairs.isEmpty()) {
                System.err.println("[NMAS] Chronic prescription demo seeding skipped: "
                        + "none of " + String.join(" / ", DOCTOR_NAMES) + " has any patients with a past appointment yet.");
                return;
            }

            YearMonth thisMonth = YearMonth.now();
            int made = 0;
            for (int i = 0; i < pairs.size() && made < 5; i++) {
                Pair pair = pairs.get(i % pairs.size());
                Doctor doctor = pair.doctor();
                Patient patient = pair.patient();
                Medication medication = medications.get(made % medications.size());

                Consultation consultation = new Consultation();
                consultation.setPatient(patient);
                consultation.setDiagnosis("Chronic condition — repeat medication (demo data)");
                consultation.setTreatmentPlan("Monthly repeat dispensing of " + medication.getMedicationName());
                consultation.setConsultationDate(LocalDateTime.now());
                consultation = consultationRepository.save(consultation);

                Prescription prescription = new Prescription();
                prescription.setPatient(patient);
                prescription.setDoctor(doctor);
                prescription.setConsultation(consultation);
                // Issued a month ago, so this is already past the first fill and due for a repeat.
                prescription.setPrescriptionDate(LocalDate.now().minusMonths(1));
                prescription.setStatus("ACTIVE");
                prescription.setInstructions(MARKER); // first one carries the marker; harmless on the rest
                prescriptionNumberService.assign(prescription);
                // 6 months, chronic — qualifies for monthly repeat pill collection.
                prescription.setValidUntil(LocalDate.now().plusMonths(6));
                prescription = prescriptionRepository.save(prescription);

                PrescriptionMedication line = new PrescriptionMedication();
                line.setPrescription(prescription);
                line.setMedication(medication);
                line.setDosage(medication.getStrength() != null ? medication.getStrength() : "As directed");
                line.setFrequency("Once daily");
                line.setDuration("6 months");
                line.setRoute("Oral");
                line.setMealInstruction("With food");
                line.setMorning(true);
                line.setInstructions("Chronic repeat — collect monthly (demo data)");
                line = prescriptionMedicationRepository.save(line);

                dispensaryService.createForPrescriptionMedication(prescription, line);

                // Spread demo collections across the month (19th/20th/21st) instead of the
                // usual 1st-4th window, so reception shows them landing on different days.
                int day = WINDOW_DAYS[made % WINDOW_DAYS.length];
                day = Math.min(day, thisMonth.lengthOfMonth());
                LocalDate windowStart = thisMonth.atDay(day);
                LocalDate windowEnd = windowStart; // one-day window for this demo collection

                PillCollection collection = new PillCollection();
                collection.setPrescription(prescription);
                collection.setPatient(patient);
                collection.setFacility(themba);
                collection.setWindowStart(windowStart);
                collection.setWindowEnd(windowEnd);
                pillCollectionRepository.save(collection);

                made++;
            }
            if (made > 0) {
                System.out.println("[NMAS] Seeded " + made + " demo chronic prescriptions at "
                        + FACILITY_NAME + " (Dr. Nkosi / Dr. Patel) for repeat pill collection, spread across the 19th-21st.");
            }
        } catch (Exception e) {
            System.err.println("[NMAS] Chronic prescription demo seeding skipped: " + e.getMessage());
        }
    }
}
