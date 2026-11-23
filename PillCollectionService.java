package za.co.nmas.nmas.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.nmas.nmas.entity.*;
import za.co.nmas.nmas.repository.PillCollectionRepository;
import za.co.nmas.nmas.repository.PrescriptionRepository;
import za.co.nmas.nmas.repository.StaffMemberRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * Monthly pill collection for repeat (chronic) prescriptions. On the 1st of each
 * month a collection appears for every active repeat prescription; the patient
 * has the first FOUR DAYS of the month to collect. Reception confirms the patient
 * is due, then the pharmacist hands over the pills and marks it collected. If the
 * window passes, that month's note is no longer valid.
 */
@Service
@org.springframework.core.annotation.Order(100)
public class PillCollectionService implements ApplicationRunner {

    public static final int WINDOW_DAYS = 4;

    private final PillCollectionRepository repo;
    private final PrescriptionRepository prescriptionRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    public PillCollectionService(PillCollectionRepository repo, PrescriptionRepository prescriptionRepository,
                                 StaffMemberRepository staffMemberRepository, NotificationService notificationService,
                                 AuditLogService auditLogService) {
        this.repo = repo;
        this.prescriptionRepository = prescriptionRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------------ rules

    /** Months a duration covers: "Ongoing (chronic)" = unlimited (-1), "3 months" = 3, anything shorter = 0/1. */
    static int monthsOf(String duration) {
        if (duration == null) return 0;
        String d = duration.trim().toLowerCase();
        if (d.contains("ongoing") || d.contains("chronic")) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*month").matcher(d);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Longest duration across the prescription's lines (-1 = ongoing). */
    static int coverageMonths(Prescription rx) {
        int best = 0;
        List<PrescriptionMedication> lines = rx.getMedicationLines();
        if (lines != null) {
            for (PrescriptionMedication l : lines) {
                int m = monthsOf(l.getDuration());
                if (m == -1) return -1;
                best = Math.max(best, m);
            }
        }
        int legacy = monthsOf(rx.getDuration());
        if (legacy == -1) return -1;
        return Math.max(best, legacy);
    }

    /** A repeat prescription is one that covers two or more months (or is ongoing). */
    public static boolean isRepeatPrescription(Prescription rx) {
        int m = coverageMonths(rx);
        return m == -1 || m >= 2;
    }

    // ------------------------------------------------------- generation

    @Override
    public void run(ApplicationArguments args) {
        refresh();
    }

    @Scheduled(cron = "0 5 0 * * *")
    public void refresh() {
        try {
            generateForMonth(YearMonth.now());
            expireOverdue();
        } catch (Exception e) {
            System.err.println("[NMAS] Pill-collection refresh skipped: " + e.getMessage());
        }
    }

    @Transactional
    public int generateForMonth(YearMonth ym) {
        LocalDate start = ym.atDay(1);
        LocalDate end = start.plusDays(WINDOW_DAYS - 1);
        int created = 0;
        for (Prescription rx : prescriptionRepository.findAll()) {
            if (!"ACTIVE".equals(rx.getStatus()) || rx.getPatient() == null || rx.getPrescriptionDate() == null) continue;
            int coverage = coverageMonths(rx);
            if (!(coverage == -1 || coverage >= 2)) continue;
            YearMonth issued = YearMonth.from(rx.getPrescriptionDate());
            long monthsSince = java.time.temporal.ChronoUnit.MONTHS.between(issued, ym);
            if (monthsSince < 1) continue;                                   // first fill goes through the dispensary queue
            if (coverage != -1 && monthsSince >= coverage) continue;         // course finished
            if (repo.existsByPrescriptionIdAndWindowStart(rx.getId(), start)) continue;

            PillCollection c = new PillCollection();
            c.setPrescription(rx);
            c.setPatient(rx.getPatient());
            c.setFacility(rx.getIssuingFacility());
            c.setWindowStart(start);
            c.setWindowEnd(end);
            repo.save(c);
            created++;

            if (rx.getPatient().getEmail() != null) {
                notificationService.notify(rx.getPatient().getEmail(), "PILL_COLLECTION_DUE",
                        "Your repeat medication (" + rx.getDisplayNumber() + ") is ready to collect"
                                + (rx.getIssuingFacility() != null ? " at " + rx.getIssuingFacility().getFacilityName() : "")
                                + " between " + start + " and " + end
                                + ". Report to reception first — after " + end + " this month's note is no longer valid.",
                        "/patient-dashboard");
            }
        }
        return created;
    }

    @Transactional
    public void expireOverdue() {
        for (PillCollection c : repo.findByStatusInAndWindowEndLessThan(List.of("DUE", "CHECKED_IN"), LocalDate.now())) {
            c.setStatus("EXPIRED");
            repo.save(c);
        }
    }

    // ---------------------------------------------------------- queries

    /** Everyone at this facility whose collection window includes today, plus today's completed ones. */
    public List<PillCollection> dueToday(Long facilityId) {
        LocalDate t = LocalDate.now();
        return repo.findByFacilityIdAndWindowStartLessThanEqualAndWindowEndGreaterThanEqualOrderByWindowEndAscIdAsc(facilityId, t, t);
    }

    public List<PillCollection> recentlyCollected(Long facilityId) {
        return repo.findByFacilityIdAndCollectedAtAfterOrderByCollectedAtDesc(facilityId, LocalDate.now().minusDays(7).atStartOfDay());
    }

    // ---------------------------------------------------------- actions

    private PillCollection forFacility(Long id, Long facilityId) {
        PillCollection c = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Collection not found"));
        if (c.getFacility() == null || !c.getFacility().getId().equals(facilityId))
            throw new IllegalArgumentException("That collection belongs to a different facility.");
        return c;
    }

    /** Reception confirms this patient really is due to collect today. */
    @Transactional
    public PillCollection confirmAtReception(Long id, Facility facility, String receptionistEmail) {
        PillCollection c = forFacility(id, facility.getId());
        if (!"DUE".equals(c.getStatus()))
            throw new IllegalArgumentException("This collection is " + c.getStatus().toLowerCase().replace('_', ' ') + " already.");
        if (!c.isOpenToday())
            throw new IllegalArgumentException("The collection window (" + c.getWindowStart() + " to " + c.getWindowEnd() + ") is not open today — this note is not valid.");
        c.setStatus("CHECKED_IN");
        c.setCheckedInAt(LocalDateTime.now());
        c.setCheckedInBy(receptionistEmail);
        repo.save(c);
        auditLogService.log("PILL_COLLECTION_CONFIRMED", "PillCollection", id,
                c.getPatient().getFullName() + " confirmed at reception for " + c.getPrescription().getDisplayNumber());
        for (StaffMember ph : staffMemberRepository.findByFacilityIdAndRole(facility.getId(), "PHARMACIST")) {
            notificationService.notify(ph.getEmail(), "PILL_COLLECTION_READY",
                    c.getPatient().getFullName() + " is at reception to collect repeat medication (" + c.getPrescription().getDisplayNumber() + ").",
                    "/dispensary");
        }
        return c;
    }

    /** Pharmacist hands over the pills. Only possible once reception has confirmed the patient. */
    @Transactional
    public PillCollection markCollected(Long id, Facility facility, String pharmacistEmail) {
        PillCollection c = forFacility(id, facility.getId());
        if ("COLLECTED".equals(c.getStatus())) throw new IllegalArgumentException("Already marked as collected.");
        if (!"CHECKED_IN".equals(c.getStatus()))
            throw new IllegalArgumentException("Reception hasn't confirmed this patient yet — they must go to the reception desk first.");
        if (!c.isOpenToday())
            throw new IllegalArgumentException("The collection window has closed — this prescription note is no longer valid.");
        c.setStatus("COLLECTED");
        c.setCollectedAt(LocalDateTime.now());
        c.setCollectedBy(pharmacistEmail);
        repo.save(c);
        auditLogService.log("PILLS_COLLECTED", "PillCollection", id,
                c.getPatient().getFullName() + " collected pills for " + c.getPrescription().getDisplayNumber());
        return c;
    }
}
