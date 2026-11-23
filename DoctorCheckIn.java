package za.co.nmas.nmas.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** One on-duty session: the doctor checked in at the hospital and (later) checked out. */
@Entity
@Table(name = "doctor_check_ins")
public class DoctorCheckIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @ManyToOne
    @JoinColumn(name = "facility_id")
    private Facility facility;

    @Column(nullable = false)
    private LocalDateTime checkInTime;

    private LocalDateTime checkOutTime;

    public Long getId() { return id; }
    public Doctor getDoctor() { return doctor; }
    public void setDoctor(Doctor doctor) { this.doctor = doctor; }
    public Facility getFacility() { return facility; }
    public void setFacility(Facility facility) { this.facility = facility; }
    public LocalDateTime getCheckInTime() { return checkInTime; }
    public void setCheckInTime(LocalDateTime t) { this.checkInTime = t; }
    public LocalDateTime getCheckOutTime() { return checkOutTime; }
    public void setCheckOutTime(LocalDateTime t) { this.checkOutTime = t; }
}
