package za.co.nmas.nmas.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.entity.StaffMember;
import za.co.nmas.nmas.repository.MedicationInventoryRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.report.ReportDocument;
import za.co.nmas.nmas.service.FacilityService;
import za.co.nmas.nmas.service.ReportExportService;
import za.co.nmas.nmas.service.ReportService;

import java.time.LocalDate;

/**
 * Two kinds of report, each viewable on screen and downloadable as PDF or CSV:
 *   /inventory/report   pharmacist: stock levels / low / out / expiring / expired, or one medication
 *   /facility/reports   facility admin: how the facility is running (doctors, consultations,
 *                       prescriptions, visits...) for one facility; the system admin can pick which
 */
@Controller
public class ReportController {

    private final ReportService reportService;
    private final ReportExportService exportService;
    private final MedicationInventoryRepository inventoryRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final FacilityService facilityService;

    public ReportController(ReportService reportService, ReportExportService exportService,
                            MedicationInventoryRepository inventoryRepository,
                            StaffMemberRepository staffMemberRepository, FacilityService facilityService) {
        this.reportService = reportService;
        this.exportService = exportService;
        this.inventoryRepository = inventoryRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.facilityService = facilityService;
    }

    private boolean isSystemAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
    }

    // ------------------------------------------------------------ pharmacist

    /** Pharmacist / facility admin: own facility. System admin: chosen facility, or all. */
    private Facility stockScope(Authentication auth, Long facilityId) {
        if (isSystemAdmin(auth)) return facilityService.findById(facilityId);
        return staffMemberRepository.findByEmail(auth.getName()).map(StaffMember::getFacility).orElse(null);
    }

    @GetMapping("/inventory/report")
    public String inventoryReport(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) Long item,
                                  @RequestParam(required = false) Long facilityId,
                                  Authentication auth, Model model) {
        String st = status == null || status.isBlank() ? "all" : status;
        boolean sys = isSystemAdmin(auth);
        Facility facility = stockScope(auth, facilityId);
        if (!sys && facility == null) {
            model.addAttribute("kind", "inventory");
            model.addAttribute("noFacility", true);
            return "report";
        }
        model.addAttribute("kind", "inventory");
        model.addAttribute("systemAdmin", sys);
        model.addAttribute("facilities", sys ? facilityService.findAll() : java.util.List.of());
        model.addAttribute("facilityId", facility != null ? facility.getId() : null);
        model.addAttribute("doc", reportService.inventoryReport(st, item, facility));
        model.addAttribute("status", st);
        model.addAttribute("item", item);
        model.addAttribute("statusOptions", ReportService.STOCK_FILTERS);
        model.addAttribute("items", facility != null
                ? inventoryRepository.findByFacilityIdOrderByMedicationNameAsc(facility.getId())
                : inventoryRepository.findAllByOrderByMedicationNameAsc());
        model.addAttribute("downloadBase", "/inventory/report/download");
        model.addAttribute("query", "status=" + st + (item != null ? "&item=" + item : "")
                + (facility != null ? "&facilityId=" + facility.getId() : ""));
        return "report";
    }

    @GetMapping("/inventory/report/download")
    public ResponseEntity<byte[]> inventoryDownload(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Long item,
                                                    @RequestParam(required = false) Long facilityId,
                                                    @RequestParam(defaultValue = "pdf") String format,
                                                    @RequestParam(required = false) Integer section,
                                                    Authentication auth) {
        Facility facility = stockScope(auth, facilityId);
        ReportDocument doc = reportService.inventoryReport(status, item, facility).onlySection(section);
        String base = "NMAS-stock-report-" + (facility != null ? facility.getFacilityName().replaceAll("[^A-Za-z0-9]+", "-") + "-" : "all-")
                + (status == null || status.isBlank() ? "all" : status) + "-" + LocalDate.now();
        return "csv".equalsIgnoreCase(format) ? exportService.csv(doc, base) : exportService.pdf(doc, base);
    }

    // ----------------------------------------------------------- facility admin

    /** The facility the report is for: own facility for a facility admin; chosen (or all) for the system admin. */
    private Facility scope(Authentication auth, Long facilityId) {
        if (isSystemAdmin(auth)) return facilityId != null ? facilityService.findById(facilityId) : null;
        return staffMemberRepository.findByEmail(auth.getName()).map(StaffMember::getFacility).orElse(null);
    }

    @GetMapping("/facility/reports")
    public String facilityReport(@RequestParam(required = false) Long facilityId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) String rx,
                                 Authentication auth, Model model) {
        boolean sys = isSystemAdmin(auth);
        Facility facility = scope(auth, facilityId);
        LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate t = to != null ? to : LocalDate.now();
        model.addAttribute("kind", "facility");
        model.addAttribute("systemAdmin", sys);
        model.addAttribute("facilities", sys ? facilityService.findAll() : java.util.List.of());
        model.addAttribute("facility", facility);
        model.addAttribute("facilityId", facility != null ? facility.getId() : null);
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("rx", rx == null ? "" : rx);
        if (!sys && facility == null) {
            model.addAttribute("noFacility", true);
            return "report";
        }
        model.addAttribute("doc", reportService.facilityReport(facility, f, t));
        if (rx != null && !rx.isBlank()) model.addAttribute("lookup", reportService.lookupPrescription(rx, facility));
        model.addAttribute("downloadBase", "/facility/reports/download");
        model.addAttribute("query", "from=" + f + "&to=" + t + (facility != null ? "&facilityId=" + facility.getId() : ""));
        return "report";
    }

    @GetMapping("/facility/reports/download")
    public ResponseEntity<byte[]> facilityDownload(@RequestParam(required = false) Long facilityId,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(defaultValue = "pdf") String format,
                                                   @RequestParam(required = false) Integer section,
                                                   Authentication auth) {
        Facility facility = scope(auth, facilityId);
        LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate t = to != null ? to : LocalDate.now();
        ReportDocument doc = reportService.facilityReport(facility, f, t).onlySection(section);
        String base = "NMAS-operations-" + (section != null ? "section" + (section + 1) + "-" : "") + (facility != null ? facility.getFacilityName().replaceAll("[^A-Za-z0-9]+", "-") : "national") + "-" + f + "-to-" + t;
        return "csv".equalsIgnoreCase(format) ? exportService.csv(doc, base) : exportService.pdf(doc, base);
    }
}
