package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.nmas.nmas.entity.*;
import za.co.nmas.nmas.repository.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Reception -> nurse -> doctor flow. Every query is scoped to one facility,
 * so each facility only ever works with its own queue.
 */
@Service
public class WalkInVisitService {

    public static final List<String> ACTIVE = List.of("WAITING_VITALS", "WAITING_DOCTOR", "IN_CONSULTATION");

    private final WalkInVisitRepository repo;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final DoctorRepository doctorRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    public WalkInVisitService(WalkInVisitRepository repo, PatientRepository patientRepository,
                              AppointmentRepository appointmentRepository, DoctorRepository doctorRepository,
                              StaffMemberRepository staffMemberRepository, NotificationService notificationService,
                              AuditLogService auditLogService) {
        this.repo = repo;
        this.patientRepository = patientRepository;
        this.appointmentRepository = appointmentRepository;
        this.doctorRepository = doctorRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------------ reception

    @Transactional
    public WalkInVisit checkInWalkIn(Long patientId, Facility facility, String service, String reason, String receptionistEmail) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new IllegalArgumentException("Patient not found"));
        if (service == null || service.isBlank())
            throw new IllegalArgumentException("Please choose which doctor/service the patient came for.");
        if (repo.existsByPatientIdAndFacilityIdAndStatusIn(patientId, facility.getId(), ACTIVE))
            throw new IllegalArgumentException(patient.getFullName() + " is already checked in and waiting at this facility.");

