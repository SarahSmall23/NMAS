package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.DispensaryOrder;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.entity.MedicationInventory;
import za.co.nmas.nmas.entity.Patient;
import za.co.nmas.nmas.entity.Prescription;
import za.co.nmas.nmas.entity.PrescriptionMedication;
import za.co.nmas.nmas.entity.StaffMember;
import za.co.nmas.nmas.repository.DispensaryOrderRepository;
import za.co.nmas.nmas.repository.MedicationInventoryRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class DispensaryService {

    private final DispensaryOrderRepository dispensaryOrderRepository;
    private final MedicationInventoryRepository inventoryRepository;
    private final AuditLogService auditLogService;
    private final StaffMemberRepository staffMemberRepository;
    private final NotificationService notificationService;

    private static final DateTimeFormatter WHEN_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy 'at' HH:mm");

    public DispensaryService(DispensaryOrderRepository dispensaryOrderRepository,
                             MedicationInventoryRepository inventoryRepository,
                             AuditLogService auditLogService,
                             StaffMemberRepository staffMemberRepository,
                             NotificationService notificationService) {
        this.dispensaryOrderRepository = dispensaryOrderRepository;
        this.inventoryRepository = inventoryRepository;
        this.auditLogService = auditLogService;
        this.staffMemberRepository = staffMemberRepository;
        this.notificationService = notificationService;
    }

    public DispensaryOrder createForPrescriptionMedication(Prescription prescription,
                                                           PrescriptionMedication pm) {
        DispensaryOrder order = new DispensaryOrder();
        order.setPrescription(prescription);
        order.setPrescriptionMedication(pm);
        order.setMedication(pm.getMedication());
        order.setMedicationName(pm.getMedication().getMedicationName()
                + (pm.getMedication().getStrength() != null ? " " + pm.getMedication().getStrength() : ""));
        order.setPatientName(prescription.getPatient().getFullName());
        order.setDosage(pm.getDosage());
        order.setFrequency(pm.getFrequency());
        order.setDuration(pm.getDuration());

        // Stock is per facility: use the shelf of the facility that issued the prescription.
        Facility prescribingFacility = prescription.getDoctor() != null ? prescription.getDoctor().getFacility() : null;
        MedicationInventory inv = prescribingFacility != null
                ? inventoryRepository.findFirstByMedicationIdAndFacilityId(pm.getMedication().getId(), prescribingFacility.getId()).orElse(null)
                : inventoryRepository.findFirstByMedicationId(pm.getMedication().getId()).orElse(null);

        // Whether this medicine is counted out individually (tablets/capsules kept in
        // packs) or handed over as a whole container exactly as it's stored (a vial of
        // insulin, a tube of cream, a bottle of syrup, an inhaler...). Prefer the actual
        // inventory row's packaging — it reflects how this stock is really kept — and
        // fall back to the formulary's pack size if the item isn't stocked yet.
        boolean packTracked = inv != null
                ? inv.isPackTracked()
                : (pm.getMedication().getPillsPerPack() != null && pm.getMedication().getPillsPerPack() > 0);
        Integer effectivePillsPerPack = inv != null ? inv.getEffectivePillsPerPack() : pm.getMedication().getPillsPerPack();

        Integer suggestedQty = packTracked ? calculateQuantity(pm.getFrequency(), pm.getDuration()) : null;
        order.setSuggestedQuantity(suggestedQty);
        // Non-pack items (vials, tubes, bottles, inhalers, patches...) are dispensed as
        // one whole container by default — that's how they're stored — and the
        // pharmacist can raise the count if the course genuinely needs more than one.
        order.setSuggestedPacks(packTracked ? calculatePacks(suggestedQty, effectivePillsPerPack) : 1);

        order.setInventoryItem(inv);
        order.setStatus(inv != null && inv.isOutOfStock() ? "OUT_OF_STOCK" : "PENDING");

        DispensaryOrder saved = dispensaryOrderRepository.save(order);
        auditLogService.log("DISPENSARY_ORDER_CREATED", "DispensaryOrder", saved.getId(),
                "Rx #" + prescription.getId() + " — " + order.getMedicationName()
                        + " for " + order.getPatientName());
        return saved;
    }

    /**
     * Dispenses whole containers of medication — exactly as it's kept in the
     * dispensary. That might be a box of 21 Amoxicillin capsules, a vial of
     * insulin, a tube of cream, a bottle of syrup, or an inhaler: whatever the
     * inventory's "unit" says this item is stored and counted as. Inventory
     * quantities are tracked in that same unit, so this just decrements the
     * container count directly, and only converts to a pill count on top of
     * that for items that are actually pack-tracked (tablets/capsules).
     */
    public DispensaryOrder dispense(Long orderId, int packs, String notes, String pharmacistEmail) {
        DispensaryOrder order = dispensaryOrderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Dispensary order not found"));

        assertOwnFacility(order, pharmacistEmail);

        if (order.getPrescription() != null && order.getPrescription().isExpired()) {
            throw new RuntimeException("This prescription expired on "
                    + order.getPrescription().getEffectiveValidUntil() + " and can no longer be dispensed. "
                    + "The patient needs a new prescription from their doctor.");
        }

        if (order.getInventoryItem() != null && order.getInventoryItem().isExpired()) {
            throw new RuntimeException(order.getMedicationName() + " cannot be dispensed: the stock expired on "
                    + order.getInventoryItem().getExpiryDate() + ". Remove it from inventory and restock.");
        }

        String unitPlural = (order.getInventoryItem() != null && order.getInventoryItem().getUnit() != null)
                ? order.getInventoryItem().getUnit() : "unit(s)";
        String unitSingular = singularUnit(unitPlural);

        if (packs < 1) {
            throw new RuntimeException("Must dispense at least 1 " + unitSingular + ".");
        }

        if (order.getInventoryItem() != null) {
            MedicationInventory inv = order.getInventoryItem();
            if (inv.getQuantityInStock() < packs) {
                throw new RuntimeException("Insufficient stock: only "
                        + inv.getQuantityInStock() + " " + unitPlural.toLowerCase() + " available");
            }
            inv.setQuantityInStock(inv.getQuantityInStock() - packs);
            inventoryRepository.save(inv);
        }

        boolean packTracked = order.getInventoryItem() != null
                ? order.getInventoryItem().isPackTracked()
                : (order.getMedication() != null && order.getMedication().getPillsPerPack() != null);
        Integer effectivePillsPerPack = order.getInventoryItem() != null
                ? order.getInventoryItem().getEffectivePillsPerPack()
                : (order.getMedication() != null ? order.getMedication().getPillsPerPack() : null);
        Integer totalPills = (packTracked && effectivePillsPerPack != null) ? packs * effectivePillsPerPack : null;

        order.setStatus("DISPENSED");
        order.setDispensedPacks(packs);
        order.setDispensedQuantity(totalPills);
        order.setPharmacistNotes(notes != null && !notes.isBlank() ? notes : null);
        order.setDispensedBy(pharmacistEmail);
        order.setDispensedAt(LocalDateTime.now());

        // Record which facility actually handed this over — needed to spot a patient
        // collecting chronic medication (e.g. ARVs) at more than one facility.
        Facility dispensingFacility = staffMemberRepository.findByEmail(pharmacistEmail)
                .map(StaffMember::getFacility).orElse(null);
        order.setDispensingFacility(dispensingFacility);

        dispensaryOrderRepository.save(order);

        auditLogService.log("MEDICATION_DISPENSED_RX", "DispensaryOrder", orderId,
                order.getMedicationName() + " — " + packs + " " + unitPlural.toLowerCase()
                        + (totalPills != null ? " (" + totalPills + " pills)" : "")
                        + " dispensed to " + order.getPatientName()
                        + " by " + pharmacistEmail);

        notifyHomeFacilityIfCrossFacilityDispensing(order, dispensingFacility);

        return order;
    }

    /**
     * If this medication was collected at a facility other than the one the
     * patient is registered at, tell that home facility's admin — the most
     * useful case is chronic medication (ARVs, insulin, etc.) that must not
     * be re-dispensed for the same period at the patient's usual clinic.
     */
    private void notifyHomeFacilityIfCrossFacilityDispensing(DispensaryOrder order, Facility dispensingFacility) {
        if (dispensingFacility == null) return;
        Prescription prescription = order.getPrescription();
        if (prescription == null || prescription.getPatient() == null) return;
        Patient patient = prescription.getPatient();
        Facility homeFacility = patient.getRegisteredFacility();
        if (homeFacility == null || homeFacility.getId().equals(dispensingFacility.getId())) return;

        List<StaffMember> admins = staffMemberRepository.findByFacilityIdAndRole(homeFacility.getId(), "FACILITY_ADMIN");
        String when = WHEN_FORMAT.format(order.getDispensedAt());
        for (StaffMember admin : admins) {
            if (admin.getEmail() == null) continue;
            notificationService.notify(admin.getEmail(), "CROSS_FACILITY_DISPENSING",
                    patient.getFullName() + " collected " + order.getMedicationName() + " at "
                            + dispensingFacility.getFacilityName() + " on " + when
                            + " — check before dispensing the same medication again.",
                    "/facility-admin-dashboard");
        }
    }

    /** "Vials" -> "Vial", "Packs" -> "Pack", "Patches" -> "Patch", "mL" left alone. Best-effort, used only for wording messages. */
    private String singularUnit(String unit) {
        if (unit == null || unit.isBlank()) return "unit";
        String u = unit.trim();
        if (u.length() > 2 && u.toLowerCase().endsWith("es") && !u.toLowerCase().endsWith("ses")) {
            return u.substring(0, u.length() - 2);
        }
        if (u.length() > 1 && u.endsWith("s") && !u.equalsIgnoreCase("mL") && !u.equalsIgnoreCase("mg")) {
            return u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** Rounds up: a patient needing 25 pills with 10-per-pack needs 3 whole packs, never a fraction of one. */
    private Integer calculatePacks(Integer totalPills, Integer pillsPerPack) {
        if (totalPills == null) return null;
        if (pillsPerPack == null || pillsPerPack <= 0) return totalPills; // fall back to pill count if pack size unknown
        return (int) Math.ceil(totalPills / (double) pillsPerPack);
    }

    public void reject(Long orderId, String notes, String pharmacistEmail) {
        DispensaryOrder order = dispensaryOrderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Dispensary order not found"));
        assertOwnFacility(order, pharmacistEmail);
        order.setStatus("REJECTED");
        order.setPharmacistNotes(notes != null && !notes.isBlank() ? notes : null);
        order.setDispensedBy(pharmacistEmail);
        order.setDispensedAt(LocalDateTime.now());
        dispensaryOrderRepository.save(order);

        auditLogService.log("DISPENSARY_REJECTED", "DispensaryOrder", orderId,
                order.getMedicationName() + " rejected for " + order.getPatientName()
                        + " by " + pharmacistEmail);
    }

    /** The facility an order belongs to: whose shelf it draws from, else who prescribed it. */
    static Long orderFacilityId(DispensaryOrder o) {
        if (o.getInventoryItem() != null && o.getInventoryItem().getFacility() != null)
            return o.getInventoryItem().getFacility().getId();
        if (o.getPrescription() != null && o.getPrescription().getDoctor() != null
                && o.getPrescription().getDoctor().getFacility() != null)
            return o.getPrescription().getDoctor().getFacility().getId();
        return null;
    }

    private void assertOwnFacility(DispensaryOrder order, String pharmacistEmail) {
        Facility mine = staffMemberRepository.findByEmail(pharmacistEmail).map(StaffMember::getFacility).orElse(null);
        Long orderFac = orderFacilityId(order);
        if (mine != null && orderFac != null && !mine.getId().equals(orderFac)) {
            throw new RuntimeException("This order belongs to another facility's pharmacy — it can only be handled there.");
        }
    }

    /**
     * For the pharmacist: has this patient been prescribed (or given) the same medication at ANOTHER
     * facility in the last 90 days? Returns human-readable lines, empty when nothing was found.
     */
    public List<String> elsewhereNotes(DispensaryOrder o) {
        List<String> notes = new java.util.ArrayList<>();
        try {
            if (o.getPrescription() == null || o.getPrescription().getPatient() == null || o.getMedication() == null) return notes;
            Long mine = orderFacilityId(o);
            LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
            for (DispensaryOrder x : dispensaryOrderRepository.findSameMedicationForPatient(
                    o.getPrescription().getPatient().getId(), o.getMedication().getId(), o.getId())) {
                Long theirs = orderFacilityId(x);
                if (theirs == null || theirs.equals(mine)) continue;
                if (x.getCreatedAt() == null || x.getCreatedAt().isBefore(cutoff)) continue;
                String where = x.getInventoryItem() != null && x.getInventoryItem().getFacility() != null
                        ? x.getInventoryItem().getFacility().getFacilityName()
                        : (x.getPrescription() != null && x.getPrescription().getIssuingFacility() != null
                            ? x.getPrescription().getIssuingFacility().getFacilityName() : "another facility");
                String state = "DISPENSED".equals(x.getStatus())
                        ? "DISPENSED on " + (x.getDispensedAt() != null ? x.getDispensedAt().toLocalDate() : "?")
                        : x.getStatus();
                notes.add("Also prescribed at " + where + " on " + x.getCreatedAt().toLocalDate() + " (" + state + ")");
            }
        } catch (Exception ignored) { }
        return notes;
    }

    public List<DispensaryOrder> findPending(Long facilityId) {
        return findPending().stream().filter(o -> facilityId == null || facilityId.equals(orderFacilityId(o))).toList();
    }

    public List<DispensaryOrder> findRecent(Long facilityId) {
        return findRecent().stream().filter(o -> facilityId == null || facilityId.equals(orderFacilityId(o))).toList();
    }

    public long countPending(Long facilityId) {
        return findPending(facilityId).size();
    }

    public List<DispensaryOrder> findPending() {
        return dispensaryOrderRepository.findByStatusOrderByCreatedAtAsc("PENDING");
    }

    public List<DispensaryOrder> findRecent() {
        return dispensaryOrderRepository.findByStatusInOrderByCreatedAtDesc(
                List.of("DISPENSED", "REJECTED", "OUT_OF_STOCK"));
    }

    public long countPending() {
        return dispensaryOrderRepository.countByStatus("PENDING");
    }

    /** For the system admin: every cross-facility collection nationally. */
    public List<DispensaryOrder> findAllCrossFacilityDispensing() {
        return dispensaryOrderRepository.findAllCrossFacilityDispensing();
    }

    /** For a facility admin: their own patients' medication collected at a different facility. */
    public List<DispensaryOrder> findCrossFacilityDispensingForHomeFacility(Long facilityId) {
        return dispensaryOrderRepository.findCrossFacilityDispensingForHomeFacility(facilityId);
    }

    private Integer calculateQuantity(String frequency, String duration) {
        if (frequency == null || duration == null) return null;

        Integer days;
        switch (duration) {
            case "1 day"          -> days = 1;
            case "3 days"         -> days = 3;
            case "5 days"         -> days = 5;
            case "7 days"         -> days = 7;
            case "10 days"        -> days = 10;
            case "14 days"        -> days = 14;
            case "1 month"        -> days = 30;
            case "3 months"       -> days = 90;
            case "6 months"       -> days = 180;
            default               -> days = null;
        }
        if (days == null) return null;

        if ("Weekly".equals(frequency)) return Math.max(1, days / 7);

        Integer perDay;
        switch (frequency) {
            case "Once daily", "Stat (once)" -> perDay = 1;
            case "Twice daily", "Every 12 hours" -> perDay = 2;
            case "Three times daily", "Every 8 hours" -> perDay = 3;
            case "Four times daily", "Every 6 hours" -> perDay = 4;
            case "Every 4 hours" -> perDay = 6;
            default -> perDay = null;
        }
        if (perDay == null) return null;
        return perDay * days;
    }
}
