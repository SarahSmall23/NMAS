package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.*;
import za.co.nmas.nmas.report.ReportDocument;
import za.co.nmas.nmas.report.ReportSection;
import za.co.nmas.nmas.repository.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The "how is this hospital really performing?" sections appended to the
 * facility operations report: front desk, nursing, pharmacy, prescriptions,
 * follow-ups, video consultations, patient portal activity, cross-facility
 * access, plus an automatic list of problem areas to fix early.
 */
@Service
public class OperationsInsightService {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final WalkInVisitRepository walkInRepository;
    private final DispensaryOrderRepository orderRepository;
    private final MedicationInventoryRepository inventoryRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final AppointmentRepository appointmentRepository;
    private final VideoConsultationRequestRepository videoRepository;
    private final LoginActivityRepository loginRepository;
    private final PatientRepository patientRepository;
    private final StaffMemberRepository staffRepository;
    private final DoctorRepository doctorRepository;
    private final RecordAccessRequestRepository accessRepository;

    public OperationsInsightService(WalkInVisitRepository walkInRepository, DispensaryOrderRepository orderRepository,
                                    MedicationInventoryRepository inventoryRepository, PrescriptionRepository prescriptionRepository,
                                    AppointmentRepository appointmentRepository, VideoConsultationRequestRepository videoRepository,
                                    LoginActivityRepository loginRepository, PatientRepository patientRepository,
                                    StaffMemberRepository staffRepository, DoctorRepository doctorRepository,
                                    RecordAccessRequestRepository accessRepository) {
        this.walkInRepository = walkInRepository;
        this.orderRepository = orderRepository;
        this.inventoryRepository = inventoryRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.appointmentRepository = appointmentRepository;
        this.videoRepository = videoRepository;
        this.loginRepository = loginRepository;
        this.patientRepository = patientRepository;
        this.staffRepository = staffRepository;
        this.doctorRepository = doctorRepository;
        this.accessRepository = accessRepository;
    }

