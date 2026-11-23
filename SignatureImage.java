package za.co.nmas.nmas.util;

import com.lowagie.text.Image;
import za.co.nmas.nmas.entity.Doctor;

import java.util.Base64;

/** Builds a PDF image from a doctor's stored digital signature (PNG data URL), or null if none / unreadable. */
public final class SignatureImage {

    private SignatureImage() {}

    public static Image of(Doctor doctor, float maxWidth, float maxHeight) {
        if (doctor == null || !doctor.hasSignature()) return null;
        try {
            String data = doctor.getSignatureData();
            int comma = data.indexOf(',');
            byte[] bytes = Base64.getDecoder().decode(data.substring(comma + 1));
            Image img = Image.getInstance(bytes);
            img.scaleToFit(maxWidth, maxHeight);
            return img;
        } catch (Exception e) {
            return null;
        }
    }
}
