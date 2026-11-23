package za.co.nmas.nmas.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import za.co.nmas.nmas.entity.AppUser;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.entity.StaffMember;
import za.co.nmas.nmas.repository.AppUserRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;
import za.co.nmas.nmas.service.DispensaryService;
import za.co.nmas.nmas.service.DoctorService;
import za.co.nmas.nmas.service.FacilityService;
import za.co.nmas.nmas.service.RecordAccessService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Facility-admin pages that are always scoped to the admin's own facility:
 * a single staff overview (doctors, nurses, receptionists, pharmacists) and a
 * permanent log of doctors from other facilities who viewed this facility's
 * patients' records.
 */
@Controller
@RequestMapping("/facility")
public class FacilityAdminController {

    private final StaffMemberRepository staffMemberRepository;
    private final AppUserRepository appUserRepository;
    private final DoctorService doctorService;
    private final RecordAccessService recordAccessService;
    private final DispensaryService dispensaryService;
    private final FacilityService facilityService;

    public FacilityAdminController(StaffMemberRepository staffMemberRepository,
                                   AppUserRepository appUserRepository,
                                   DoctorService doctorService,
                                   RecordAccessService recordAccessService,
                                   DispensaryService dispensaryService,
                                   FacilityService facilityService) {
        this.staffMemberRepository = staffMemberRepository;
        this.appUserRepository = appUserRepository;
        this.doctorService = doctorService;
        this.recordAccessService = recordAccessService;
        this.dispensaryService = dispensaryService;
        this.facilityService = facilityService;
    }

    private Facility ownFacility(Authentication auth) {
        return staffMemberRepository.findByEmail(auth.getName())
                .map(StaffMember::getFacility).orElse(null);
    }

    private boolean isSystemAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
    }

    @GetMapping("/staff")
    public String staff(@RequestParam(required = false) Long facilityId, Authentication auth, Model model) {
        boolean sys = isSystemAdmin(auth);
        Facility facility = sys ? facilityService.findById(facilityId) : ownFacility(auth);
        boolean national = sys && facility == null;
        model.addAttribute("systemAdmin", sys);
        model.addAttribute("facilities", sys ? facilityService.findAll() : java.util.List.of());
        model.addAttribute("facilityId", facility != null ? facility.getId() : null);
        model.addAttribute("national", national);
        model.addAttribute("facility", facility);
        if (national || facility != null) {
            List<Doctor> doctors = doctorService.findAll().stream()
                    .filter(d -> national || (d.getFacility() != null && d.getFacility().getId().equals(facility.getId())))
                    .toList();
            model.addAttribute("doctors", doctors);
            model.addAttribute("doctorSuspended", doctorService.getSuspendedMap(doctors));

            List<StaffMember> staff = staffMemberRepository
                    .findByRoleIn(List.of("NURSE", "RECEPTIONIST", "PHARMACIST")).stream()
                    .filter(s -> national || (s.getFacility() != null && s.getFacility().getId().equals(facility.getId())))
                    .toList();
            Map<Long, Boolean> staffSuspended = new HashMap<>();
            for (StaffMember s : staff) {
                staffSuspended.put(s.getId(), appUserRepository.findByEmail(s.getEmail())
                        .map(AppUser::isSuspended).orElse(false));
            }
            model.addAttribute("staff", staff);
            model.addAttribute("staffSuspended", staffSuspended);
        }
        return "facility-staff";
    }

    @GetMapping("/access-log")
    public String accessLog(@RequestParam(required = false) Long facilityId, Authentication auth, Model model) {
        boolean sys = isSystemAdmin(auth);
        Facility facility = sys ? facilityService.findById(facilityId) : ownFacility(auth);
        boolean national = sys && facility == null;
        model.addAttribute("systemAdmin", sys);
        model.addAttribute("facilities", sys ? facilityService.findAll() : java.util.List.of());
        model.addAttribute("facilityId", facility != null ? facility.getId() : null);
        model.addAttribute("national", national);
        model.addAttribute("facility", facility);
        if (national) {
            model.addAttribute("accessLog", recordAccessService.findAccessLogAll());
            model.addAttribute("dispensingLog", dispensaryService.findAllCrossFacilityDispensing());
        } else if (facility != null) {
            model.addAttribute("accessLog", recordAccessService.findAccessLogForFacility(facility.getId()));
            model.addAttribute("dispensingLog", dispensaryService.findCrossFacilityDispensingForHomeFacility(facility.getId()));
        }
        return "facility-access-log";
    }
}
