package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Medication;
import za.co.nmas.nmas.entity.MedicationInventory;
import za.co.nmas.nmas.repository.MedicationInventoryRepository;
import za.co.nmas.nmas.repository.MedicationRepository;

import java.util.List;

@Service
public class MedicationService {

    private final MedicationRepository medicationRepository;
    private final MedicationInventoryRepository medicationInventoryRepository;

    public MedicationService(MedicationRepository medicationRepository,
                              MedicationInventoryRepository medicationInventoryRepository) {
        this.medicationRepository = medicationRepository;
        this.medicationInventoryRepository = medicationInventoryRepository;
    }

    /**
     * Throws if this medication's stock at the given facility has expired or run out, so a
     * doctor can never prescribe something the pharmacy cannot (and must not) hand over.
     * A medication with no inventory row is left alone here; the pickers only list stocked items.
     */
    public void assertPrescribable(Medication medication, za.co.nmas.nmas.entity.Facility facility) {
        if (medication == null || medication.getId() == null) return;
        MedicationInventory inv = (facility != null
                ? medicationInventoryRepository.findFirstByMedicationIdAndFacilityId(medication.getId(), facility.getId())
                : medicationInventoryRepository.findFirstByMedicationId(medication.getId())).orElse(null);
        if (inv == null) return;
        if (inv.isExpired()) {
            throw new IllegalStateException(medication.getMedicationName() + " is not available: it expired on "
                    + inv.getExpiryDate() + ". It cannot be prescribed.");
        }
        if (inv.isOutOfStock()) {
            throw new IllegalStateException(medication.getMedicationName()
                    + " is not available: it is out of stock. It cannot be prescribed.");
        }
    }

    public List<Medication> findAllActive() {
        return medicationRepository.findByActiveTrue();
    }

    public List<Medication> findAll() {
        return medicationRepository.findAll();
    }

    public Medication findById(Long id) {
        return medicationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Medication not found: " + id));
    }

    /**
     * Medications a doctor can actually prescribe against real stock —
     * everything currently held in the (national) inventory, formulary
     * details included (dosage form, strength, storage unit), sorted
     * alphabetically. Used to populate the medication picker on the
     * prescription form so doctors see exactly how each item is stored
     * (pack of tablets, vial, tube, bottle, inhaler...) rather than a
     * bare formulary list disconnected from what's dispensable.
     */
    public List<MedicationInventory> findAllActiveInInventory() {
        return findAllActiveInInventory(null);
    }

    /**
     * Medication a doctor can prescribe from THEIR facility's own stock. With no
     * facility (admins), every facility's rows are returned but each medication
     * only once.
     */
    public List<MedicationInventory> findAllActiveInInventory(za.co.nmas.nmas.entity.Facility facility) {
        List<MedicationInventory> rows = facility != null
                ? medicationInventoryRepository.findByFacilityIdOrderByMedicationNameAsc(facility.getId())
                : medicationInventoryRepository.findAllByOrderByMedicationNameAsc();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        return rows.stream()
                // Only formulary-linked rows can actually be prescribed (a prescription
                // line needs a Medication FK) — pharmacy-added items with no formulary
                // link aren't selectable here even though they show up in Inventory.
                .filter(inv -> inv.getMedication() != null && inv.getMedication().isActive())
                .filter(inv -> facility != null || seen.add(inv.getMedication().getId()))
                .toList();
    }
}
