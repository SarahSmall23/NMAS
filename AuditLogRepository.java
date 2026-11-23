package za.co.nmas.nmas.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import za.co.nmas.nmas.entity.AuditLog;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findAllByOrderByTimestampDesc();

    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:action IS NULL OR a.action = :action) AND " +
           "(:role IS NULL OR a.role = :role) AND " +
           "(:search IS NULL OR LOWER(a.username) LIKE :searchPat OR LOWER(a.details) LIKE :searchPat) " +
           "ORDER BY a.timestamp DESC")
    List<AuditLog> findFiltered(@Param("action") String action,
                                 @Param("role") String role,
                                 @Param("search") String search,
                                 @Param("searchPat") String searchPat);

    long countByTimestampAfter(LocalDateTime after);

    @Query("SELECT DISTINCT a.action FROM AuditLog a ORDER BY a.action")
    List<String> findDistinctActions();

    @Query("SELECT DISTINCT a.role FROM AuditLog a ORDER BY a.role")
    List<String> findDistinctRoles();

    long countByAction(String action);
}
