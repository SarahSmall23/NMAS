package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.*;
import za.co.nmas.nmas.report.ReportDocument;
import za.co.nmas.nmas.report.ReportSection;
import za.co.nmas.nmas.repository.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Builds the pharmacist stock/expiry report and the facility operations report. */
@Service
public class ReportService {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final MedicationInventoryRepository inventoryRepository;
    private final ConsultationService consultationService;
    private final PrescriptionRepository prescriptionRepository;
    private final AppointmentService appointmentService;
    private final WalkInVisitRepository walkInRepository;
    private final DispensaryOrderRepository dispensaryOrderRepository;
    private final DoctorService doctorService;
    private final StaffMemberRepository staffMemberRepository;
    private final PatientService patientService;
    private final AuditLogService auditLogService;
    private final PillCollectionRepository pillCollectionRepository;
    private final OperationsInsightService insightService;

    public ReportService(MedicationInventoryRepository inventoryRepository, ConsultationService consultationService,
                         PrescriptionRepository prescriptionRepository, AppointmentService appointmentService,
                         WalkInVisitRepository walkInRepository, DispensaryOrderRepository dispensaryOrderRepository,
                         DoctorService doctorService, StaffMemberRepository staffMemberRepository,
                         PatientService patientService, AuditLogService auditLogService,
                         PillCollectionRepository pillCollectionRepository,
                         OperationsInsightService insightService) {
        this.insightService = insightService;
        this.inventoryRepository = inventoryRepository;
        this.consultationService = consultationService;
        this.prescriptionRepository = prescriptionRepository;
        this.appointmentService = appointmentService;
        this.walkInRepository = walkInRepository;
        this.dispensaryOrderRepository = dispensaryOrderRepository;
        this.doctorService = doctorService;
        this.staffMemberRepository = staffMemberRepository;
        this.patientService = patientService;
        this.auditLogService = auditLogService;
        this.pillCollectionRepository = pillCollectionRepository;
    }

    // ================================================== PHARMACIST: STOCK & EXPIRY

    public static final Map<String, String> STOCK_FILTERS = new LinkedHashMap<>();
    static {
        STOCK_FILTERS.put("all", "All medication");
        STOCK_FILTERS.put("instock", "In stock");
        STOCK_FILTERS.put("low", "Low stock (running out)");
        STOCK_FILTERS.put("out", "Out of stock");
        STOCK_FILTERS.put("restock", "Needs restocking (low + out of stock)");
        STOCK_FILTERS.put("expiring", "Expiring within 6 months");
        STOCK_FILTERS.put("expired", "Expired");
    }

