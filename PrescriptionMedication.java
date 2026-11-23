package za.co.nmas.nmas.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "prescription_medications")
public class PrescriptionMedication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "prescription_id", nullable = false)
    private Prescription prescription;

    @ManyToOne
    @JoinColumn(name = "medication_id", nullable = false)
    private Medication medication;

    private String dosage;
    private String frequency;
    private String duration;
    private String route;
    private String mealInstruction;
    private boolean morning;
    private boolean afternoon;
    private boolean evening;
    private boolean night;

    @Column(length = 500)
    private String instructions;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public PrescriptionMedication() {}

    public Long getId() { return id; }
    public Prescription getPrescription() { return prescription; }
    public void setPrescription(Prescription prescription) { this.prescription = prescription; }
    public Medication getMedication() { return medication; }
    public void setMedication(Medication medication) { this.medication = medication; }
    public String getDosage() { return dosage; }
    public void setDosage(String dosage) { this.dosage = dosage; }
    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }
    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public String getMealInstruction() { return mealInstruction; }
    public void setMealInstruction(String mealInstruction) { this.mealInstruction = mealInstruction; }
    public boolean isMorning() { return morning; }
    public void setMorning(boolean morning) { this.morning = morning; }
    public boolean isAfternoon() { return afternoon; }
    public void setAfternoon(boolean afternoon) { this.afternoon = afternoon; }
    public boolean isEvening() { return evening; }
    public void setEvening(boolean evening) { this.evening = evening; }
    public boolean isNight() { return night; }
    public void setNight(boolean night) { this.night = night; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
