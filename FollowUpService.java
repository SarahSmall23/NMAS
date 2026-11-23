package za.co.nmas.nmas.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Appointment;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.Patient;
import za.co.nmas.nmas.entity.StaffMember;
import za.co.nmas.nmas.repository.AppointmentRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A follow-up is booked by the DOCTOR (patients no longer book physical
 * appointments themselves). It is approved immediately, the patient is
 * notified on their dashboard and by email with a PDF slip, the facility's
 * receptionists are told, and on arrival the patient goes ahead of the queue.
 */
@Service
public class FollowUpService {

    private final AppointmentRepository appointmentRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final StaffMemberRepository staffMemberRepository;
    private final AppointmentPdfService pdfService;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    public FollowUpService(AppointmentRepository appointmentRepository, AuditLogService auditLogService,
                           NotificationService notificationService, StaffMemberRepository staffMemberRepository,
                           AppointmentPdfService pdfService, JavaMailSender mailSender) {
        this.appointmentRepository = appointmentRepository;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
        this.staffMemberRepository = staffMemberRepository;
        this.pdfService = pdfService;
        this.mailSender = mailSender;
    }

    public Appointment book(Patient patient, Doctor doctor, LocalDate date, LocalTime time, String reason, String doctorEmail) {
        if (patient == null) throw new IllegalArgumentException("Please search for and select the patient.");
        if (doctor == null) throw new IllegalArgumentException("Only doctors can book follow-up appointments.");
        if (date == null || date.isBefore(LocalDate.now())) throw new IllegalArgumentException("The follow-up date cannot be in the past.");
        if (time == null) throw new IllegalArgumentException("Please choose a time.");
        if (date.isEqual(LocalDate.now()) && time.isBefore(LocalTime.now())) throw new IllegalArgumentException("The time cannot be in the past.");
        for (Appointment other : appointmentRepository.findByDoctorId(doctor.getId())) {
            if (other.getAppointmentDate() != null && other.getAppointmentTime() != null
                    && other.getAppointmentDate().equals(date) && other.getAppointmentTime().equals(time)
                    && !"REJECTED".equals(other.getStatus()) && !"CANCELLED".equals(other.getStatus())) {
                throw new IllegalArgumentException("You already have an appointment on " + date + " at " + time + ". Please choose another time.");
            }
        }

        Appointment a = new Appointment();
        a.setPatient(patient);
        a.setDoctor(doctor);
        a.setFacility(doctor.getFacility());
        a.setAppointmentDate(date);
        a.setAppointmentTime(time);
        a.setReason(reason != null && !reason.isBlank() ? reason.trim() : "Follow-up / check-up");
        a.setStatus("APPROVED");
        a.setFollowUp(true);
        a.setBookedBy(doctorEmail);
        Appointment saved = appointmentRepository.save(a);

        auditLogService.log("FOLLOW_UP_BOOKED", "Appointment", saved.getId(),
                "Dr. " + doctor.getFullName() + " booked a follow-up for " + patient.getFullName() + " on " + date + " at " + time);

        String when = DateTimeFormatter.ofPattern("dd MMM yyyy").format(date) + " at " + time;
        if (patient.getEmail() != null) {
            notificationService.notify(patient.getEmail(), "FOLLOW_UP_BOOKED",
                    "Dr. " + doctor.getFullName() + " booked a follow-up appointment for you on " + when
                            + (doctor.getFacility() != null ? " at " + doctor.getFacility().getFacilityName() : "")
                            + ". You can download your appointment slip from your dashboard.",
                    "/patient-dashboard");
            emailSlip(saved);
        }
        if (doctor.getFacility() != null) {
            List<StaffMember> reception = staffMemberRepository.findByFacilityIdAndRole(doctor.getFacility().getId(), "RECEPTIONIST");
            for (StaffMember r : reception) {
                notificationService.notify(r.getEmail(), "FOLLOW_UP_BOOKED",
                        "Follow-up booked: " + patient.getFullName() + " with Dr. " + doctor.getFullName() + " on " + when + ".",
                        "/reception/walk-ins");
            }
        }
        return saved;
    }

    public void emailSlip(Appointment a) {
        try {
            if (a.getPatient() == null || a.getPatient().getEmail() == null || a.getPatient().getEmail().isBlank()) return;
            MimeMessage m = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(m, true);
            h.setFrom(fromAddress);
            h.setTo(a.getPatient().getEmail());
            h.setSubject("Your NMAS follow-up appointment - " + a.getAppointmentDate());
            h.setText("Hi " + a.getPatient().getFullName() + ",\n\nYour doctor has booked a follow-up appointment for you on "
                    + a.getAppointmentDate() + " at " + a.getAppointmentTime()
                    + (a.getFacility() != null ? " at " + a.getFacility().getFacilityName() : "")
                    + ".\n\nYour appointment slip is attached. You can also download it from your NMAS dashboard.\n\n- NMAS");
            h.addAttachment("NMAS-Appointment-" + a.getId() + ".pdf",
                    new org.springframework.core.io.ByteArrayResource(pdfService.generate(a)));
            mailSender.send(m);
        } catch (Exception e) {
            System.err.println("[NMAS] Could not email appointment slip: " + e);
        }
    }

    public void patientConfirm(Long appointmentId, String patientEmail) {
        Appointment a = appointmentRepository.findById(appointmentId).orElseThrow(() -> new IllegalArgumentException("Appointment not found"));
        if (a.getPatient() == null || a.getPatient().getEmail() == null || !a.getPatient().getEmail().equalsIgnoreCase(patientEmail))
            throw new IllegalArgumentException("That is not your appointment.");
        a.setPatientConfirmed(true);
        appointmentRepository.save(a);
        if (a.getDoctor() != null && a.getDoctor().getEmail() != null) {
            notificationService.notify(a.getDoctor().getEmail(), "FOLLOW_UP_CONFIRMED",
                    a.getPatient().getFullName() + " confirmed the follow-up on " + a.getAppointmentDate() + " at " + a.getAppointmentTime() + ".",
                    "/follow-up");
        }
    }

    public List<Appointment> forDoctor(Long doctorId) {
        return appointmentRepository.findByDoctorId(doctorId).stream()
                .filter(Appointment::isFollowUp)
                .sorted(java.util.Comparator.comparing(Appointment::getAppointmentDate, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }
}
