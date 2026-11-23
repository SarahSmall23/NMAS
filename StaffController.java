package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.co.nmas.nmas.entity.AppUser;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.entity.StaffMember;
import za.co.nmas.nmas.repository.AppUserRepository;
import za.co.nmas.nmas.repository.FacilityRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.service.AuditLogService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/staff")
public class StaffController {

    private final AppUserRepository appUserRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final FacilityRepository facilityRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public StaffController(AppUserRepository appUserRepository,
                           StaffMemberRepository staffMemberRepository,
                           FacilityRepository facilityRepository,
                           PasswordEncoder passwordEncoder,
                           AuditLogService auditLogService) {
        this.appUserRepository = appUserRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.facilityRepository = facilityRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    /**
     * The facility this logged-in facility admin is scoped to. Null means
     * no scoping (a system admin, if this page is ever opened up to one,
     * would see and manage every facility's staff) — in practice this page
     * is currently FACILITY_ADMIN-only, so this always resolves to their
     * own facility, and staff with no facility on record are never shown
     * or manageable here (nothing to safely scope them to).
     */
    private Facility scopeFacility(Authentication auth) {
        boolean isSystemAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
        if (isSystemAdmin) return null;
        return staffMemberRepository.findByEmail(auth.getName())
                .map(StaffMember::getFacility)
                .orElse(null);
    }

    private void populateModel(Model model, Authentication auth) {
        Facility scope = scopeFacility(auth);
        List<StaffMember> staffList = staffMemberRepository.findByRoleIn(List.of("FACILITY_ADMIN", "NURSE", "PHARMACIST", "RECEPTIONIST"))
                .stream()
                .filter(s -> scope == null || (s.getFacility() != null && s.getFacility().getId().equals(scope.getId())))
                .toList();

        Map<Long, Boolean> suspendedMap = staffList.stream()
                .collect(Collectors.toMap(
                        StaffMember::getId,
                        s -> appUserRepository.findByEmail(s.getEmail())
                                .map(AppUser::isSuspended)
                                .orElse(false)
                ));

        model.addAttribute("staffList", staffList);
        model.addAttribute("suspendedMap", suspendedMap);
        model.addAttribute("facilities", facilityRepository.findAll());
        model.addAttribute("scopedToFacility", scope);
    }

    @GetMapping("/register")
    public String showForm(Authentication auth, Model model) {
        populateModel(model, auth);
        return "staff-register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String email,
                           @RequestParam String password,
                           @RequestParam String role,
                           @RequestParam String fullName,
                           @RequestParam(required = false) String gender,
                           @RequestParam(required = false) String phoneNumber,
                           @RequestParam(required = false) String alternativePhoneNumber,
                           @RequestParam(required = false) String employeeNumber,
                           @RequestParam(required = false) String qualification,
                           @RequestParam(required = false) String department,
                           @RequestParam(required = false) Long facilityId,
                           Authentication auth,
                           Model model) {

        // A facility admin may only create nurses, pharmacists and receptionists for their own facility;
        // only the system admin may create facility administrators. Never trust the role sent by the form.
        boolean sysAdmin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
        boolean facilityScoped = !sysAdmin;
        boolean allowedRole = List.of("NURSE", "PHARMACIST", "RECEPTIONIST").contains(role)
                || (sysAdmin && "FACILITY_ADMIN".equals(role));
        if (!allowedRole) {
            model.addAttribute("error", "You are not allowed to create that kind of account.");
            populateModel(model, auth);
            return "staff-register";
        }
        if (!facilityScoped && facilityId == null) {
            model.addAttribute("error", "Please choose the facility this account belongs to.");
            populateModel(model, auth);
            return "staff-register";
        }

        if (appUserRepository.findByEmail(email).isPresent()) {
            model.addAttribute("error", "An account with this email already exists.");
            populateModel(model, auth);
            return "staff-register";
        }

        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(role);
        appUserRepository.save(user);

        StaffMember member = new StaffMember();
        member.setEmail(email);
        member.setRole(role);
        member.setFullName(fullName);
        member.setGender(gender);
        member.setPhoneNumber(phoneNumber);
        member.setAlternativePhoneNumber(alternativePhoneNumber);
        member.setEmployeeNumber(employeeNumber);
        member.setQualification(qualification);
        member.setDepartment(department);

        // A facility admin can only ever register staff into their own
        // facility — the facility dropdown, if shown at all, isn't a way
        // around this; their own facility always wins.
        Facility scope = scopeFacility(auth);
        if (scope != null) {
            member.setFacility(scope);
        } else if (facilityId != null) {
            facilityRepository.findById(facilityId).ifPresent(member::setFacility);
        }

        staffMemberRepository.save(member);
        auditLogService.log("STAFF_REGISTERED", "StaffMember", member.getId(),
                role + " account created for " + fullName + " (" + email + ")");

        String label = role.charAt(0) + role.substring(1).toLowerCase();
        model.addAttribute("success", label + " account created for " + fullName + " (" + email + ")");
        populateModel(model, auth);
        return "staff-register";
    }

    @PostMapping("/suspend/{id}")
    public String suspend(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        staffMemberRepository.findById(id).ifPresent(member -> {
            if (!inScope(member, auth)) {
                ra.addFlashAttribute("error", "You can only manage staff registered at your own facility.");
                return;
            }
            appUserRepository.findByEmail(member.getEmail()).ifPresent(user -> {
                user.setSuspended(true);
                appUserRepository.save(user);
            });
            auditLogService.log("STAFF_SUSPENDED", "StaffMember", member.getId(),
                    member.getRole() + " account suspended: " + member.getFullName() + " (" + member.getEmail() + ")");
        });
        return "redirect:/staff/register";
    }

    @PostMapping("/unsuspend/{id}")
    public String unsuspend(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        staffMemberRepository.findById(id).ifPresent(member -> {
            if (!inScope(member, auth)) {
                ra.addFlashAttribute("error", "You can only manage staff registered at your own facility.");
                return;
            }
            appUserRepository.findByEmail(member.getEmail()).ifPresent(user -> {
                user.setSuspended(false);
                appUserRepository.save(user);
            });
            auditLogService.log("STAFF_REACTIVATED", "StaffMember", member.getId(),
                    member.getRole() + " account reactivated: " + member.getFullName() + " (" + member.getEmail() + ")");
        });
        return "redirect:/staff/register";
    }

    @PostMapping("/delete/{id}")
    public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        staffMemberRepository.findById(id).ifPresent(member -> {
            if (!inScope(member, auth)) {
                ra.addFlashAttribute("error", "You can only manage staff registered at your own facility.");
                return;
            }
            auditLogService.log("STAFF_DELETED", "StaffMember", member.getId(),
                    member.getRole() + " account deleted: " + member.getFullName() + " (" + member.getEmail() + ")");
            appUserRepository.findByEmail(member.getEmail()).ifPresent(appUserRepository::delete);
            staffMemberRepository.delete(member);
        });
        return "redirect:/staff/register";
    }

    private boolean inScope(StaffMember member, Authentication auth) {
        Facility scope = scopeFacility(auth);
        if (scope == null) return true; // system admin — unrestricted
        return member.getFacility() != null && member.getFacility().getId().equals(scope.getId());
    }
}
