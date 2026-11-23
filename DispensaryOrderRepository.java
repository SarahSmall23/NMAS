package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import za.co.nmas.nmas.entity.DispensaryOrder;

import java.util.List;

public interface DispensaryOrderRepository extends JpaRepository<DispensaryOrder, Long> {

    List<DispensaryOrder> findByStatusOrderByCreatedAtAsc(String status);

    @Query("SELECT d FROM DispensaryOrder d WHERE d.prescription.patient.id = :patientId "
         + "AND d.medication.id = :medicationId AND d.id <> :excludeId ORDER BY d.createdAt DESC")
    List<DispensaryOrder> findSameMedicationForPatient(@Param("patientId") Long patientId,
                                                       @Param("medicationId") Long medicationId,
                                                       @Param("excludeId") Long excludeId);

    List<DispensaryOrder> findByStatusInOrderByCreatedAtDesc(List<String> statuses);

    long countByStatus(String status);

    List<DispensaryOrder> findByPrescriptionIdOrderByCreatedAtDesc(Long prescriptionId);

    /** Dispensed orders for patients registered at the given facility, but collected somewhere else — for that facility's admin to see (avoids duplicate chronic-medication dispensing). */
    @Query("SELECT d FROM DispensaryOrder d WHERE d.status = 'DISPENSED' "
         + "AND d.prescription.patient.registeredFacility.id = :homeFacilityId "
         + "AND d.dispensingFacility IS NOT NULL AND d.dispensingFacility.id <> :homeFacilityId "
         + "ORDER BY d.dispensedAt DESC")
    List<DispensaryOrder> findCrossFacilityDispensingForHomeFacility(@Param("homeFacilityId") Long homeFacilityId);

    /** National view: every dispensed order collected at a facility other than the patient's registered one. */
    @Query("SELECT d FROM DispensaryOrder d WHERE d.status = 'DISPENSED' "
         + "AND d.prescription.patient.registeredFacility IS NOT NULL "
         + "AND d.dispensingFacility IS NOT NULL "
         + "AND d.dispensingFacility.id <> d.prescription.patient.registeredFacility.id "
         + "ORDER BY d.dispensedAt DESC")
    List<DispensaryOrder> findAllCrossFacilityDispensing();
}