    public List<MedicationInventory> filterInventory(String status, Long inventoryId, Facility facility) {
        String st = status == null || status.isBlank() ? "all" : status;
        Comparator<MedicationInventory> byName =
                Comparator.comparing(MedicationInventory::getMedicationName, String.CASE_INSENSITIVE_ORDER);
        Comparator<MedicationInventory> byExpiry =
                Comparator.comparing(MedicationInventory::getExpiryDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()));
        Comparator<MedicationInventory> order = (st.equals("expiring") || st.equals("expired")) ? byExpiry : byName;
        return inventoryRepository.findAllByOrderByMedicationNameAsc().stream()
                .filter(m -> facility == null || (m.getFacility() != null && m.getFacility().getId().equals(facility.getId())))
                .filter(m -> inventoryId == null || m.getId().equals(inventoryId))
                .filter(m -> switch (st) {
                    case "instock" -> !m.isOutOfStock() && !m.isLowStock();
                    case "low" -> m.isLowStock();
                    case "out" -> m.isOutOfStock();
                    case "restock" -> m.isLowStock() || m.isOutOfStock();
                    case "expiring" -> m.isExpiringSoon();
                    case "expired" -> m.isExpired();
                    default -> true;
                })
                .sorted(order)
                .toList();
    }

    public ReportDocument inventoryReport(String status, Long inventoryId, Facility facility) {
        List<MedicationInventory> items = filterInventory(status, inventoryId, facility);
        String label = STOCK_FILTERS.getOrDefault(status == null || status.isBlank() ? "all" : status, "All medication");
        ReportDocument doc = new ReportDocument("Pharmacy Stock Report — " + (facility != null ? facility.getFacilityName() : "All facilities"),
                label + " — " + items.size() + " item(s)");

        ReportSection s = new ReportSection(label,
                List.of("Facility", "Medication", "Generic name", "Category", "In stock", "Reorder at", "Status", "Expiry date", "Days left", "Batch", "Supplier"));
        for (MedicationInventory m : items) {
            Integer ppp = m.getEffectivePillsPerPack();
            String stock = ppp != null && ppp > 0
                    ? m.getQuantityInStock() + " pack(s) (" + (m.getQuantityInStock() * ppp) + " pills)"
                    : m.getQuantityInStock() + " " + m.getUnit();
            String reorder = ppp != null && ppp > 0 ? m.getReorderLevel() + " pack(s)" : m.getReorderLevel() + " " + m.getUnit();
            s.add(m.getFacility() != null ? m.getFacility().getFacilityName() : "—",
                    m.getMedicationName() + (m.getMedication() != null && m.getMedication().getStrength() != null ? " " + m.getMedication().getStrength() : ""),
                    nz(m.getGenericName()), nz(m.getCategory()), stock, reorder, statusText(m),
                    m.getExpiryDate() != null ? m.getExpiryDate().format(D) : "Not recorded",
                    m.getDaysToExpiry() != null ? String.valueOf(m.getDaysToExpiry()) : "—",
                    nz(m.getBatchNumber()), nz(m.getSupplier()));
        }
        doc.add(s);
        return doc;
    }

    private String statusText(MedicationInventory m) {
        List<String> parts = new ArrayList<>();
        if (m.isOutOfStock()) parts.add("Out of stock");
        else if (m.isLowStock()) parts.add("Low stock");
        else parts.add("In stock");
        if (m.isExpired()) parts.add("EXPIRED");
        else if (m.isExpiringVerySoon()) parts.add("Expires < 3 months");
        else if (m.isExpiringSoon()) parts.add("Expires < 6 months");
        return String.join(" / ", parts);
    }

    // ================================================== FACILITY OPERATIONS

    /** facility == null means national (system admin, all facilities). */
    public ReportDocument facilityReport(Facility facility, LocalDate from, LocalDate to) {
        String scope = facility != null ? facility.getFacilityName() : "All facilities (national)";
        ReportDocument doc = new ReportDocument("Operations Report — " + scope,
                from.format(D) + " to " + to.format(D));

        Predicate<Facility> inScope = f -> facility == null || (f != null && f.getId().equals(facility.getId()));
        Predicate<LocalDate> inRange = d -> d != null && !d.isBefore(from) && !d.isAfter(to);

        List<Doctor> doctors = doctorService.findAll().stream().filter(d -> inScope.test(d.getFacility())).toList();
        Map<Long, long[]> perDoctor = new LinkedHashMap<>(); // [consultations, prescriptions, patients seen via queue]
        for (Doctor d : doctors) perDoctor.put(d.getId(), new long[3]);

        long consultations = 0;
        for (Consultation c : consultationService.findAll()) {
            Appointment a = c.getAppointment();
            Doctor d = a != null ? a.getDoctor() : null;
            if (d == null || !inScope.test(d.getFacility())) continue;
            if (c.getConsultationDate() == null || !inRange.test(c.getConsultationDate().toLocalDate())) continue;
            consultations++;
            long[] arr = perDoctor.get(d.getId());
            if (arr != null) arr[0]++;
        }

        long prescriptions = 0;
        for (Prescription p : prescriptionRepository.findAll()) {
            if (p.getDoctor() == null || !inScope.test(p.getDoctor().getFacility())) continue;
            if (!inRange.test(p.getPrescriptionDate())) continue;
            prescriptions++;
            long[] arr = perDoctor.get(p.getDoctor().getId());
            if (arr != null) arr[1]++;
        }

        List<WalkInVisit> walkIns = walkInRepository.findAll().stream()
                .filter(w -> inScope.test(w.getFacility()))
                .filter(w -> inRange.test(w.getArrivalTime().toLocalDate()))
                .toList();
        for (WalkInVisit w : walkIns) {
            if (w.getAssignedDoctor() != null && ("COMPLETED".equals(w.getStatus()) || "IN_CONSULTATION".equals(w.getStatus()))) {
                long[] arr = perDoctor.get(w.getAssignedDoctor().getId());
                if (arr != null) arr[2]++;
            }
        }

        List<Appointment> appointments = appointmentService.findAll().stream()
                .filter(a -> inScope.test(a.getFacility() != null ? a.getFacility()
                        : (a.getDoctor() != null ? a.getDoctor().getFacility() : null)))
                .filter(a -> inRange.test(a.getAppointmentDate()))
                .toList();

        long dispensed = dispensaryOrderRepository.findAll().stream()
                .filter(o -> "DISPENSED".equals(o.getStatus()) && o.getDispensedAt() != null)
                .filter(o -> inScope.test(o.getDispensingFacility()))
                .filter(o -> inRange.test(o.getDispensedAt().toLocalDate()))
                .count();

        long patients = patientService.findAll().stream()
                .filter(p -> inScope.test(p.getRegisteredFacility())).count();
        long staff = staffMemberRepository.findAll().stream().filter(s -> inScope.test(s.getFacility())).count();

        // ---- headline numbers
        ReportSection summary = new ReportSection("Summary", List.of("Measure", "Value"));
        summary.add("Registered patients", String.valueOf(patients));
        summary.add("Doctors", String.valueOf(doctors.size()));
        summary.add("Nurses, receptionists & pharmacists", String.valueOf(staff));
        summary.add("Walk-in / check-in visits in period", String.valueOf(walkIns.size()));
        summary.add("Consultations held in period", String.valueOf(consultations));
        summary.add("Prescriptions issued in period", String.valueOf(prescriptions));
        summary.add("Medication orders dispensed in period", String.valueOf(dispensed));
        summary.add("Appointments (bookings) in period", String.valueOf(appointments.size()));
        doctors.stream()
                .max(Comparator.comparingLong((Doctor d) -> perDoctor.get(d.getId())[0] + perDoctor.get(d.getId())[1]))
                .filter(d -> perDoctor.get(d.getId())[0] + perDoctor.get(d.getId())[1] > 0)
                .ifPresent(d -> summary.add("Busiest doctor", "Dr. " + d.getFullName() + " ("
                        + perDoctor.get(d.getId())[0] + " consultations, " + perDoctor.get(d.getId())[1] + " prescriptions)"));
        doc.add(summary);

        // ---- per-doctor workload, busiest first
        ReportSection docs = new ReportSection("Doctor workload (busiest first)",
                List.of("Doctor", "Code", "Specialization", "Facility", "Consultations", "Prescriptions issued", "Queue patients seen"));
        doctors.stream()
                .sorted(Comparator.comparingLong((Doctor d) -> -(perDoctor.get(d.getId())[0] + perDoctor.get(d.getId())[1])))
                .forEach(d -> {
                    long[] a = perDoctor.get(d.getId());
                    docs.add("Dr. " + d.getFullName(), PrescriptionNumberService.doctorCode(d), nz(d.getSpecialization()),
                            d.getFacility() != null ? d.getFacility().getFacilityName() : "—",
                            String.valueOf(a[0]), String.valueOf(a[1]), String.valueOf(a[2]));
                });
        doc.add(docs);

        // ---- who came for what
        Map<String, Long> byService = walkIns.stream()
                .collect(Collectors.groupingBy(w -> nz(w.getRequestedService()), TreeMap::new, Collectors.counting()));
        ReportSection svc = new ReportSection("Visits by service requested", List.of("Service / doctor type", "Visits"));
        byService.forEach((k, v) -> svc.add(k, String.valueOf(v)));
        doc.add(svc);

        Map<String, Long> byStatus = appointments.stream()
                .collect(Collectors.groupingBy(a -> nz(a.getStatus()), TreeMap::new, Collectors.counting()));
        ReportSection apt = new ReportSection("Appointments by status", List.of("Status", "Count"));
        byStatus.forEach((k, v) -> apt.add(k, String.valueOf(v)));
        doc.add(apt);

        // ---- pill collections
        long due = pillCollectionRepository.findAll().stream()
                .filter(c -> inScope.test(c.getFacility()) && inRange.test(c.getWindowStart())).count();
        long collected = pillCollectionRepository.findAll().stream()
                .filter(c -> inScope.test(c.getFacility()) && inRange.test(c.getWindowStart()) && "COLLECTED".equals(c.getStatus())).count();
        long missed = pillCollectionRepository.findAll().stream()
                .filter(c -> inScope.test(c.getFacility()) && inRange.test(c.getWindowStart()) && "EXPIRED".equals(c.getStatus())).count();
        ReportSection pills = new ReportSection("Repeat pill collections", List.of("Measure", "Count"));
        pills.add("Collections due in period", String.valueOf(due));
        pills.add("Collected", String.valueOf(collected));
        pills.add("Missed (window passed)", String.valueOf(missed));
        doc.add(pills);

        // ---- activity from the audit log (staff & patients registered at the facility)
        Set<String> users = usernamesAt(facility);
        Map<String, Long> actions = auditLogService.findAll().stream()
                .filter(l -> l.getUsername() != null && (facility == null || users.contains(l.getUsername().toLowerCase())))
                .filter(l -> l.getTimestamp() != null && inRange.test(l.getTimestamp().toLocalDate()))
                .collect(Collectors.groupingBy(l -> nz(l.getAction()), Collectors.counting()));
        ReportSection act = new ReportSection("Activity recorded in the audit log (top 15 actions)", List.of("Action", "Times"));
        actions.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(15)
                .forEach(e -> act.add(e.getKey(), String.valueOf(e.getValue())));
        doc.add(act);

        insightService.addSections(doc, facility, from, to);
        return doc;
    }

    /** Lower-case logins of everyone registered at the facility (null = nobody filter needed). */
    public Set<String> usernamesAt(Facility facility) {
        Set<String> names = new HashSet<>();
        if (facility == null) return names;
        Long fid = facility.getId();
        staffMemberRepository.findAll().stream()
                .filter(s -> s.getFacility() != null && s.getFacility().getId().equals(fid) && s.getEmail() != null)
                .forEach(s -> names.add(s.getEmail().toLowerCase()));
        doctorService.findAll().stream()
                .filter(d -> d.getFacility() != null && d.getFacility().getId().equals(fid) && d.getEmail() != null)
                .forEach(d -> names.add(d.getEmail().toLowerCase()));
        patientService.findAll().stream()
                .filter(p -> p.getRegisteredFacility() != null && p.getRegisteredFacility().getId().equals(fid) && p.getEmail() != null)
                .forEach(p -> names.add(p.getEmail().toLowerCase()));
        return names;
    }

    // ================================================== PRESCRIPTION NUMBER LOOKUP

    /** Who issued this prescription number? Limited to the given facility unless facility is null (system admin). */
    public ReportSection lookupPrescription(String number, Facility facility) {
        ReportSection s = new ReportSection("Prescription lookup: " + number,
                List.of("Prescription", "Issued", "Doctor", "Specialization", "Facility", "Patient", "Status"));
        if (number == null || number.isBlank()) return s;
        prescriptionRepository.findByPrescriptionNumber(number.trim().toUpperCase()).ifPresent(p -> {
            Facility f = p.getIssuingFacility();
            if (facility != null && (f == null || !f.getId().equals(facility.getId()))) return; // not this facility's
            s.add(p.getDisplayNumber(), p.getPrescriptionDate() != null ? p.getPrescriptionDate().format(D) : "—",
                    p.getDoctor() != null ? "Dr. " + p.getDoctor().getFullName() : "—",
                    p.getDoctor() != null ? nz(p.getDoctor().getSpecialization()) : "—",
                    f != null ? f.getFacilityName() : "—",
                    p.getPatient() != null ? p.getPatient().getFullName() : "—", nz(p.getStatus()));
        });
        return s;
    }

    private static String nz(String v) { return v == null || v.isBlank() ? "—" : v; }
}