    public void addSections(ReportDocument doc, Facility facility, LocalDate from, LocalDate to) {
        Predicate<Facility> inScope = f -> facility == null || (f != null && f.getId().equals(facility.getId()));
        Predicate<LocalDateTime> inRange = t -> t != null && !t.toLocalDate().isBefore(from) && !t.toLocalDate().isAfter(to);
        Predicate<LocalDate> dateInRange = d -> d != null && !d.isBefore(from) && !d.isAfter(to);
        List<String> problems = new ArrayList<>();

        // ------------------------------------------------ front desk -> nurse -> doctor flow
        List<WalkInVisit> visits = walkInRepository.findAll().stream()
                .filter(w -> inScope.test(w.getFacility()) && inRange.test(w.getArrivalTime())).toList();
        long walk = visits.stream().filter(v -> !"APPOINTMENT".equals(v.getVisitType())).count();
        long appt = visits.size() - walk;
        long cancelled = visits.stream().filter(v -> "CANCELLED".equals(v.getStatus())).count();
        long completed = visits.stream().filter(v -> "COMPLETED".equals(v.getStatus())).count();
        double toVitals = avgMinutes(visits, WalkInVisit::getArrivalTime, WalkInVisit::getVitalsRecordedAt);
        double toDoctor = avgMinutes(visits, WalkInVisit::getVitalsRecordedAt, WalkInVisit::getConsultationStartedAt);
        double total = avgMinutes(visits, WalkInVisit::getArrivalTime, WalkInVisit::getConsultationStartedAt);

        ReportSection flow = new ReportSection("Patient flow: reception > nurse > doctor", List.of("Measure", "Value"));
        flow.add("Patients checked in at reception", String.valueOf(visits.size()));
        flow.add("  - walk-ins", String.valueOf(walk));
        flow.add("  - booked follow-ups (priority)", String.valueOf(appt));
        flow.add("Completed visits", String.valueOf(completed));
        flow.add("Cancelled check-ins", String.valueOf(cancelled));
        flow.add("Average wait: reception to nurse vitals", fmt(toVitals));
        flow.add("Average wait: vitals to doctor", fmt(toDoctor));
        flow.add("Average total time: arrival to doctor", fmt(total));
        doc.add(flow);
        if (!Double.isNaN(toVitals) && toVitals > 30) problems.add("Patients wait on average " + Math.round(toVitals) + " min for the nurse - consider more nursing capacity at peak times.");
        if (!Double.isNaN(toDoctor) && toDoctor > 45) problems.add("Patients wait on average " + Math.round(toDoctor) + " min between vitals and the doctor - doctors may be overloaded.");

        Map<LocalDate, Long> perDay = visits.stream().collect(Collectors.groupingBy(v -> v.getArrivalTime().toLocalDate(), TreeMap::new, Collectors.counting()));
        ReportSection daily = new ReportSection("Daily check-ins", List.of("Date", "Patients"));
        perDay.forEach((d, c) -> daily.add(d.format(D), String.valueOf(c)));
        doc.add(daily);

        Map<String, long[]> byReceptionist = new TreeMap<>();
        Map<String, long[]> byNurse = new TreeMap<>();
        for (WalkInVisit v : visits) {
            if (v.getRegisteredBy() != null) byReceptionist.computeIfAbsent(v.getRegisteredBy(), k -> new long[1])[0]++;
            if (v.getVitalsRecordedBy() != null) byNurse.computeIfAbsent(v.getVitalsRecordedBy(), k -> new long[1])[0]++;
        }
        ReportSection staffWork = new ReportSection("Front-desk and nursing activity by staff member", List.of("Staff", "Role", "Patients handled"));
        byReceptionist.forEach((k, v) -> staffWork.add(k, "Receptionist", String.valueOf(v[0])));
        byNurse.forEach((k, v) -> staffWork.add(k, "Nurse", String.valueOf(v[0])));
        doc.add(staffWork);

        // stuck right now
        LocalDateTime now = LocalDateTime.now();
        long stuckVitals = walkInRepository.findAll().stream().filter(w -> inScope.test(w.getFacility())
                && "WAITING_VITALS".equals(w.getStatus()) && w.getArrivalTime() != null
                && Duration.between(w.getArrivalTime(), now).toMinutes() > 60).count();
        if (stuckVitals > 0) problems.add(stuckVitals + " patient(s) have been waiting more than 60 minutes for vitals right now.");

        // ------------------------------------------------ pharmacy
        List<DispensaryOrder> orders = orderRepository.findAll().stream()
                .filter(o -> {
                    Long fid = DispensaryService.orderFacilityId(o);
                    return facility == null || (fid != null && fid.equals(facility.getId()));
                })
                .filter(o -> inRange.test(o.getCreatedAt())).toList();
        Map<String, Long> byStatus = orders.stream().collect(Collectors.groupingBy(o -> o.getStatus() == null ? "-" : o.getStatus(), TreeMap::new, Collectors.counting()));
        double rxToDispense = avgMinutes(orders.stream().filter(o -> "DISPENSED".equals(o.getStatus())).toList(),
                DispensaryOrder::getCreatedAt, DispensaryOrder::getDispensedAt);
        ReportSection pharm = new ReportSection("Pharmacy: orders", List.of("Measure", "Value"));
        pharm.add("Dispensary orders created", String.valueOf(orders.size()));
        byStatus.forEach((k, v) -> pharm.add("  - " + k, String.valueOf(v)));
        pharm.add("Average time prescription to dispensed", fmt(rxToDispense));
        doc.add(pharm);
        long pendingOld = orders.stream().filter(o -> "PENDING".equals(o.getStatus()) && o.getCreatedAt() != null
                && Duration.between(o.getCreatedAt(), now).toHours() >= 24).count();
        if (pendingOld > 0) problems.add(pendingOld + " dispensary order(s) have been pending for over 24 hours.");

        Map<String, Long> topMeds = orders.stream().filter(o -> "DISPENSED".equals(o.getStatus()))
                .collect(Collectors.groupingBy(o -> o.getMedicationName() == null ? "-" : o.getMedicationName(), Collectors.counting()));
        ReportSection top = new ReportSection("Most dispensed medication (top 10)", List.of("Medication", "Times dispensed"));
        topMeds.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10)
                .forEach(e -> top.add(e.getKey(), String.valueOf(e.getValue())));
        doc.add(top);

        List<MedicationInventory> shelf = inventoryRepository.findAll().stream()
                .filter(m -> inScope.test(m.getFacility())).toList();
        long low = shelf.stream().filter(MedicationInventory::isLowStock).count();
        long out = shelf.stream().filter(MedicationInventory::isOutOfStock).count();
        long expiring = shelf.stream().filter(MedicationInventory::isExpiringSoon).count();
        long expired = shelf.stream().filter(MedicationInventory::isExpired).count();
        ReportSection stock = new ReportSection("Pharmacy stock health (right now)", List.of("Measure", "Items"));
        stock.add("Medicines on the shelf", String.valueOf(shelf.size()));
        stock.add("Low stock", String.valueOf(low));
        stock.add("Out of stock", String.valueOf(out));
        stock.add("Expiring within 6 months", String.valueOf(expiring));
        stock.add("Expired", String.valueOf(expired));
        doc.add(stock);
        if (out > 0) problems.add(out + " medicine(s) are out of stock - restock now.");
        if (low > 0) problems.add(low + " medicine(s) are running low.");
        if (expired > 0) problems.add(expired + " expired medicine(s) are still on the shelf - remove them.");

