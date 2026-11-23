package za.co.nmas.nmas.util;

import za.co.nmas.nmas.entity.Patient;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Period;

/**
 * Works out whether a patient is elderly (and so goes to the front of the queue) from the
 * first six digits of their South African ID number (YYMMDD = date of birth). If the ID
 * number is missing or not a valid date (e.g. passport patients), the stored date of birth is used.
 */
public final class PatientPriority {

    /** Patients this age or older are seen first. */
    public static final int ELDERLY_AGE = 50;

    private PatientPriority() {}

    /** Birth date read from the first six ID digits, or null if the ID doesn't carry a valid one. */
    public static LocalDate birthDateFromId(String idNumber) {
        if (idNumber == null) return null;
        String id = idNumber.trim();
        if (id.length() < 6) return null;
        String head = id.substring(0, 6);
        for (int i = 0; i < 6; i++) if (!Character.isDigit(head.charAt(i))) return null;
        int yy = Integer.parseInt(head.substring(0, 2));
        int mm = Integer.parseInt(head.substring(2, 4));
        int dd = Integer.parseInt(head.substring(4, 6));
        LocalDate today = LocalDate.now();
        try {
            LocalDate d = LocalDate.of(2000 + yy, mm, dd);
            if (d.isAfter(today)) d = LocalDate.of(1900 + yy, mm, dd); // a future date means 19YY
            return d;
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** Current age from the ID number (falling back to the date of birth); null if neither is usable. */
    public static Integer ageOf(Patient p) {
        if (p == null) return null;
        LocalDate born = birthDateFromId(p.getIdNumber());
        if (born == null) born = p.getDateOfBirth();
        if (born == null) return null;
        return Period.between(born, LocalDate.now()).getYears();
    }

    public static boolean isElderly(Patient p) {
        Integer age = ageOf(p);
        return age != null && age >= ELDERLY_AGE;
    }
}
