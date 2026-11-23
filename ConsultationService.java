package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Appointment;
import za.co.nmas.nmas.entity.Consultation;
import za.co.nmas.nmas.repository.AppointmentRepository;
import za.co.nmas.nmas.repository.ConsultationRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConsultationService {

    private final ConsultationRepository consultationRepository;
    private final AppointmentRepository appointmentRepository;
    private final AuditLogService auditLogService;

    public ConsultationService(ConsultationRepository consultationRepository,
                               AppointmentRepository appointmentRepository,
                               AuditLogService auditLogService) {
        this.consultationRepository = consultationRepository;
        this.appointmentRepository = appointmentRepository;
        this.auditLogService = auditLogService;
    }

    public Consultation save(Long appointmentId, String symptoms, String observation, String assessment) {

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new RuntimeException("Appointment not found"));

        Consultation consultation = new Consultation();
        consultation.setAppointment(appointment);
        consultation.setPatient(appointment.getPatient());
        consultation.setSymptoms(symptoms);
        consultation.setObservation(observation);
        // "Assessment" is stored in the existing diagnosis column so that
        // other parts of the app that already read diagnosis (medical
        // history, visit records, completion counts) keep working unchanged.
        consultation.setDiagnosis(assessment);
        consultation.setConsultationDate(LocalDateTime.now());
        consultationRepository.save(consultation);

        appointment.setStatus("COMPLETED");
        appointmentRepository.save(appointment);

        auditLogService.log("CONSULTATION_CREATED", "Consultation", consultation.getId(),
                "Patient: " + appointment.getPatient().getFullName());
        return consultation;
    }

    public Consultation update(Long id, String symptoms, String observation, String assessment) {
        Consultation consultation = findById(id);
        consultation.setSymptoms(symptoms);
        consultation.setObservation(observation);
        consultation.setDiagnosis(assessment);
        Consultation saved = consultationRepository.save(consultation);
        auditLogService.log("CONSULTATION_UPDATED", "Consultation", id, null);
        return saved;
    }

    public List<Consultation> findAll() { return consultationRepository.findAll(); }
    public Consultation findById(Long id) {
        return consultationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Consultation not found"));
    }
    public List<Consultation> findByPatientId(Long patientId) { return consultationRepository.findByPatientId(patientId); }
    public List<Consultation> findByDoctorId(Long doctorId) { return consultationRepository.findByAppointmentDoctorId(doctorId); }
    public long count() { return consultationRepository.count(); }
    public long countByDoctorId(Long doctorId) { return consultationRepository.countByAppointmentDoctorId(doctorId); }

    public long countToday() {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        return consultationRepository.countByConsultationDateBetween(start, start.plusDays(1));
    }

    public long countTodayByDoctorId(Long doctorId) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        return consultationRepository.countByAppointmentDoctorIdAndConsultationDateBetween(
                doctorId, start, start.plusDays(1));
    }

    public long countCompleted() {
        return consultationRepository.countByDiagnosisIsNotNull();
    }

    public long countCompletedByDoctorId(Long doctorId) {
        return consultationRepository.countByAppointmentDoctorIdAndDiagnosisIsNotNull(doctorId);
    }
}
