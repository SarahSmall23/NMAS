package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.co.nmas.nmas.entity.DispensaryOrder;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.entity.StaffMember;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.service.DispensaryService;
import za.co.nmas.nmas.service.PillCollectionService;

@Controller
@RequestMapping("/dispensary")
public class DispensaryController {

    private final DispensaryService dispensaryService;

    private final PillCollectionService pillCollectionService;
    private final StaffMemberRepository staffMemberRepository;

    public DispensaryController(DispensaryService dispensaryService,
                                PillCollectionService pillCollectionService,
                                StaffMemberRepository staffMemberRepository) {
        this.dispensaryService = dispensaryService;
        this.pillCollectionService = pillCollectionService;
        this.staffMemberRepository = staffMemberRepository;
    }

    private Facility facilityOf(Authentication auth) {
        return staffMemberRepository.findByEmail(auth.getName()).map(StaffMember::getFacility).orElse(null);
    }

    /** Pharmacist confirms the patient (already cleared by reception) collected their repeat pills. */
    @PostMapping("/collections/{id}/collected")
    public String collected(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        try {
            Facility f = facilityOf(auth);
            if (f == null) throw new IllegalArgumentException("Your account isn't linked to a facility.");
            var c = pillCollectionService.markCollected(id, f, auth.getName());
            ra.addFlashAttribute("success", c.getPatient().getFullName() + " collected their pills.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dispensary";
    }

    @GetMapping
    public String queue(Authentication auth, Model model) {
        Facility f = facilityOf(auth);
        if (f != null) {
            model.addAttribute("collectionsToday", pillCollectionService.dueToday(f.getId()));
            model.addAttribute("collectedRecently", pillCollectionService.recentlyCollected(f.getId()));
        }
        // Each facility's pharmacy only sees its own orders (system admin: all)
        Long fid = f != null ? f.getId() : null;
        java.util.List<DispensaryOrder> pending = dispensaryService.findPending(fid);
        model.addAttribute("pendingOrders", pending);
        java.util.Map<Long, java.util.List<String>> elsewhere = new java.util.HashMap<>();
        for (DispensaryOrder o : pending) elsewhere.put(o.getId(), dispensaryService.elsewhereNotes(o));
        model.addAttribute("elsewhere", elsewhere);
        model.addAttribute("recentOrders", dispensaryService.findRecent(fid));
        model.addAttribute("pendingCount", dispensaryService.countPending(fid));
        return "dispensary";
    }

    @PostMapping("/{id}/dispense")
    public String dispense(@PathVariable Long id,
                           @RequestParam int packs,
                           @RequestParam(required = false) String notes,
                           Authentication auth,
                           RedirectAttributes ra) {
        try {
            DispensaryOrder order = dispensaryService.dispense(id, packs, notes, auth.getName());
            String unit = (order.getInventoryItem() != null && order.getInventoryItem().getUnit() != null)
                    ? order.getInventoryItem().getUnit().toLowerCase() : "unit(s)";
            ra.addFlashAttribute("success", "Medication dispensed (" + packs + " " + unit + ") and inventory updated.");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dispensary";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id,
                         @RequestParam(required = false) String notes,
                         Authentication auth,
                         RedirectAttributes ra) {
        dispensaryService.reject(id, notes, auth.getName());
        ra.addFlashAttribute("success", "Order rejected.");
        return "redirect:/dispensary";
    }
}
