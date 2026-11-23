package za.co.nmas.nmas.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.repository.FacilityRepository;

import java.util.List;
import java.util.Map;

/**
 * Gives facilities an approximate GPS position (so AIDEN can find the one
 * closest to a patient) and answers "which facility is nearest?".
 * Coordinates for the seeded Mpumalanga facilities are approximate town
 * positions; they only fill in when a facility has none, so any value an
 * administrator sets later is never overwritten.
 */
@Service
@Order(105)
public class FacilityGeoService implements CommandLineRunner {

    private static final Map<String, double[]> SEED = Map.ofEntries(
            Map.entry("MPU-001", new double[]{-25.4547, 30.9707}),
            Map.entry("MPU-002", new double[]{-25.3639, 31.0894}),
            Map.entry("MPU-003", new double[]{-24.8167, 31.0500}),
            Map.entry("MPU-004", new double[]{-25.0970, 30.4560}),
            Map.entry("MPU-005", new double[]{-26.4600, 29.1100}),
            Map.entry("MPU-006", new double[]{-25.7860, 31.0510}),
            Map.entry("MPU-007", new double[]{-26.9400, 29.2400}),
            Map.entry("MPU-008", new double[]{-25.8700, 29.2330}),
            Map.entry("MPU-009", new double[]{-24.5967, 31.0364}),
            Map.entry("MPU-010", new double[]{-25.2300, 29.0500}),
            Map.entry("MPU-011", new double[]{-26.0700, 30.1130}),
            Map.entry("MPU-012", new double[]{-27.0050, 30.8120}),
            Map.entry("MPU-013", new double[]{-26.0000, 30.7830}),
            Map.entry("MPU-014", new double[]{-26.3720, 29.0410}),
            Map.entry("MPU-015", new double[]{-25.7750, 29.4640}));

    private final FacilityRepository facilityRepository;

    public FacilityGeoService(FacilityRepository facilityRepository) {
        this.facilityRepository = facilityRepository;
    }

    @Override
    public void run(String... args) {
        try {
            for (Facility f : facilityRepository.findAll()) {
                if (f.getLatitude() != null && f.getLongitude() != null) continue;
                double[] c = f.getRegistrationNumber() != null ? SEED.get(f.getRegistrationNumber()) : null;
                if (c == null) continue;
                f.setLatitude(c[0]);
                f.setLongitude(c[1]);
                facilityRepository.save(f);
            }
        } catch (Exception e) {
            System.err.println("[FacilityGeo] could not set coordinates: " + e);
        }
    }

    /** Great-circle distance in km. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static Double distanceTo(Facility f, double lat, double lng) {
        if (f == null || f.getLatitude() == null || f.getLongitude() == null) return null;
        return distanceKm(lat, lng, f.getLatitude(), f.getLongitude());
    }

    /** Nearest facility with coordinates, or null. */
    public Facility nearest(double lat, double lng) {
        Facility best = null;
        double bestD = Double.MAX_VALUE;
        List<Facility> all = facilityRepository.findAll();
        for (Facility f : all) {
            Double d = distanceTo(f, lat, lng);
            if (d != null && d < bestD) { bestD = d; best = f; }
        }
        return best;
    }

    /** Nearest hospital-type facility if any exist (emergency care), otherwise nearest of any type. */
    public Facility nearestEmergencyFacility(double lat, double lng) {
        Facility best = null;
        double bestD = Double.MAX_VALUE;
        for (Facility f : facilityRepository.findAll()) {
            String t = f.getFacilityType() == null ? "" : f.getFacilityType().toLowerCase();
            if (!t.contains("hospital")) continue;
            Double d = distanceTo(f, lat, lng);
            if (d != null && d < bestD) { bestD = d; best = f; }
        }
        return best != null ? best : nearest(lat, lng);
    }
}
