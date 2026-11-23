package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.LoginActivity;

import java.time.LocalDateTime;
import java.util.List;

public interface LoginActivityRepository extends JpaRepository<LoginActivity, Long> {
    List<LoginActivity> findByLoggedInAtBetween(LocalDateTime from, LocalDateTime to);
}
