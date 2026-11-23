package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.repository.FacilityRepository;

import java.util.List;

@Service
public class FacilityService {

    private final FacilityRepository facilityRepository;
    private final FormularyShelfService formularyShelfService;

    public FacilityService(FacilityRepository facilityRepository, FormularyShelfService formularyShelfService) {
        this.facilityRepository = facilityRepository;
        this.formularyShelfService = formularyShelfService;
    }

    public Facility register(Facility facility) {
        Facility saved = facilityRepository.save(facility);
        // Every new facility's pharmacy starts with the full national medication list.
        formularyShelfService.ensureShelf(saved);
        return saved;
    }

    public List<Facility> findAll() {
        return facilityRepository.findAll();
    }

    /** The facility with this id, or null if there isn't one. */
    public Facility findById(Long id) {
        return id == null ? null : facilityRepository.findById(id).orElse(null);
    }

    public long count() {
        return facilityRepository.count();
    }
}
