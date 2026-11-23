package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.DoctorCheckIn;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DoctorCheckInRepository extends JpaRepository<DoctorCheckIn, Long> {

    /** The doctor's currently open session (checked in, not yet checked out). */
    Optional<DoctorCheckIn> findFirstByDoctorIdAndCheckOutTimeIsNullOrderByCheckInTimeDesc(Long doctorId);

    List<DoctorCheckIn> findByCheckOutTimeIsNull();

    List<DoctorCheckIn> findByCheckInTimeAfterOrderByCheckInTimeDesc(LocalDateTime since);
}
