package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.VideoConsultationRequest;

import java.util.List;
import java.util.Optional;

public interface VideoConsultationRequestRepository extends JpaRepository<VideoConsultationRequest, Long> {
    List<VideoConsultationRequest> findByPatientIdOrderByRequestedAtDesc(Long patientId);
    List<VideoConsultationRequest> findByDoctorIdOrderByRequestedAtDesc(Long doctorId);
    List<VideoConsultationRequest> findByDoctorIdAndStatus(Long doctorId, String status);
    List<VideoConsultationRequest> findByDoctorIdAndStatusOrderByRequestedAtDesc(Long doctorId, String status);
    Optional<VideoConsultationRequest> findByRoomId(String roomId);
}
