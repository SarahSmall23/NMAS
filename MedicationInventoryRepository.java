package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import za.co.nmas.nmas.entity.MedicationInventory;

import java.util.List;

public interface MedicationInventoryRepository extends JpaRepository<MedicationInventory, Long> {

    List<MedicationInventory> findAllByOrderByMedicationNameAsc();

    @Query("SELECT m FROM MedicationInventory m WHERE m.quantityInStock <= m.reorderLevel ORDER BY m.quantityInStock ASC")
    List<MedicationInventory> findLowStock();

    @Query("SELECT COUNT(m) FROM MedicationInventory m WHERE m.quantityInStock <= m.reorderLevel")
    long countLowStock();

    @Query("SELECT COUNT(m) FROM MedicationInventory m WHERE m.quantityInStock = 0")
    long countOutOfStock();

    List<MedicationInventory> findByMedicationNameContainingIgnoreCase(String name);

    /** Any one row for this medication (used only when the prescribing facility is unknown). */
    java.util.Optional<MedicationInventory> findFirstByMedicationId(Long medicationId);

    /** The row a given facility holds for a given medication. */
    java.util.Optional<MedicationInventory> findFirstByMedicationIdAndFacilityId(Long medicationId, Long facilityId);

    List<MedicationInventory> findByFacilityIdOrderByMedicationNameAsc(Long facilityId);

    List<MedicationInventory> findByFacilityIdAndMedicationNameContainingIgnoreCase(Long facilityId, String name);

    /** Legacy rows from before stock was split per facility. */
    List<MedicationInventory> findByFacilityIsNull();
}
