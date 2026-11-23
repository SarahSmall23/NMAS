package za.co.nmas.nmas.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import za.co.nmas.nmas.entity.Consultation;
import za.co.nmas.nmas.entity.Prescription;
import za.co.nmas.nmas.entity.VideoConsultationRequest;
import za.co.nmas.nmas.repository.AppointmentRepository;
import za.co.nmas.nmas.repository.ConsultationRepository;
import za.co.nmas.nmas.repository.DoctorRepository;
import za.co.nmas.nmas.repository.PatientRepository;
import za.co.nmas.nmas.repository.PrescriptionRepository;
import za.co.nmas.nmas.repository.VideoConsultationRequestRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class VideoConsultationService {

    private final VideoConsultationRequestRepository videoRepo;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final ConsultationRepository consultationRepository;
    private final NotificationService notificationService;
    private final PrescriptionNumberService prescriptionNumberService;

    public VideoConsultationService(VideoConsultationRequestRepository videoRepo,
                                    PatientRepository patientRepository,
                                    DoctorRepository doctorRepository,
                                    AppointmentRepository appointmentRepository,
                                    PrescriptionRepository prescriptionRepository,
                                    ConsultationRepository consultationRepository,
                                    NotificationService notificationService,
                                    PrescriptionNumberService prescriptionNumberService) {
        this.videoRepo = videoRepo;
        this.patientRepository = patientRepository;
        this.doctorRepository = doctorRepository;
        this.appointmentRepository = appointmentRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.consultationRepository = consultationRepository;
        this.notificationService = notificationService;
        this.prescriptionNumberService = prescriptionNumberService;
    }

    public VideoConsultationRequest request(Long patientId, Long doctorId,
                                            Long appointmentId, String notes) {
        VideoConsultationRequest req = new VideoConsultationRequest();
        req.setPatient(patientRepository.findById(patientId)
                .orElseThrow(() -> new RuntimeException("Patient not found")));
        req.setDoctor(doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found")));
        if (appointmentId != null) {
            appointmentRepository.findById(appointmentId).ifPresent(req::setAppointment);
        }
        req.setNotes(notes);
        req.setStatus("REQUESTED");
        VideoConsultationRequest saved = videoRepo.save(req);

        return saved;
    }

    public VideoConsultationRequest accept(Long requestId, String doctorEmail) {
        VideoConsultationRequest req = findById(requestId);
        req.setStatus("ACCEPTED");
        req.setAcceptedAt(LocalDateTime.now());
        req.setRoomId(UUID.randomUUID().toString());
        VideoConsultationRequest saved = videoRepo.save(req);

        // Notifications for request/accept are sent once, by VideoController.
        return saved;
    }

    public void end(Long requestId) {
        VideoConsultationRequest req = findById(requestId);
        req.setStatus("ENDED");
        req.setEndedAt(LocalDateTime.now());
        videoRepo.save(req);
    }

    public void cancel(Long requestId) {
        VideoConsultationRequest req = findById(requestId);
        req.setStatus("CANCELLED");
        videoRepo.save(req);
    }

    public List<VideoConsultationRequest> findByPatient(Long patientId) {
        return videoRepo.findByPatientIdOrderByRequestedAtDesc(patientId);
    }

    public List<VideoConsultationRequest> findIncoming(Long doctorId) {
        return videoRepo.findByDoctorIdAndStatusOrderByRequestedAtDesc(doctorId, "REQUESTED");
    }

    public List<VideoConsultationRequest> findAllByDoctor(Long doctorId) {
        return videoRepo.findByDoctorIdOrderByRequestedAtDesc(doctorId);
    }

    public VideoConsultationRequest findById(Long id) {
        return videoRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Video consultation request " + id + " not found — it may have been removed, or this is a stale link."));
    }

    /**
     * Returns the Consultation record backing this video call's prescription,
     * creating one on first use. This lets the doctor write a full structured
     * prescription (medication + dosage + frequency + route etc.) during the
     * video call through the exact same prescription system used for
     * in-person appointments, rather than a separate free-text hack.
     */
    public Consultation ensureConsultation(Long requestId) {
        VideoConsultationRequest req = findById(requestId);
        if (req.getConsultation() != null) {
            return req.getConsultation();
        }
        Consultation consultation = new Consultation();
        consultation.setPatient(req.getPatient());
        consultation.setAppointment(req.getAppointment()); // may be null for ad-hoc video calls
        consultation.setSymptoms(req.getNotes());
        consultation.setConsultationDate(LocalDateTime.now());
        Consultation saved = consultationRepository.save(consultation);

        req.setConsultation(saved);
        videoRepo.save(req);
        return saved;
    }

    public VideoConsultationRequest findByRoomId(String roomId) {
        return videoRepo.findByRoomId(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This video room (" + roomId + ") doesn't exist or has expired. "
                        + "This usually means the link is from an older, already-ended call — go back to "
                        + "your incoming/pending video requests and join from there instead of an old link."));
    }

    /**
     * Saves the prescription written during a video consultation into the
     * patient's official prescription record, so it shows up in the same
     * /prescriptions list used everywhere else in NMAS.
     */
    public void savePrescription(Long requestId, String prescriptionText, String doctorEmail) {
        VideoConsultationRequest req = findById(requestId);
        Prescription rx = new Prescription();
        rx.setPatient(req.getPatient());
        rx.setDoctor(req.getDoctor());
        rx.setMedicationName("Video Consultation — " + LocalDate.now());
        rx.setDosage(prescriptionText);
        rx.setFrequency("As directed by doctor");
        rx.setDuration("As directed");
        rx.setPrescriptionDate(LocalDate.now());
        rx.setStatus("ACTIVE");
        prescriptionNumberService.assign(rx);
        prescriptionRepository.save(rx);
    }
}
