package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Appointment;
import za.co.nmas.nmas.entity.Patient;
import za.co.nmas.nmas.repository.AppointmentRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              AuditLogService auditLogService,
                              NotificationService notificationService) {
        this.appointmentRepository = appointmentRepository;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
    }

    public Appointment book(Appointment appointment) {
        if (appointment.getStatus() == null || appointment.getStatus().isBlank()) {
            appointment.setStatus("PENDING");
        }
        Appointment saved = appointmentRepository.save(appointment);
        auditLogService.log("APPOINTMENT_BOOKED", "Appointment", saved.getId(),
                "Patient: " + saved.getPatient().getFullName());

        if (saved.getDoctor() != null && saved.getDoctor().getEmail() != null) {
            String when = saved.getAppointmentDate() != null
                    ? DateTimeFormatter.ofPattern("dd MMM yyyy").format(saved.getAppointmentDate())
                            + (saved.getAppointmentTime() != null ? " at " + saved.getAppointmentTime() : "")
                    : "a date to be confirmed";
            notificationService.notify(saved.getDoctor().getEmail(), "APPOINTMENT_BOOKED",
                    saved.getPatient().getFullName() + " booked an appointment with you for " + when + ".",
                    "/appointments");
        }
        return saved;
    }

    public void approve(Long id) {
        Appointment appointment = findById(id);
        appointment.setStatus("APPROVED");
        appointmentRepository.save(appointment);
        auditLogService.log("APPOINTMENT_APPROVED", "Appointment", id, null);

        if (appointment.getPatient() != null && appointment.getPatient().getEmail() != null) {
            String when = appointment.getAppointmentDate() != null
                    ? DateTimeFormatter.ofPattern("dd MMM yyyy").format(appointment.getAppointmentDate())
                            + (appointment.getAppointmentTime() != null ? " at " + appointment.getAppointmentTime() : "")
                    : "your requested time";
            String withDoctor = appointment.getDoctor() != null ? " with Dr. " + appointment.getDoctor().getFullName() : "";
            notificationService.notify(appointment.getPatient().getEmail(), "APPOINTMENT_APPROVED",
                    "Your appointment" + withDoctor + " on " + when + " has been approved.",
                    "/patient-dashboard");
        }
    }

    public void reject(Long id) {
        Appointment appointment = findById(id);
        appointment.setStatus("REJECTED");
        appointmentRepository.save(appointment);
        auditLogService.log("APPOINTMENT_REJECTED", "Appointment", id, null);

        if (appointment.getPatient() != null && appointment.getPatient().getEmail() != null) {
            notificationService.notify(appointment.getPatient().getEmail(), "APPOINTMENT_REJECTED",
                    "Your appointment request has been declined. Please contact the facility or book a new time.",
                    "/patient-dashboard");
        }
    }

    public List<Appointment> findAll() { return appointmentRepository.findAll(); }
    public List<Appointment> findByPatientId(Long patientId) { return appointmentRepository.findByPatientId(patientId); }
    public List<Appointment> findByDoctorId(Long doctorId) { return appointmentRepository.findByDoctorId(doctorId); }
    public List<Patient> findPatientsByDoctorId(Long doctorId) { return appointmentRepository.findPatientsByDoctorId(doctorId); }

    public Appointment findById(Long id) {
        return appointmentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Appointment not found"));
    }

    public long count() { return appointmentRepository.count(); }
    public long countByStatus(String status) { return appointmentRepository.countByStatus(status); }
    public long countByDoctorId(Long doctorId) { return appointmentRepository.countByDoctorId(doctorId); }
    public long countByDoctorIdAndStatus(Long doctorId, String status) { return appointmentRepository.countByDoctorIdAndStatus(doctorId, status); }
    public long countTodayByDoctorId(Long doctorId) { return appointmentRepository.countByDoctorIdAndAppointmentDate(doctorId, LocalDate.now()); }

    public boolean doctorHasPatient(Long doctorId, Long patientId) {
        return appointmentRepository.existsByDoctorIdAndPatientIdAndStatusIn(
                doctorId, patientId, List.of("APPROVED", "COMPLETED"));
    }

    public long countByDate(LocalDate date) { return appointmentRepository.countByAppointmentDate(date); }
}
