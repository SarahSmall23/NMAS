package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.RecordAccessRequest;

import java.util.List;

public interface RecordAccessRequestRepository extends JpaRepository<RecordAccessRequest, Long> {

    /** Cross-facility views of this facility's own patients, most recent first — for the facility admin's audit trail. */
    List<RecordAccessRequest> findByHomeFacilityIdOrderByRequestedAtDesc(Long facilityId);

    /** Every doctor who has viewed this patient's history from outside their home facility, most recent first. */
    List<RecordAccessRequest> findByPatientIdOrderByRequestedAtDesc(Long patientId);
}
