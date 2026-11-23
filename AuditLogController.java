package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import za.co.nmas.nmas.entity.AuditLog;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.entity.StaffMember;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.service.AuditLogService;
import za.co.nmas.nmas.service.DoctorService;
import za.co.nmas.nmas.service.FacilityService;
import za.co.nmas.nmas.service.PatientService;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Controller
@RequestMapping("/audit-log")
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final StaffMemberRepository staffMemberRepository;
    private final DoctorService doctorService;
    private final PatientService patientService;
    private final FacilityService facilityService;

    public AuditLogController(AuditLogService auditLogService,
                              StaffMemberRepository staffMemberRepository,
                              DoctorService doctorService,
                              PatientService patientService,
                              FacilityService facilityService) {
        this.auditLogService = auditLogService;
        this.staffMemberRepository = staffMemberRepository;
        this.doctorService = doctorService;
        this.patientService = patientService;
        this.facilityService = facilityService;
    }

    /** Logins (lower-case emails) of every staff member, doctor and patient registered at the given facility. */
    private Set<String> usernamesAt(Facility facility) {
        Long fid = facility.getId();
        Set<String> names = new HashSet<>();
        staffMemberRepository.findAll().stream()
                .filter(s -> s.getFacility() != null && s.getFacility().getId().equals(fid) && s.getEmail() != null)
                .forEach(s -> names.add(s.getEmail().toLowerCase()));
        doctorService.findAll().stream()
                .filter(d -> d.getFacility() != null && d.getFacility().getId().equals(fid) && d.getEmail() != null)
                .forEach(d -> names.add(d.getEmail().toLowerCase()));
        patientService.findAll().stream()
                .filter(p -> p.getRegisteredFacility() != null && p.getRegisteredFacility().getId().equals(fid) && p.getEmail() != null)
                .forEach(p -> names.add(p.getEmail().toLowerCase()));
        return names;
    }

    @GetMapping
    public String view(@RequestParam(required = false) String action,
                       @RequestParam(required = false) String role,
                       @RequestParam(required = false) String search,
                       @RequestParam(required = false) Long facilityId,
                       Authentication auth,
                       Model model) {

        boolean systemAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));

        Facility chosen = systemAdmin ? facilityService.findById(facilityId) : null;
        if (systemAdmin) {
            model.addAttribute("facilities", facilityService.findAll());
            model.addAttribute("facilityId", chosen != null ? chosen.getId() : null);
        }

        if (systemAdmin && chosen == null) {
            model.addAttribute("logs", auditLogService.findFiltered(action, role, search));
            model.addAttribute("actions", auditLogService.distinctActions());
            model.addAttribute("roles", auditLogService.distinctRoles());
            model.addAttribute("totalCount", auditLogService.countTotal());
            model.addAttribute("todayCount", auditLogService.countToday());
        } else {
            // Facility admin: only activity by staff and patients registered at their own facility.
            Facility facility = systemAdmin ? chosen : staffMemberRepository.findByEmail(auth.getName())
                    .map(StaffMember::getFacility).orElse(null);
            Set<String> users = facility != null ? usernamesAt(facility) : Set.of();
            List<AuditLog> scopedAll = auditLogService.findAllForUsers(users);
            LocalDate today = LocalDate.now();
            model.addAttribute("logs", auditLogService.findFilteredForUsers(users, action, role, search));
            model.addAttribute("actions", new TreeSet<>(scopedAll.stream().map(AuditLog::getAction).filter(x -> x != null).toList()));
            model.addAttribute("roles", new TreeSet<>(scopedAll.stream().map(AuditLog::getRole).filter(x -> x != null).toList()));
            model.addAttribute("totalCount", scopedAll.size());
            model.addAttribute("todayCount", scopedAll.stream()
                    .filter(l -> l.getTimestamp() != null && l.getTimestamp().toLocalDate().equals(today)).count());
            model.addAttribute("scopedFacility", facility);
        }
        model.addAttribute("selectedAction", action);
        model.addAttribute("selectedRole", role);
        model.addAttribute("search", search);
        return "audit-log";
    }
}
