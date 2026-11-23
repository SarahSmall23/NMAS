package za.co.nmas.nmas.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** One successful login: who, which role, which facility, when. Feeds the "portal activity" report. */
@Entity
@Table(name = "login_activity", indexes = {
        @Index(name = "idx_login_activity_email", columnList = "email"),
        @Index(name = "idx_login_activity_at", columnList = "loggedInAt")})
public class LoginActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;
    private String role;

    @ManyToOne
    @JoinColumn(name = "facility_id")
    private Facility facility;

    private LocalDateTime loggedInAt = LocalDateTime.now();

    public LoginActivity() {}

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Facility getFacility() { return facility; }
    public void setFacility(Facility facility) { this.facility = facility; }
    public LocalDateTime getLoggedInAt() { return loggedInAt; }
    public void setLoggedInAt(LocalDateTime loggedInAt) { this.loggedInAt = loggedInAt; }
}
