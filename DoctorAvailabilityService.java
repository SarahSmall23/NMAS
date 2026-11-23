package za.co.nmas.nmas.service;

import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Appointment;
import za.co.nmas.nmas.entity.AppointmentSlot;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.DoctorAvailability;
import za.co.nmas.nmas.repository.AppointmentRepository;
import za.co.nmas.nmas.repository.AppointmentSlotRepository;
import za.co.nmas.nmas.repository.DoctorAvailabilityRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class DoctorAvailabilityService {

    private final DoctorAvailabilityRepository availabilityRepository;
    private final AppointmentSlotRepository slotRepository;
    private final AppointmentRepository appointmentRepository;

    public DoctorAvailabilityService(DoctorAvailabilityRepository availabilityRepository,
                                     AppointmentSlotRepository slotRepository,
                                     AppointmentRepository appointmentRepository) {
        this.availabilityRepository = availabilityRepository;
        this.slotRepository = slotRepository;
        this.appointmentRepository = appointmentRepository;
    }

    @org.springframework.transaction.annotation.Transactional
    public DoctorAvailability createAvailability(Doctor doctor, LocalDate date,
                                                  LocalTime shiftStart, LocalTime shiftEnd) {
        DoctorAvailability availability = new DoctorAvailability();
        availability.setDoctor(doctor);
        availability.setAvailableDate(date);
        availability.setShiftStartTime(shiftStart);
        availability.setShiftEndTime(shiftEnd);
        availabilityRepository.save(availability);

        List<AppointmentSlot> slots = new ArrayList<>();
        // Minute arithmetic (LocalTime.plusMinutes wraps past midnight and caused an infinite loop).
        int startMin = shiftStart.toSecondOfDay() / 60;
        int endMin = shiftEnd.toSecondOfDay() / 60;
        for (int m = startMin; m + 30 <= endMin; m += 30) {
            LocalTime start = LocalTime.ofSecondOfDay(m * 60L);
            AppointmentSlot slot = new AppointmentSlot();
            slot.setDoctor(doctor);
            slot.setAvailability(availability);
            slot.setSlotDate(date);
            slot.setStartTime(start);
            slot.setEndTime(start.plusMinutes(30));
            slot.setStatus("AVAILABLE");
            slots.add(slot);
        }
        slotRepository.saveAll(slots);

        return availability;
    }

    public List<DoctorAvailability> findByDoctor(Long doctorId) {
        return availabilityRepository.findByDoctorIdOrderByAvailableDateAsc(doctorId);
    }

    public List<AppointmentSlot> getSlotsForDoctor(Long doctorId, LocalDate date) {
        return slotRepository.findByDoctorIdAndSlotDateOrderByStartTimeAsc(doctorId, date);
    }

    public Optional<AppointmentSlot> getNextAvailableSlot(Long doctorId, LocalDate date) {
        return slotRepository.findFirstByDoctorIdAndSlotDateAndStatusOrderByStartTimeAsc(
                doctorId, date, "AVAILABLE");
    }

    /**
     * What a patient sees before booking: either the doctor's confirmed
     * schedule for the exact date they picked (with real slot availability),
     * or — if nothing's been set for that specific date — the doctor's
     * usual working pattern inferred from all their other availability
     * records, so the patient at least knows roughly when this doctor
     * tends to be in.
     */
    /** True when the doctor has an active shift today that covers the current time (so a video request can be accepted). */
    public boolean isOnShiftNow(Long doctorId) {
        LocalDate today = LocalDate.now();
        java.time.LocalTime now = java.time.LocalTime.now();
        for (DoctorAvailability a : availabilityRepository.findByDoctorIdOrderByAvailableDateAsc(doctorId)) {
            if (!a.isActive() || !today.equals(a.getAvailableDate())) continue;
            if (a.getShiftStartTime() != null && a.getShiftEndTime() != null
                    && !now.isBefore(a.getShiftStartTime()) && now.isBefore(a.getShiftEndTime())) return true;
        }
        return false;
    }

    public java.util.Map<String, Object> getScheduleSummary(Long doctorId, LocalDate requestedDate) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        java.time.LocalTime nowTime = java.time.LocalTime.now();

        // Newest information first: if a date somehow has more than one
        // availability record, the most recently created/updated one wins.
        List<DoctorAvailability> all = availabilityRepository.findByDoctorIdOrderByAvailableDateAsc(doctorId);
        java.util.Map<LocalDate, DoctorAvailability> latestPerDate = new java.util.TreeMap<>();
        for (DoctorAvailability a : all) {
            if (!a.isActive() || a.getAvailableDate() == null) continue;
            DoctorAvailability cur = latestPerDate.get(a.getAvailableDate());
            if (cur == null || (a.getUpdatedAt() != null && cur.getUpdatedAt() != null
                    && a.getUpdatedAt().isAfter(cur.getUpdatedAt()))) {
                latestPerDate.put(a.getAvailableDate(), a);
            }
        }

        // Upcoming days (today onward) that still have open slots, soonest first.
        List<java.util.Map<String, Object>> upcoming = new java.util.ArrayList<>();
        for (java.util.Map.Entry<LocalDate, DoctorAvailability> e : latestPerDate.entrySet()) {
            if (e.getKey().isBefore(today)) continue;
            List<AppointmentSlot> slots = slotRepository.findByDoctorIdAndSlotDateOrderByStartTimeAsc(doctorId, e.getKey());
            List<AppointmentSlot> open = slots.stream()
                    .filter(sl -> "AVAILABLE".equals(sl.getStatus()))
                    .filter(sl -> !e.getKey().equals(today) || sl.getStartTime().isAfter(nowTime))
                    .toList();
            if (open.isEmpty()) continue;
            java.util.Map<String, Object> day = new java.util.LinkedHashMap<>();
            day.put("date", e.getKey().toString());
            day.put("dayName", e.getKey().getDayOfWeek().toString().charAt(0)
                    + e.getKey().getDayOfWeek().toString().substring(1).toLowerCase());
            day.put("startTime", e.getValue().getShiftStartTime().toString());
            day.put("endTime", e.getValue().getShiftEndTime().toString());
            day.put("openSlots", open.size());
            day.put("firstOpenTime", open.get(0).getStartTime().toString());
            upcoming.add(day);
            if (upcoming.size() >= 5) break;
        }
        result.put("upcoming", upcoming);
        result.put("nextAvailable", upcoming.isEmpty() ? null : upcoming.get(0));

        if (requestedDate != null && latestPerDate.containsKey(requestedDate)) {
            DoctorAvailability av = latestPerDate.get(requestedDate);
            List<AppointmentSlot> slots = slotRepository.findByDoctorIdAndSlotDateOrderByStartTimeAsc(doctorId, requestedDate);
            List<AppointmentSlot> open = slots.stream()
                    .filter(sl -> "AVAILABLE".equals(sl.getStatus()))
                    .filter(sl -> !requestedDate.equals(today) || sl.getStartTime().isAfter(nowTime))
                    .toList();
            result.put("hasExactSchedule", true);
            result.put("date", requestedDate.toString());
            result.put("startTime", av.getShiftStartTime().toString());
            result.put("endTime", av.getShiftEndTime().toString());
            result.put("totalSlots", slots.size());
            result.put("availableSlots", open.size());
            result.put("nextAvailableTime", open.isEmpty() ? null : open.get(0).getStartTime().toString());
            return result;
        }
        result.put("hasExactSchedule", false);

        // Usual pattern, based only on recent and upcoming schedules (not old history).
        List<DoctorAvailability> recent = latestPerDate.values().stream()
                .filter(a -> !a.getAvailableDate().isBefore(today.minusDays(60))).toList();
        if (recent.isEmpty()) {
            result.put("hasPattern", false);
            return result;
        }
        java.util.Map<java.time.DayOfWeek, Long> dayCounts = recent.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        a -> a.getAvailableDate().getDayOfWeek(), java.util.stream.Collectors.counting()));
        java.util.Map<String, Long> timeCounts = recent.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        a -> a.getShiftStartTime() + "-" + a.getShiftEndTime(), java.util.stream.Collectors.counting()));
        List<String> commonDays = dayCounts.entrySet().stream()
                .sorted((x, y) -> y.getValue().compareTo(x.getValue()))
                .map(e -> { String d = e.getKey().toString(); return d.charAt(0) + d.substring(1).toLowerCase(); })
                .collect(java.util.stream.Collectors.toList());
        String commonTimes = timeCounts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey).orElse(null);
        result.put("hasPattern", true);
        result.put("commonDays", commonDays);
        result.put("commonTimes", commonTimes);
        return result;
    }

    public void bookSlot(Long slotId, Long appointmentId) {
        AppointmentSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new RuntimeException("Slot not found"));
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new RuntimeException("Appointment not found"));

        slot.setStatus("BOOKED");
        slot.setAppointment(appointment);
        slotRepository.save(slot);

        appointment.setAppointmentTime(slot.getStartTime());
        appointmentRepository.save(appointment);
    }

    public void cancelSlot(Long slotId) {
        AppointmentSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new RuntimeException("Slot not found"));
        slot.setStatus("AVAILABLE");
        slot.setAppointment(null);
        slotRepository.save(slot);
    }

    public DoctorAvailability findById(Long id) {
        return availabilityRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Availability not found"));
    }

    public List<AppointmentSlot> getSlotsByAvailabilityId(Long availabilityId) {
        return slotRepository.findByAvailabilityId(availabilityId);
    }
}
