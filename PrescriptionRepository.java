package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.Prescription;
import java.util.List;

public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    List<Prescription> findByPatientId(Long patientId);

    List<Prescription> findByDoctorId(Long doctorId);

    long countByStatus(String status);

    List<Prescription> findByDoctorFacilityIdOrderByIdDesc(Long facilityId);

    long countByDoctorFacilityId(Long facilityId);

    long countByDoctorFacilityIdAndStatus(Long facilityId, String status);

    long countByDoctorId(Long doctorId);

    boolean existsByPrescriptionNumber(String prescriptionNumber);
    java.util.Optional<Prescription> findByPrescriptionNumber(String prescriptionNumber);
    java.util.List<Prescription> findByPrescriptionNumberIsNull();
}
