package za.co.nmas.nmas.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.entity.MedicationInventory;
import za.co.nmas.nmas.entity.StaffMember;
import za.co.nmas.nmas.repository.MedicationInventoryRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.service.AuditLogService;
import za.co.nmas.nmas.service.FacilityService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Every facility keeps its own pharmacy stock. A pharmacist (or facility admin) only ever
 * sees and changes their own facility's shelves; the system admin picks which facility to view.
 */
@Controller
@RequestMapping("/inventory")
public class InventoryController {

    private final MedicationInventoryRepository inventoryRepository;
    private final AuditLogService auditLogService;
    private final StaffMemberRepository staffMemberRepository;
    private final FacilityService facilityService;

    public InventoryController(MedicationInventoryRepository inventoryRepository,
                               AuditLogService auditLogService,
                               StaffMemberRepository staffMemberRepository,
                               FacilityService facilityService) {
        this.inventoryRepository = inventoryRepository;
        this.auditLogService = auditLogService;
        this.staffMemberRepository = staffMemberRepository;
        this.facilityService = facilityService;
    }

    private boolean isSystemAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
    }

    /** Own facility for staff; for the system admin the chosen facility, defaulting to the first one. */
    private Facility scope(Authentication auth, Long facilityId) {
        if (isSystemAdmin(auth)) {
            Facility chosen = facilityService.findById(facilityId);
            if (chosen != null) return chosen;
            List<Facility> all = facilityService.findAll();
            return all.isEmpty() ? null : all.get(0);
        }
        return staffMemberRepository.findByEmail(auth.getName()).map(StaffMember::getFacility).orElse(null);
    }

    /** A row may only be touched by someone from the facility that owns it (system admin: any). */
    private boolean allowed(MedicationInventory med, Authentication auth) {
        if (isSystemAdmin(auth)) return true;
        Facility mine = scope(auth, null);
        return mine != null && med.getFacility() != null && med.getFacility().getId().equals(mine.getId());
    }

    private String back(MedicationInventory med, Authentication auth) {
        return isSystemAdmin(auth) && med.getFacility() != null
                ? "redirect:/inventory?facilityId=" + med.getFacility().getId() : "redirect:/inventory";
    }

    @GetMapping
    public String index(@RequestParam(required = false) Long facilityId, Authentication auth, Model model) {
        Facility facility = scope(auth, facilityId);
        model.addAttribute("facility", facility);
        model.addAttribute("systemAdmin", isSystemAdmin(auth));
        model.addAttribute("facilities", isSystemAdmin(auth) ? facilityService.findAll() : List.of());
        List<MedicationInventory> all = facility == null ? List.of()
                : inventoryRepository.findByFacilityIdOrderByMedicationNameAsc(facility.getId());
        model.addAttribute("medications", all);
        model.addAttribute("lowStockList", all.stream().filter(MedicationInventory::isLowStock).toList());
        model.addAttribute("totalMedications", all.size());
        model.addAttribute("lowStockCount", all.stream().filter(m -> m.getQuantityInStock() <= m.getReorderLevel()).count());
        model.addAttribute("outOfStockCount", all.stream().filter(MedicationInventory::isOutOfStock).count());
        model.addAttribute("expiringCount", all.stream().filter(MedicationInventory::isExpiringSoon).count());
        model.addAttribute("expiredCount", all.stream().filter(MedicationInventory::isExpired).count());
        return "inventory";
    }

    @PostMapping("/add")
    public String add(@RequestParam String medicationName,
                      @RequestParam(required = false) String genericName,
                      @RequestParam(required = false) String category,
                      @RequestParam String unit,
                      @RequestParam int quantityInStock,
                      @RequestParam int reorderLevel,
                      @RequestParam(required = false) String supplier,
                      @RequestParam(required = false) Integer pillsPerPack,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
                      @RequestParam(required = false) String batchNumber,
                      @RequestParam(required = false) Long facilityId,
                      Authentication auth,
                      RedirectAttributes ra) {

        Facility facility = scope(auth, facilityId);
        if (facility == null) {
            ra.addFlashAttribute("error", "Your account isn't linked to a facility, so stock can't be added.");
            return "redirect:/inventory";
        }
        MedicationInventory med = new MedicationInventory();
        med.setFacility(facility);
        med.setMedicationName(medicationName);
        med.setGenericName(genericName);
        med.setCategory(category);
        med.setUnit(unit);
        med.setQuantityInStock(quantityInStock);
        med.setReorderLevel(reorderLevel);
        med.setSupplier(supplier);
        med.setPillsPerPack(pillsPerPack);
        med.setExpiryDate(expiryDate);
        med.setBatchNumber(batchNumber != null && !batchNumber.isBlank() ? batchNumber.trim() : null);
        inventoryRepository.save(med);

        String packNote = pillsPerPack != null ? " (" + pillsPerPack + " pills/pack)" : "";
        auditLogService.log("MEDICATION_ADDED", "MedicationInventory", med.getId(),
                medicationName + " added at " + facility.getFacilityName() + " — stock: " + quantityInStock + " " + unit + packNote);

        ra.addFlashAttribute("success", medicationName + " added to " + facility.getFacilityName() + "'s inventory.");
        return back(med, auth);
    }

    @PostMapping("/restock/{id}")
    public String restock(@PathVariable Long id,
                          @RequestParam int quantity,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
                          @RequestParam(required = false) String batchNumber,
                          Authentication auth,
                          RedirectAttributes ra) {

        MedicationInventory target = inventoryRepository.findById(id).orElse(null);
        if (target == null) return "redirect:/inventory";
        if (!allowed(target, auth)) {
            ra.addFlashAttribute("error", "That item belongs to another facility's pharmacy.");
            return "redirect:/inventory";
        }
        MedicationInventory med = target;
        int before = med.getQuantityInStock();
        med.setQuantityInStock(before + quantity);
        med.setLastRestockedAt(LocalDateTime.now());
        // A restock brings a new batch: record its expiry, and let the 6-month warning fire again for it.
        if (expiryDate != null) {
            med.setExpiryDate(expiryDate);
            med.setExpiryAlertSentFor(null);
        }
        if (batchNumber != null && !batchNumber.isBlank()) med.setBatchNumber(batchNumber.trim());
        inventoryRepository.save(med);

        String unitLabel = packLabel(med, quantity);
        auditLogService.log("MEDICATION_RESTOCKED", "MedicationInventory", med.getId(),
                med.getMedicationName() + " restocked +" + unitLabel
                + " (was " + before + ", now " + med.getQuantityInStock() + " " + med.getUnit() + ")");
        ra.addFlashAttribute("success", med.getMedicationName() + " restocked by " + unitLabel + ".");
        return back(med, auth);
    }

    @PostMapping("/dispense/{id}")
    public String dispense(@PathVariable Long id,
                           @RequestParam int quantity,
                           Authentication auth,
                           RedirectAttributes ra) {

        MedicationInventory med = inventoryRepository.findById(id).orElse(null);
        if (med == null) return "redirect:/inventory";
        if (!allowed(med, auth)) {
            ra.addFlashAttribute("error", "That item belongs to another facility's pharmacy.");
            return "redirect:/inventory";
        }
        if (med.getQuantityInStock() < quantity) {
            ra.addFlashAttribute("error",
                    "Insufficient stock for " + med.getMedicationName() +
                    ". Available: " + packLabel(med, med.getQuantityInStock()) + ".");
        } else {
            int before = med.getQuantityInStock();
            med.setQuantityInStock(before - quantity);
            inventoryRepository.save(med);

            String unitLabel = packLabel(med, quantity);
            auditLogService.log("MEDICATION_DISPENSED", "MedicationInventory", med.getId(),
                    med.getMedicationName() + " dispensed -" + unitLabel
                    + " (was " + before + ", now " + med.getQuantityInStock() + " " + med.getUnit() + ")");
            ra.addFlashAttribute("success", unitLabel + " of " + med.getMedicationName() + " dispensed.");
        }
        return back(med, auth);
    }

    /**
     * Formats a stock quantity for display/logging. For pack-tracked items
     * (a fixed pillsPerPack is known — either set directly here or inherited
     * from the linked formulary Medication), shows both the pack count and
     * the total pill count, e.g. "3 pack(s) (63 pills)". Non-pill items
     * (vials, inhalers, mL) just show the raw quantity and unit as before.
     */
    private String packLabel(MedicationInventory med, int packQuantity) {
        Integer pillsPerPack = med.getEffectivePillsPerPack();
        if (pillsPerPack != null && pillsPerPack > 0) {
            int totalPills = packQuantity * pillsPerPack;
            return packQuantity + " pack(s) (" + totalPills + " pills)";
        }
        return packQuantity + " " + med.getUnit();
    }

    @PostMapping("/delete/{id}")
    public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        MedicationInventory med = inventoryRepository.findById(id).orElse(null);
        if (med == null) return "redirect:/inventory";
        if (!allowed(med, auth)) {
            ra.addFlashAttribute("error", "That item belongs to another facility's pharmacy.");
            return "redirect:/inventory";
        }
        auditLogService.log("MEDICATION_DELETED", "MedicationInventory", med.getId(),
                med.getMedicationName() + " removed from inventory");
        String redirect = back(med, auth);
        inventoryRepository.delete(med);
        ra.addFlashAttribute("success", med.getMedicationName() + " removed from inventory.");
        return redirect;
    }
}
