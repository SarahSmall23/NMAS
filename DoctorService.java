package za.co.nmas.nmas.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.dto.DoctorForm;
import za.co.nmas.nmas.entity.AppUser;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.repository.AppUserRepository;
import za.co.nmas.nmas.repository.DoctorRepository;
import za.co.nmas.nmas.repository.FacilityRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final AppUserRepository appUserRepository;
    private final FacilityRepository facilityRepository;
    private final PasswordEncoder passwordEncoder;

    public DoctorService(DoctorRepository doctorRepository,
                         AppUserRepository appUserRepository,
                         FacilityRepository facilityRepository,
                         PasswordEncoder passwordEncoder) {
        this.doctorRepository = doctorRepository;
        this.appUserRepository = appUserRepository;
        this.facilityRepository = facilityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Doctor register(DoctorForm form) {
        Doctor doctor = new Doctor();
        doctor.setFullName(form.getFullName());
        doctor.setGender(form.getGender());
        doctor.setEmail(form.getEmail());
        doctor.setPhoneNumber(form.getPhoneNumber());
        doctor.setAlternativePhoneNumber(form.getAlternativePhoneNumber());
        doctor.setSpecialization(form.getSpecialization());
        doctor.setQualification(form.getQualification());
        doctor.setHpcsaNumber(form.getHpcsaNumber());

        if (form.getFacilityId() != null) {
            facilityRepository.findById(form.getFacilityId())
                    .ifPresent(doctor::setFacility);
        }

        doctorRepository.save(doctor);

        AppUser loginAccount = new AppUser();
        loginAccount.setEmail(form.getEmail());
        loginAccount.setPassword(passwordEncoder.encode(form.getPassword()));
        loginAccount.setRole("DOCTOR");
        appUserRepository.save(loginAccount);

        return doctor;
    }

    public Optional<Doctor> findByEmail(String email) {
        return doctorRepository.findByEmail(email);
    }

    public List<Doctor> findAll() {
        return doctorRepository.findAll();
    }

    /**
     * Used by the AIDEN chatbot to recommend a real NMAS doctor once it has
     * concluded a specialization is needed. Falls back to any available
     * doctor (e.g. a General Practitioner) if no exact specialization match
     * is found, so the patient always gets a concrete recommendation.
     */
    public Optional<Doctor> findBySpecialization(String specialization) {
        if (specialization == null || specialization.isBlank()) {
            return Optional.empty();
        }
        List<Doctor> matches = doctorRepository.findBySpecializationIgnoreCaseContaining(specialization);
        if (!matches.isEmpty()) {
            return Optional.of(matches.get(0));
        }
        // Fallback: try General Practitioner, otherwise any doctor on file
        List<Doctor> gp = doctorRepository.findBySpecializationIgnoreCaseContaining("General");
        if (!gp.isEmpty()) {
            return Optional.of(gp.get(0));
        }
        List<Doctor> all = doctorRepository.findAll();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    public long count() {
        return doctorRepository.count();
    }

    public boolean existsByEmail(String email) {
        return doctorRepository.findByEmail(email).isPresent();
    }

    public boolean existsByHpcsaNumber(String hpcsaNumber) {
        return doctorRepository.existsByHpcsaNumber(hpcsaNumber);
    }

    public boolean loginExists(String email) {
        return appUserRepository.findByEmail(email).isPresent();
    }

    public Optional<Doctor> findById(Long id) {
        return doctorRepository.findById(id);
    }

    public Map<Long, Boolean> getSuspendedMap(List<Doctor> doctors) {
        return doctors.stream()
                .collect(Collectors.toMap(
                        Doctor::getId,
                        d -> appUserRepository.findByEmail(d.getEmail())
                                .map(AppUser::isSuspended)
                                .orElse(false)
                ));
    }

    public void suspend(Long id) {
        doctorRepository.findById(id).ifPresent(doctor ->
                appUserRepository.findByEmail(doctor.getEmail()).ifPresent(user -> {
                    user.setSuspended(true);
                    appUserRepository.save(user);
                })
        );
    }

    public void unsuspend(Long id) {
        doctorRepository.findById(id).ifPresent(doctor ->
                appUserRepository.findByEmail(doctor.getEmail()).ifPresent(user -> {
                    user.setSuspended(false);
                    appUserRepository.save(user);
                })
        );
    }

    public void delete(Long id) {
        doctorRepository.findById(id).ifPresent(doctor -> {
            appUserRepository.findByEmail(doctor.getEmail()).ifPresent(appUserRepository::delete);
            doctorRepository.delete(doctor);
        });
    }
}
