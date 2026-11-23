package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.WalkInVisit;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface WalkInVisitRepository extends JpaRepository<WalkInVisit, Long> {

    /** Oldest arrival first, so the person who came in first is at the top. */
    List<WalkInVisit> findByFacilityIdAndStatusInOrderByArrivalTimeAsc(Long facilityId, Collection<String> statuses);

    List<WalkInVisit> findByAssignedDoctorIdAndStatusInOrderBySentToDoctorAtAsc(Long doctorId, Collection<String> statuses);

    List<WalkInVisit> findByFacilityIdAndArrivalTimeAfterOrderByArrivalTimeDesc(Long facilityId, LocalDateTime after);

    List<WalkInVisit> findByPatientIdOrderByArrivalTimeDesc(Long patientId);

    boolean existsByPatientIdAndFacilityIdAndStatusIn(Long patientId, Long facilityId, Collection<String> statuses);

    boolean existsByAppointmentIdAndStatusNot(Long appointmentId, String status);

    java.util.Optional<WalkInVisit> findFirstByAppointmentId(Long appointmentId);
}
