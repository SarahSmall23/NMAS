package za.co.nmas.nmas.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import za.co.nmas.nmas.entity.*;
import za.co.nmas.nmas.report.ReportDocument;
import za.co.nmas.nmas.report.ReportSection;
import za.co.nmas.nmas.repository.AppUserRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.service.DoctorService;
import za.co.nmas.nmas.service.FacilityService;
import za.co.nmas.nmas.service.PatientService;
import za.co.nmas.nmas.service.ReportExportService;

import java.time.LocalDate;
import java.util.*;

/**
 * Directory of every registered account, read live from the database: role, name, email,
 * facility and whether the account is blocked. Passwords are stored encrypted, so they can't
 * be shown here (the seeded starting passwords are in the login PDF written at start-up).
 * Facility admins see their own facility; the system admin sees everyone and can filter by facility.
 */
@Controller
public class UserDirectoryController {

    private static final List<String> ROLES = List.of(
            "SYSTEM_ADMIN", "FACILITY_ADMIN", "DOCTOR", "NURSE", "PHARMACIST", "RECEPTIONIST", "PATIENT");

    private final AppUserRepository appUserRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final DoctorService doctorService;
    private final PatientService patientService;
    private final FacilityService facilityService;
    private final ReportExportService exportService;

    public UserDirectoryController(AppUserRepository appUserRepository, StaffMemberRepository staffMemberRepository,
                                   DoctorService doctorService, PatientService patientService,
                                   FacilityService facilityService, ReportExportService exportService) {
        this.appUserRepository = appUserRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.doctorService = doctorService;
        this.patientService = patientService;
        this.facilityService = facilityService;
        this.exportService = exportService;
    }

    private boolean isSystemAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
    }

    private Facility scope(Authentication auth, Long facilityId) {
        if (isSystemAdmin(auth)) return facilityService.findById(facilityId);
        return staffMemberRepository.findByEmail(auth.getName()).map(StaffMember::getFacility).orElse(null);
    }

    private ReportDocument build(Facility facility, String role, boolean restrictToFacility) {
        Map<String, StaffMember> staff = new HashMap<>();
        staffMemberRepository.findAll().forEach(s -> { if (s.getEmail() != null) staff.put(s.getEmail().toLowerCase(), s); });
        Map<String, Doctor> docs = new HashMap<>();
        doctorService.findAll().forEach(d -> { if (d.getEmail() != null) docs.put(d.getEmail().toLowerCase(), d); });
        Map<String, Patient> pats = new HashMap<>();
        patientService.findAll().forEach(p -> { if (p.getEmail() != null) pats.put(p.getEmail().toLowerCase(), p); });

        List<String[]> rows = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        for (AppUser u : appUserRepository.findAll()) {
            String email = u.getEmail() == null ? "" : u.getEmail().toLowerCase();
            String name = "—";
            Facility f = null;
            if (staff.containsKey(email)) { StaffMember s = staff.get(email); name = s.getFullName(); f = s.getFacility(); }
            else if (docs.containsKey(email)) { Doctor d = docs.get(email); name = "Dr. " + d.getFullName(); f = d.getFacility(); }
            else if (pats.containsKey(email)) { Patient p = pats.get(email); name = p.getFullName(); f = p.getRegisteredFacility(); }
            else if ("SYSTEM_ADMIN".equals(u.getRole())) name = "System Administrator";

            boolean national = "SYSTEM_ADMIN".equals(u.getRole());
            if (facility != null && (national || f == null || !f.getId().equals(facility.getId()))) continue;
            if (restrictToFacility && facility == null) continue; // facility admin without a facility sees nothing
            if (role != null && !role.isBlank() && !role.equals(u.getRole())) continue;

            rows.add(new String[]{u.getRole(), name, u.getEmail(),
                    national ? "All facilities" : (f != null ? f.getFacilityName() : "—"),
                    u.isSuspended() ? "Blocked" : "Active"});
            counts.merge(u.getRole(), 1, Integer::sum);
        }
        rows.sort(Comparator.<String[]>comparingInt(r -> { int i = ROLES.indexOf(r[0]); return i < 0 ? 99 : i; })
                .thenComparing(r -> r[3]).thenComparing(r -> r[1]));

        ReportDocument doc = new ReportDocument("Registered Users — " + (facility != null ? facility.getFacilityName() : "All facilities"),
                rows.size() + " account(s)" + (role != null && !role.isBlank() ? " · role " + role : "")
                + " · passwords are stored encrypted and are not shown");
        ReportSection sum = new ReportSection("Accounts by role", List.of("Role", "Accounts"));
        counts.forEach((k, v) -> sum.add(k, String.valueOf(v)));
        doc.add(sum);
        ReportSection list = new ReportSection("All accounts", List.of("Role", "Name", "Email / username", "Facility", "Status"));
        rows.forEach(r -> list.add(r));
        doc.add(list);
        return doc;
    }

    @GetMapping("/admin/users")
    public String users(@RequestParam(required = false) Long facilityId, @RequestParam(required = false) String role,
                        Authentication auth, Model model) {
        boolean sys = isSystemAdmin(auth);
        Facility facility = scope(auth, facilityId);
        model.addAttribute("kind", "users");
        model.addAttribute("systemAdmin", sys);
        model.addAttribute("facilities", sys ? facilityService.findAll() : List.of());
        model.addAttribute("facilityId", facility != null ? facility.getId() : null);
        model.addAttribute("roleOptions", ROLES);
        model.addAttribute("role", role == null ? "" : role);
        if (!sys && facility == null) { model.addAttribute("noFacility", true); return "report"; }
        model.addAttribute("doc", build(facility, role, !sys));
        model.addAttribute("downloadBase", "/admin/users/download");
        model.addAttribute("query", "role=" + (role == null ? "" : role) + (facility != null ? "&facilityId=" + facility.getId() : ""));
        return "report";
    }

    @GetMapping("/admin/users/download")
    public ResponseEntity<byte[]> download(@RequestParam(required = false) Long facilityId, @RequestParam(required = false) String role,
                                           @RequestParam(defaultValue = "pdf") String format, Authentication auth) {
        boolean sys = isSystemAdmin(auth);
        Facility facility = scope(auth, facilityId);
        ReportDocument doc = build(facility, role, !sys);
        String base = "NMAS-registered-users-" + (facility != null ? facility.getFacilityName().replaceAll("[^A-Za-z0-9]+", "-") : "all") + "-" + LocalDate.now();
        return "csv".equalsIgnoreCase(format) ? exportService.csv(doc, base) : exportService.pdf(doc, base);
    }
}
