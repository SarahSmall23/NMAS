package za.co.nmas.nmas.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Small, idempotent schema relaxations that Hibernate's ddl-auto=update
 * will not do on its own for an existing database.
 */
@Component
@Order(1)
public class SchemaFixRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaFixRunner.class);
    private final JdbcTemplate jdbc;

    public SchemaFixRunner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        // Nurses can now appear in the cross-facility access log, so doctor_id is optional.
        try {
            jdbc.execute("ALTER TABLE record_access_requests ALTER COLUMN doctor_id DROP NOT NULL");
        } catch (Exception e) {
            log.debug("doctor_id relax skipped: {}", e.getMessage());
        }

        // One-off clean-up: bring every stored patient phone number to 10-digit South African format.
        try {
            for (String col : new String[]{"phone_number", "alternative_phone_number", "next_of_kin_phone", "emergency_contact_phone"}) {
                var rows = jdbc.queryForList("SELECT id, " + col + " AS v FROM patients WHERE " + col + " IS NOT NULL");
                int fixed = 0;
                for (var r : rows) {
                    String old = (String) r.get("v");
                    String neu = za.co.nmas.nmas.util.PhoneUtil.normalize(old);
                    if (neu != null && !neu.equals(old)) {
                        jdbc.update("UPDATE patients SET " + col + " = ? WHERE id = ?", neu, r.get("id"));
                        fixed++;
                    }
                }
                if (fixed > 0) log.info("Normalised {} patient {} values to SA 10-digit format", fixed, col);
            }
        } catch (Exception e) {
            log.warn("Patient phone normalisation skipped: {}", e.getMessage());
        }
    }
}
