package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.AppUser;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);
}
