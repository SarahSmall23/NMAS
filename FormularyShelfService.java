package za.co.nmas.nmas.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.entity.Medication;
import za.co.nmas.nmas.entity.MedicationInventory;
import za.co.nmas.nmas.repository.FacilityRepository;
import za.co.nmas.nmas.repository.MedicationInventoryRepository;
import za.co.nmas.nmas.repository.MedicationRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Guarantees every facility's pharmacy shelf carries the COMPLETE national
 * formulary: for every facility and every medication in the catalogue that
 * has no inventory row yet, a row is created (with starter stock the
 * pharmacist then manages). Runs at startup, whenever a facility is
 * registered and whenever a medication is added, so no pharmacist ever
 * sees a partial or empty list.
 */
@Service
@Order(110)
public class FormularyShelfService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FormularyShelfService.class);

    private final FacilityRepository facilityRepository;
    private final MedicationRepository medicationRepository;
    private final MedicationInventoryRepository inventoryRepository;

    public FormularyShelfService(FacilityRepository facilityRepository,
                                 MedicationRepository medicationRepository,
                                 MedicationInventoryRepository inventoryRepository) {
        this.facilityRepository = facilityRepository;
        this.medicationRepository = medicationRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        int added = 0;
        for (Facility f : facilityRepository.findAll()) added += ensureShelf(f);
        if (added > 0) log.info("[NMAS] Formulary check added {} missing shelf rows across facilities", added);
    }

    /** Adds every catalogue medication this facility is missing. Returns how many rows were created. */
    public int ensureShelf(Facility facility) {
        if (facility == null || facility.getId() == null) return 0;
        int added = 0;
        List<Medication> meds = medicationRepository.findAll();
        for (Medication m : meds) {
            if (m.getId() == null) continue;
            if (inventoryRepository.findFirstByMedicationIdAndFacilityId(m.getId(), facility.getId()).isPresent()) continue;
            inventoryRepository.save(newRow(m, facility));
            added++;
        }
        return added;
    }

    /** A newly added medication goes on every facility's shelf. */
    public int ensureMedicationEverywhere(Medication m) {
        if (m == null || m.getId() == null) return 0;
        int added = 0;
        for (Facility f : facilityRepository.findAll()) {
            if (inventoryRepository.findFirstByMedicationIdAndFacilityId(m.getId(), f.getId()).isPresent()) continue;
            inventoryRepository.save(newRow(m, f));
            added++;
        }
        return added;
    }

    private MedicationInventory newRow(Medication m, Facility f) {
        MedicationInventory inv = new MedicationInventory();
        inv.setFacility(f);
        inv.setMedication(m);
        inv.setMedicationName(m.getMedicationName());
        inv.setGenericName(m.getGenericName());
        boolean packs = m.getPillsPerPack() != null && m.getPillsPerPack() > 0;
        String form = m.getDosageForm() == null ? "" : m.getDosageForm().toLowerCase();
        String unit;
        if (packs) unit = "Packs";
        else if (form.contains("cream") || form.contains("ointment") || form.contains("gel")) unit = "Tubes";
        else if (form.contains("syrup") || form.contains("suspension") || form.contains("lotion")
                || form.contains("drop") || form.contains("solution")) unit = "Bottles";
        else if (form.contains("inject") || form.contains("vial")) unit = "Vials";
        else if (form.contains("inhal")) unit = "Inhalers";
        else unit = "Packs";
        inv.setUnit(unit);
        if (packs) inv.setPillsPerPack(m.getPillsPerPack());
        inv.setQuantityInStock(20);
        inv.setReorderLevel(5);
        inv.setSupplier("NMAS Formulary");
        inv.setExpiryDate(LocalDate.now().plusMonths(18));
        String code = m.getMedicationCode() == null ? String.valueOf(m.getId()) : m.getMedicationCode().replace("MED-", "");
        inv.setBatchNumber("LOT-" + code);
        return inv;
    }
}
