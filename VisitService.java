package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Visit;
import za.co.nmas.nmas.repository.DoctorRepository;
import za.co.nmas.nmas.repository.FacilityRepository;
import za.co.nmas.nmas.repository.PatientRepository;
import za.co.nmas.nmas.repository.VisitRepository;

import java.util.List;

@Service
public class VisitService {

    private final VisitRepository visitRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final FacilityRepository facilityRepository;

    public VisitService(VisitRepository visitRepository,
                        PatientRepository patientRepository,
                        DoctorRepository doctorRepository,
                        FacilityRepository facilityRepository) {
        this.visitRepository = visitRepository;
        this.patientRepository = patientRepository;
        this.doctorRepository = doctorRepository;
        this.facilityRepository = facilityRepository;
    }

    public Visit save(Long patientId, Long doctorId, Long facilityId,
                      String symptoms, String diagnosis, String treatment, String notes) {

        Visit visit = new Visit();
        visit.setPatient(patientRepository.findById(patientId)
                .orElseThrow(() -> new RuntimeException("Patient not found")));

        if (doctorId != null) {
            doctorRepository.findById(doctorId).ifPresent(visit::setDoctor);
        }
        if (facilityId != null) {
            facilityRepository.findById(facilityId).ifPresent(visit::setFacility);
        }

        visit.setSymptoms(symptoms);
        visit.setDiagnosis(diagnosis);
        visit.setTreatment(treatment);
        visit.setNotes(notes);
        visit.setStatus("OPEN");

        return visitRepository.save(visit);
    }

    public void close(Long id) {
        Visit visit = visitRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Visit not found"));
        visit.setStatus("CLOSED");
        visitRepository.save(visit);
    }

    public List<Visit> findAll() {
        return visitRepository.findAll();
    }

    public List<Visit> findByPatientId(Long patientId) {
        return visitRepository.findByPatientId(patientId);
    }

    public long count() {
        return visitRepository.count();
    }
}
