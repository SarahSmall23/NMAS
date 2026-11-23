package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.AppointmentSlot;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AppointmentSlotRepository extends JpaRepository<AppointmentSlot, Long> {
    List<AppointmentSlot> findByDoctorIdAndSlotDateOrderByStartTimeAsc(Long doctorId, LocalDate slotDate);
    Optional<AppointmentSlot> findFirstByDoctorIdAndSlotDateAndStatusOrderByStartTimeAsc(Long doctorId, LocalDate slotDate, String status);
    List<AppointmentSlot> findByAvailabilityId(Long availabilityId);
}
