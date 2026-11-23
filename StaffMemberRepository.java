package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.nmas.nmas.entity.StaffMember;

import java.util.List;
import java.util.Optional;

public interface StaffMemberRepository extends JpaRepository<StaffMember, Long> {
    List<StaffMember> findByRoleIn(List<String> roles);
    Optional<StaffMember> findByEmail(String email);
    long countByRole(String role);
    List<StaffMember> findByFacilityIdAndRole(Long facilityId, String role);
}
