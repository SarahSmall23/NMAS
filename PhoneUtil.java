package za.co.nmas.nmas.util;

/** South African phone number helpers. Canonical form: 10 digits starting with 0 (e.g. 0812345678). */
public final class PhoneUtil {

    private PhoneUtil() {}

    /**
     * Converts a phone number to the 10-digit South African format.
     * Handles +27 / 27 / 0027 prefixes, spaces, dashes and brackets, 9-digit numbers
     * missing the leading 0, and stray extra digits (e.g. 11-digit seeded numbers).
     * Returns null for blank input. If it cannot be made valid the digits are returned as-is
     * so {@link #isValid(String)} can reject them.
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("\\D", "");
        if (d.isEmpty()) return null;

        if (d.startsWith("0027")) d = "0" + d.substring(4);
        else if (d.startsWith("27") && d.length() >= 11) d = "0" + d.substring(2);

        if (d.length() == 9 && !d.startsWith("0")) d = "0" + d;

        if (d.length() > 10 && d.startsWith("0")) {
            // Keep the 0 + network digit, then the last 8 digits (drops the surplus digit)
            d = d.substring(0, 2) + d.substring(d.length() - 8);
        }
        return d;
    }

    public static boolean isValid(String phone) {
        return phone != null && phone.matches("0\\d{9}");
    }
}
