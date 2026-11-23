package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.PillCollection;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface PillCollectionRepository extends JpaRepository<PillCollection, Long> {

    boolean existsByPrescriptionIdAndWindowStart(Long prescriptionId, LocalDate windowStart);

    /** Collections for a facility whose window overlaps the given day, soonest-ending first. */
    List<PillCollection> findByFacilityIdAndWindowStartLessThanEqualAndWindowEndGreaterThanEqualOrderByWindowEndAscIdAsc(
            Long facilityId, LocalDate startOnOrBefore, LocalDate endOnOrAfter);

    List<PillCollection> findByStatusInAndWindowEndLessThan(Collection<String> statuses, LocalDate before);

    List<PillCollection> findByFacilityIdAndCollectedAtAfterOrderByCollectedAtDesc(Long facilityId, java.time.LocalDateTime after);
}