        WalkInVisit v = new WalkInVisit();
        v.setPatient(patient);
        v.setFacility(facility);
        v.setVisitType("WALK_IN");
        v.setRequestedService(service.trim());
        v.setReason(reason != null && !reason.isBlank() ? reason.trim() : null);
        v.setRegisteredBy(receptionistEmail);
        WalkInVisit saved = repo.save(v);
        afterCheckIn(saved);
        return saved;
    }

    /** Patient arrives for a booked (usually follow-up) appointment. */
    @Transactional
    public WalkInVisit checkInAppointment(Long appointmentId, Facility facility, String receptionistEmail) {
        Appointment a = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new IllegalArgumentException("Appointment not found"));
        if (repo.existsByAppointmentIdAndStatusNot(appointmentId, "CANCELLED"))
            throw new IllegalArgumentException("This appointment has already been checked in.");
        if (repo.existsByPatientIdAndFacilityIdAndStatusIn(a.getPatient().getId(), facility.getId(), ACTIVE))
            throw new IllegalArgumentException(a.getPatient().getFullName() + " is already checked in and waiting at this facility.");

        WalkInVisit v = new WalkInVisit();
        v.setPatient(a.getPatient());
        v.setFacility(facility);
        v.setVisitType("APPOINTMENT");
        v.setAppointment(a);
        v.setRequestedService(a.getDoctor() != null && a.getDoctor().getSpecialization() != null
                ? a.getDoctor().getSpecialization() : "Booked appointment");
        v.setReason(a.getReason());
        v.setRegisteredBy(receptionistEmail);
        WalkInVisit saved = repo.save(v);
        afterCheckIn(saved);
        return saved;
    }

    private void afterCheckIn(WalkInVisit v) {
        auditLogService.log("WALK_IN_REGISTERED", "WalkInVisit", v.getId(),
                v.getPatient().getFullName() + " checked in for " + v.getRequestedService()
                        + " (" + v.getVisitType() + ") at " + v.getFacility().getFacilityName());
        for (StaffMember nurse : staffMemberRepository.findByFacilityIdAndRole(v.getFacility().getId(), "NURSE")) {
            notificationService.notify(nurse.getEmail(), "WALK_IN_ARRIVED",
                    v.getPatient().getFullName() + " has arrived for " + v.getRequestedService() + " — vitals needed.",
                    "/nurse/queue");
        }
    }

    @Transactional
    public void cancel(Long id, Long facilityId) {
        WalkInVisit v = forFacility(id, facilityId);
        if (!"WAITING_VITALS".equals(v.getStatus()) && !"WAITING_DOCTOR".equals(v.getStatus()))
            throw new IllegalArgumentException("This visit can no longer be cancelled.");
        v.setStatus("CANCELLED");
        repo.save(v);
        auditLogService.log("WALK_IN_CANCELLED", "WalkInVisit", id, v.getPatient().getFullName());
    }

    // ---------------------------------------------------------------- nurse

    /**
     * Elderly patients (by ID number) go first, then booked follow-ups ahead of walk-ins;
     * within each group, first come first served.
     */
    private static List<WalkInVisit> priorityFirst(List<WalkInVisit> in) {
        List<WalkInVisit> out = new java.util.ArrayList<>(in);
        out.sort(java.util.Comparator
                .comparing((WalkInVisit v) -> v.isPriorityElderly() ? 0 : 1)
                .thenComparing(v -> "APPOINTMENT".equals(v.getVisitType()) ? 0 : 1));
        return out; // List.sort is stable, so arrival order is kept inside each group
    }

    public List<WalkInVisit> awaitingVitals(Long facilityId) {
        return priorityFirst(repo.findByFacilityIdAndStatusInOrderByArrivalTimeAsc(facilityId, List.of("WAITING_VITALS")));
    }

    public List<WalkInVisit> activeAtFacility(Long facilityId) {
        return priorityFirst(repo.findByFacilityIdAndStatusInOrderByArrivalTimeAsc(facilityId, ACTIVE));
    }

    public List<WalkInVisit> todayAtFacility(Long facilityId) {
        return repo.findByFacilityIdAndArrivalTimeAfterOrderByArrivalTimeDesc(facilityId, LocalDate.now().atStartOfDay());
    }

    public WalkInVisit forFacility(Long id, Long facilityId) {
        WalkInVisit v = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Visit not found"));
        if (v.getFacility() == null || !v.getFacility().getId().equals(facilityId))
            throw new IllegalArgumentException("That visit belongs to a different facility.");
        return v;
    }

    @Transactional
    public WalkInVisit saveVitalsAndSend(Long id, Facility facility, Integer sys, Integer dia, Integer hr, Double temp,
                                         Double weight, Double height, Integer spo2, Double glucose, String hiv,
                                         String notes, Long doctorId, String nurseEmail) {
        WalkInVisit v = forFacility(id, facility.getId());
        if (!"WAITING_VITALS".equals(v.getStatus()))
            throw new IllegalArgumentException("Vitals were already recorded for this visit.");
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new IllegalArgumentException("Please choose a doctor to send the patient to."));
        if (doctor.getFacility() == null || !doctor.getFacility().getId().equals(facility.getId()))
            throw new IllegalArgumentException("You can only send patients to doctors at your own facility.");

        v.setBpSystolic(sys); v.setBpDiastolic(dia); v.setHeartRate(hr); v.setTemperature(temp);
        v.setWeightKg(weight); v.setHeightCm(height); v.setOxygenSaturation(spo2); v.setBloodGlucose(glucose);
        v.setHivStatus(hiv != null && !hiv.isBlank() ? hiv : null);
        v.setNurseNotes(notes != null && !notes.isBlank() ? notes.trim() : null);
        v.setVitalsRecordedBy(nurseEmail);
        v.setVitalsRecordedAt(LocalDateTime.now());
        v.setAssignedDoctor(doctor);
        v.setSentToDoctorAt(LocalDateTime.now());
        v.setStatus("WAITING_DOCTOR");
        WalkInVisit saved = repo.save(v);

        auditLogService.log("VITALS_RECORDED", "WalkInVisit", id,
                v.getPatient().getFullName() + " — vitals recorded, sent to Dr. " + doctor.getFullName());
        if (doctor.getEmail() != null) {
            notificationService.notify(doctor.getEmail(), "PATIENT_READY",
                    v.getPatient().getFullName() + " is ready to see you (" + v.getRequestedService() + ") — vitals recorded.",
                    "/doctor/queue");
        }
        return saved;
    }

    // --------------------------------------------------------------- doctor

    public List<WalkInVisit> doctorQueue(Long doctorId) {
        return priorityFirst(repo.findByAssignedDoctorIdAndStatusInOrderBySentToDoctorAtAsc(doctorId, List.of("WAITING_DOCTOR", "IN_CONSULTATION")));
    }

    public long doctorWaitingCount(Long doctorId) {
        return repo.findByAssignedDoctorIdAndStatusInOrderBySentToDoctorAtAsc(doctorId, List.of("WAITING_DOCTOR")).size();
    }

    /**
     * Marks the visit as in consultation and returns the appointment the
     * existing consultation screen works with. Walk-ins have no appointment
     * yet, so one is created (approved, for right now) — that way the
     * consultation, prescription and dispensing steps stay exactly as they were.
     */
    @Transactional
    public Long startConsultation(Long id, Doctor doctor) {
        WalkInVisit v = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Visit not found"));
        if (v.getAssignedDoctor() == null || !v.getAssignedDoctor().getId().equals(doctor.getId()))
            throw new IllegalArgumentException("This patient was sent to a different doctor.");
        if (v.getAppointment() == null) {
            Appointment a = new Appointment();
            a.setPatient(v.getPatient());
            a.setDoctor(doctor);
            a.setFacility(v.getFacility());
            a.setAppointmentDate(LocalDate.now());
            a.setAppointmentTime(LocalTime.now().withSecond(0).withNano(0));
            a.setReason("Walk-in: " + (v.getRequestedService() != null ? v.getRequestedService() : "consultation")
                    + (v.getReason() != null ? " — " + v.getReason() : ""));
            a.setStatus("APPROVED");
            v.setAppointment(appointmentRepository.save(a));
        } else if (!"APPROVED".equals(v.getAppointment().getStatus()) && !"COMPLETED".equals(v.getAppointment().getStatus())) {
            v.getAppointment().setStatus("APPROVED");
            appointmentRepository.save(v.getAppointment());
        }
        if (!"IN_CONSULTATION".equals(v.getStatus())) {
            v.setStatus("IN_CONSULTATION");
            v.setConsultationStartedAt(LocalDateTime.now());
        }
        repo.save(v);
        auditLogService.log("WALK_IN_CONSULTATION_STARTED", "WalkInVisit", id, v.getPatient().getFullName());
        return v.getAppointment().getId();
    }

    /** Called after the doctor saves the consultation, so the queue entry closes itself. */
    @Transactional
    public void completeForAppointment(Long appointmentId) {
        repo.findFirstByAppointmentId(appointmentId).ifPresent(v -> {
            v.setStatus("COMPLETED");
            repo.save(v);
        });
    }

    /** The nurse's recorded vitals for this appointment's visit, or null if none were recorded. */
    public WalkInVisit vitalsForAppointment(Long appointmentId) {
        return repo.findFirstByAppointmentId(appointmentId)
                .filter(v -> v.getVitalsRecordedAt() != null)
                .orElse(null);
    }

    public List<WalkInVisit> historyForPatient(Long patientId) {
        return repo.findByPatientIdOrderByArrivalTimeDesc(patientId);
    }
}
