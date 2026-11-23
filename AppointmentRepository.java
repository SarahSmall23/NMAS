package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import za.co.nmas.nmas.entity.Appointment;
import za.co.nmas.nmas.entity.Patient;

import java.time.LocalDate;
import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    List<Appointment> findByPatientId(Long patientId);

    List<Appointment> findByDoctorId(Long doctorId);

    long countByStatus(String status);

    long countByDoctorId(Long doctorId);

    long countByDoctorIdAndStatus(Long doctorId, String status);

    long countByDoctorIdAndAppointmentDate(Long doctorId, LocalDate appointmentDate);

    long countByAppointmentDate(LocalDate appointmentDate);

    boolean existsByDoctorIdAndPatientIdAndStatusIn(Long doctorId, Long patientId, List<String> statuses);

    @Query("SELECT DISTINCT a.patient FROM Appointment a WHERE a.doctor.id = :doctorId")
    List<Patient> findPatientsByDoctorId(@Param("doctorId") Long doctorId);
}