        // ------------------------------------------------ prescriptions
        List<Prescription> rxs = prescriptionRepository.findAll().stream()
                .filter(p -> p.getDoctor() != null && inScope.test(p.getDoctor().getFacility()) && dateInRange.test(p.getPrescriptionDate())).toList();
        long expiredRx = rxs.stream().filter(Prescription::isExpired).count();
        Set<Long> dispensedRx = orders.stream().filter(o -> "DISPENSED".equals(o.getStatus()) && o.getPrescription() != null)
                .map(o -> o.getPrescription().getId()).collect(Collectors.toSet());
        long expiredUncollected = rxs.stream().filter(p -> p.isExpired() && !dispensedRx.contains(p.getId())).count();
        ReportSection rx = new ReportSection("Prescriptions", List.of("Measure", "Count"));
        rx.add("Prescriptions issued", String.valueOf(rxs.size()));
        rx.add("Collected / dispensed (at least one item)", String.valueOf(rxs.stream().filter(p -> dispensedRx.contains(p.getId())).count()));
        rx.add("Past validity date", String.valueOf(expiredRx));
        rx.add("Expired without being collected", String.valueOf(expiredUncollected));
        doc.add(rx);
        if (expiredUncollected > 0) problems.add(expiredUncollected + " prescription(s) expired uncollected - consider following up with those patients.");

        // ------------------------------------------------ follow-ups
        List<Appointment> followUps = appointmentRepository.findAll().stream()
                .filter(Appointment::isFollowUp)
                .filter(a -> inScope.test(a.getFacility() != null ? a.getFacility() : (a.getDoctor() != null ? a.getDoctor().getFacility() : null)))
                .filter(a -> dateInRange.test(a.getAppointmentDate())).toList();
        Set<Long> attended = visits.stream().filter(v -> v.getAppointment() != null).map(v -> v.getAppointment().getId()).collect(Collectors.toSet());
        long missedFollowUps = followUps.stream().filter(a -> a.getAppointmentDate().isBefore(LocalDate.now()) && !attended.contains(a.getId())).count();
        ReportSection fu = new ReportSection("Follow-up bookings", List.of("Measure", "Count"));
        fu.add("Booked by doctors", String.valueOf(followUps.size()));
        fu.add("Confirmed by the patient", String.valueOf(followUps.stream().filter(Appointment::isPatientConfirmed).count()));
        fu.add("Patient arrived (checked in)", String.valueOf(followUps.stream().filter(a -> attended.contains(a.getId())).count()));
        fu.add("Missed (date passed, no check-in)", String.valueOf(missedFollowUps));
        doc.add(fu);
        if (missedFollowUps > 0) problems.add(missedFollowUps + " follow-up appointment(s) were missed - patients may need a reminder call.");

        // ------------------------------------------------ video consultations
        List<VideoConsultationRequest> vids = videoRepository.findAll().stream()
                .filter(v -> v.getDoctor() != null && inScope.test(v.getDoctor().getFacility()) && inRange.test(v.getRequestedAt())).toList();
        Map<String, Long> vidStatus = vids.stream().collect(Collectors.groupingBy(v -> v.getStatus() == null ? "-" : v.getStatus(), TreeMap::new, Collectors.counting()));
        ReportSection vs = new ReportSection("Video consultations", List.of("Status", "Requests"));
        vs.add("Total requests", String.valueOf(vids.size()));
        vidStatus.forEach((k, v) -> vs.add(k, String.valueOf(v)));
        doc.add(vs);

        // ------------------------------------------------ patient portal activity
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        List<LoginActivity> logins = loginRepository.findByLoggedInAtBetween(start, end).stream()
                .filter(l -> inScope.test(l.getFacility()) || (facility != null && "PATIENT".equals(l.getRole()) && patientAt(l.getEmail(), facility))).toList();
        Map<String, Long> loginsByRole = logins.stream().collect(Collectors.groupingBy(l -> l.getRole() == null ? "-" : l.getRole(), TreeMap::new, Collectors.counting()));
        ReportSection lg = new ReportSection("Logins by role", List.of("Role", "Logins in period"));
        loginsByRole.forEach((k, v) -> lg.add(k, String.valueOf(v)));
        if (loginsByRole.isEmpty()) lg.add("No logins recorded yet", "0");
        doc.add(lg);

