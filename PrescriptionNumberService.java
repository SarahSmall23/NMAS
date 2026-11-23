package za.co.nmas.nmas.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.Prescription;
import za.co.nmas.nmas.repository.PrescriptionRepository;

/**
 * Prescription numbers tie every prescription to the doctor who issued it:
 *
 *     RX-<doctor code>-<running count for that doctor>      e.g. RX-0327-0004
 *
 * The doctor code is the doctor's 4-digit NMAS id, so a facility admin can read
 * a number and know exactly whose prescription it is; the last part just counts
 * up for each prescription that doctor issues.
 */
@Service
@org.springframework.core.annotation.Order(100)
public class PrescriptionNumberService implements ApplicationRunner {

    private final PrescriptionRepository prescriptionRepository;

    public PrescriptionNumberService(PrescriptionRepository prescriptionRepository) {
        this.prescriptionRepository = prescriptionRepository;
    }

    public static String doctorCode(Doctor doctor) {
        return doctor == null || doctor.getId() == null ? "0000" : String.format("%04d", doctor.getId());
    }

    /** Gives the prescription its number (if it doesn't have one). Call before the first save. */
    public synchronized void assign(Prescription p) {
        if (p.getPrescriptionNumber() != null) return;
        Doctor d = p.getDoctor();
        long seq = (d != null && d.getId() != null)
                ? prescriptionRepository.countByDoctorId(d.getId()) + 1
                : prescriptionRepository.count() + 1;
        String prefix = "RX-" + doctorCode(d) + "-";
        String candidate = prefix + String.format("%04d", seq);
        while (prescriptionRepository.existsByPrescriptionNumber(candidate)) {
            seq++;
            candidate = prefix + String.format("%04d", seq);
        }
        p.setPrescriptionNumber(candidate);
    }

    /** Prescriptions issued before numbering existed get a number once, at startup. */
    @Override
    public void run(ApplicationArguments args) {
        try {
            for (Prescription p : prescriptionRepository.findByPrescriptionNumberIsNull()) {
                assign(p);
                prescriptionRepository.save(p);
            }
        } catch (Exception e) {
            System.err.println("[NMAS] Prescription-number backfill skipped: " + e.getMessage());
        }
    }
}
