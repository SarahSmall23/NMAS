package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Patient;
import za.co.nmas.nmas.repository.PatientRepository;

import java.util.ArrayList;
import java.util.List;

@Service
public class SearchService {

    private final PatientRepository patientRepository;

    public SearchService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    public List<Patient> search(String term) {
        List<Patient> results = new ArrayList<>();

        patientRepository.findByIdNumber(term).ifPresent(results::add);
        patientRepository.findByPassportNumber(term).ifPresent(results::add);
        results.addAll(patientRepository.findByFullNameContainingIgnoreCase(term));
        results.addAll(patientRepository.findByPhoneNumberContaining(term));

        return results;
    }
}
