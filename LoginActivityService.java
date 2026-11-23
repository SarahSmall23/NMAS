package za.co.nmas.nmas.service;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.entity.LoginActivity;
import za.co.nmas.nmas.repository.DoctorRepository;
import za.co.nmas.nmas.repository.LoginActivityRepository;
import za.co.nmas.nmas.repository.PatientRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;

@Service
public class LoginActivityService {

    private final LoginActivityRepository repo;
    private final StaffMemberRepository staffRepo;
    private final DoctorRepository doctorRepo;
    private final PatientRepository patientRepo;

    public LoginActivityService(LoginActivityRepository repo, StaffMemberRepository staffRepo,
                                DoctorRepository doctorRepo, PatientRepository patientRepo) {
        this.repo = repo;
        this.staffRepo = staffRepo;
        this.doctorRepo = doctorRepo;
        this.patientRepo = patientRepo;
    }

    /** Never throws: a tracking problem must never stop someone logging in. */
    public void record(Authentication auth) {
        try {
            if (auth == null || auth.getName() == null) return;
            String email = auth.getName();
            String role = auth.getAuthorities().stream().map(a -> a.getAuthority().replace("ROLE_", "")).findFirst().orElse("USER");
            Facility f = null;
            switch (role) {
                case "DOCTOR" -> f = doctorRepo.findByEmail(email).map(d -> d.getFacility()).orElse(null);
                case "PATIENT" -> f = patientRepo.findByEmail(email).map(p -> p.getRegisteredFacility()).orElse(null);
                case "SYSTEM_ADMIN" -> f = null;
                default -> f = staffRepo.findByEmail(email).map(s -> s.getFacility()).orElse(null);
            }
            LoginActivity la = new LoginActivity();
            la.setEmail(email);
            la.setRole(role);
            la.setFacility(f);
            repo.save(la);
        } catch (Exception e) {
            System.err.println("[NMAS] login tracking skipped: " + e);
        }
    }
}
