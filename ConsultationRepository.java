package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.Consultation;

import java.time.LocalDateTime;
import java.util.List;

public interface ConsultationRepository extends JpaRepository<Consultation, Long> {

    List<Consultation> findByPatientId(Long patientId);

    List<Consultation> findByAppointmentDoctorId(Long doctorId);

    long countByAppointmentDoctorId(Long doctorId);

    long countByConsultationDateBetween(LocalDateTime start, LocalDateTime end);

    long countByAppointmentDoctorIdAndConsultationDateBetween(Long doctorId, LocalDateTime start, LocalDateTime end);

    long countByDiagnosisIsNotNull();

    long countByAppointmentDoctorIdAndDiagnosisIsNotNull(Long doctorId);
}