        List<Patient> facilityPatients = patientRepository.findAll().stream().filter(p -> inScope.test(p.getRegisteredFacility())).toList();
        Map<String, List<LoginActivity>> patientLogins = logins.stream().filter(l -> "PATIENT".equals(l.getRole()))
                .collect(Collectors.groupingBy(l -> l.getEmail().toLowerCase()));
        long activePatients = facilityPatients.stream().filter(p -> p.getEmail() != null && patientLogins.containsKey(p.getEmail().toLowerCase())).count();
        ReportSection pa = new ReportSection("Patient portal activity", List.of("Measure", "Value"));
        pa.add("Registered patients", String.valueOf(facilityPatients.size()));
        pa.add("Patients who logged in during the period", String.valueOf(activePatients));
        pa.add("Patients who did not log in", String.valueOf(facilityPatients.size() - activePatients));
        pa.add("Patient logins (total)", String.valueOf(patientLogins.values().stream().mapToInt(List::size).sum()));
        double rate = facilityPatients.isEmpty() ? 0 : 100.0 * activePatients / facilityPatients.size();
        pa.add("Portal adoption", String.format("%.1f%%", rate));
        doc.add(pa);

        ReportSection mostActive = new ReportSection("Most active patients (top 10)", List.of("Patient", "Logins", "Last login"));
        Map<String, Patient> byEmail = new HashMap<>();
        facilityPatients.forEach(p -> { if (p.getEmail() != null) byEmail.put(p.getEmail().toLowerCase(), p); });
        patientLogins.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size())).limit(10)
                .forEach(e -> {
                    Patient p = byEmail.get(e.getKey());
                    LocalDateTime last = e.getValue().stream().map(LoginActivity::getLoggedInAt).max(Comparator.naturalOrder()).orElse(null);
                    mostActive.add(p != null ? p.getFullName() : e.getKey(), String.valueOf(e.getValue().size()),
                            last != null ? last.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")) : "-");
                });
        doc.add(mostActive);

        Map<Integer, Long> byHour = logins.stream().filter(l -> l.getLoggedInAt() != null)
                .collect(Collectors.groupingBy(l -> l.getLoggedInAt().getHour(), TreeMap::new, Collectors.counting()));
        ReportSection hours = new ReportSection("When people log in (by hour of day)", List.of("Hour", "Logins"));
        byHour.forEach((h, c) -> hours.add(String.format("%02d:00", h), String.valueOf(c)));
        if (byHour.isEmpty()) hours.add("-", "0");
        doc.add(hours);

        // ------------------------------------------------ cross-facility access
        List<RecordAccessRequest> access = accessRepository.findAll();
        long viewedHere = access.stream().filter(r -> facility == null || (r.getHomeFacility() != null && r.getHomeFacility().getId().equals(facility.getId())))
                .filter(r -> inRange.test(r.getRequestedAt())).count();
        long viewedElsewhere = access.stream().filter(r -> facility == null || (r.getRequestingFacility() != null && r.getRequestingFacility().getId().equals(facility.getId())))
                .filter(r -> inRange.test(r.getRequestedAt())).count();
        ReportSection ca = new ReportSection("Cross-facility record access", List.of("Measure", "Count"));
        ca.add("Our patients' records viewed by staff of other facilities", String.valueOf(viewedHere));
        ca.add("Records of other facilities' patients viewed by our staff", String.valueOf(viewedElsewhere));
        doc.add(ca);

        // ------------------------------------------------ problems
        ReportSection prob = new ReportSection("Problem areas to fix early (automatic)", List.of("#", "Finding"));
        if (problems.isEmpty()) prob.add("-", "No problem areas detected for this period.");
        for (int i = 0; i < problems.size(); i++) prob.add(String.valueOf(i + 1), problems.get(i));
        doc.add(prob);
    }

    private boolean patientAt(String email, Facility facility) {
        if (email == null) return false;
        return patientRepository.findByEmail(email).map(p -> p.getRegisteredFacility() != null
                && p.getRegisteredFacility().getId().equals(facility.getId())).orElse(false);
    }

    private <T> double avgMinutes(List<T> list, java.util.function.Function<T, LocalDateTime> a, java.util.function.Function<T, LocalDateTime> b) {
        double sum = 0; int n = 0;
        for (T t : list) {
            LocalDateTime x = a.apply(t), y = b.apply(t);
            if (x == null || y == null || y.isBefore(x)) continue;
            sum += Duration.between(x, y).toMinutes(); n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private String fmt(double minutes) {
        if (Double.isNaN(minutes)) return "no data yet";
        return Math.round(minutes) + " min";
    }
}
