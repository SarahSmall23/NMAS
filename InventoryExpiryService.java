package za.co.nmas.nmas.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.MedicationInventory;
import za.co.nmas.nmas.entity.StaffMember;
import za.co.nmas.nmas.repository.MedicationInventoryRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Warns pharmacists six months before stock expires. Checked once a day (and at
 * start-up); each batch is announced once — restocking with a new expiry date
 * re-arms the alert for the new batch.
 */
@Service
@org.springframework.core.annotation.Order(100)
public class InventoryExpiryService implements ApplicationRunner {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final MedicationInventoryRepository inventoryRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final NotificationService notificationService;

    public InventoryExpiryService(MedicationInventoryRepository inventoryRepository,
                                  StaffMemberRepository staffMemberRepository,
                                  NotificationService notificationService) {
        this.inventoryRepository = inventoryRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.notificationService = notificationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        checkExpiries();
    }

    @Scheduled(cron = "0 10 6 * * *")
    public void checkExpiries() {
        try {
            for (MedicationInventory inv : inventoryRepository.findAll()) {
                if (inv.getExpiryDate() == null) continue;
                if (inv.getExpiryDate().equals(inv.getExpiryAlertSentFor())) continue; // already announced this batch
                if (!inv.isExpiringSoon() && !inv.isExpired()) continue;

                String msg = inv.isExpired()
                        ? inv.getMedicationName() + " has EXPIRED (" + inv.getExpiryDate().format(D) + ") — remove it from the shelf."
                        : inv.getMedicationName() + " expires on " + inv.getExpiryDate().format(D)
                          + " (" + inv.getDaysToExpiry() + " days) — within the 6-month warning window.";
                if (inv.getFacility() == null) continue;
                for (StaffMember ph : staffMemberRepository.findByFacilityIdAndRole(inv.getFacility().getId(), "PHARMACIST")) {
                    notificationService.notify(ph.getEmail(), "MEDICATION_EXPIRY", msg, "/inventory/report?status=expiring");
                }
                inv.setExpiryAlertSentFor(inv.getExpiryDate());
                inventoryRepository.save(inv);
            }
        } catch (Exception e) {
            System.err.println("[NMAS] Expiry check skipped: " + e.getMessage());
        }
    }
}
