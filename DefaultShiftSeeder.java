package za.co.nmas.nmas.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.DoctorAvailability;
import za.co.nmas.nmas.repository.DoctorRepository;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Video requests are only accepted while a doctor is on a published shift.
 * So that demo/seeded doctors are usable straight away, any doctor with no
 * shift from today onward gets a default all-day shift (00:00-23:59) for today and the
 * next two days. Doctors then manage their own schedule under "My Availability".
 */
@Service
@Order(140)
public class DefaultShiftSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultShiftSeeder.class);

    private final DoctorRepository doctorRepository;
    private final DoctorAvailabilityService availabilityService;

    public DefaultShiftSeeder(DoctorRepository doctorRepository, DoctorAvailabilityService availabilityService) {
        this.doctorRepository = doctorRepository;
        this.availabilityService = availabilityService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            LocalDate today = LocalDate.now();
            int made = 0;
            for (Doctor d : doctorRepository.findAll()) {
                boolean hasFuture = false;
                for (DoctorAvailability a : availabilityService.findByDoctor(d.getId())) {
                    if (a.isActive() && a.getAvailableDate() != null && !a.getAvailableDate().isBefore(today)
                            && !availabilityService.getSlotsByAvailabilityId(a.getId()).isEmpty()) { hasFuture = true; break; }
                }
                if (hasFuture) continue;
                for (int i = 0; i < 3; i++) {
                    availabilityService.createAvailability(d, today.plusDays(i), LocalTime.of(0, 0), LocalTime.of(23, 59));
                    made++;
                }
            }
            if (made > 0) log.info("[NMAS] Default shifts created: {}", made);
        } catch (RuntimeException e) {
            log.warn("Default shift seeding skipped: {}", e.toString());
        }
    }
}
