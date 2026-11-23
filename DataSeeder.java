package za.co.nmas.nmas.config;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import za.co.nmas.nmas.entity.*;
import za.co.nmas.nmas.repository.*;

import java.awt.Color;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Component
@org.springframework.core.annotation.Order(0)
public class DataSeeder implements CommandLineRunner {

    /** Login of the national system administrator. Set nmas.system-admin.email to change it. */
    @org.springframework.beans.factory.annotation.Value("${nmas.system-admin.email:admin@nmas.gov.za}")
    private String systemAdminEmail;

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final PatientRepository patientRepository;
    private final MedicationRepository medicationRepository;
    private final MedicationInventoryRepository medicationInventoryRepository;
    private final DispensaryOrderRepository dispensaryOrderRepository;
    /** Facility whose shelf is currently being stocked by seedInv(). */
    private Facility currentFacility;
    private final DoctorRepository doctorRepository;
    private final FacilityRepository facilityRepository;
    private final StaffMemberRepository staffMemberRepository;

    private final List<String[]> pdfRows = new ArrayList<>();

    public DataSeeder(AppUserRepository appUserRepository,
                      PasswordEncoder passwordEncoder,
                      PatientRepository patientRepository,
                      MedicationRepository medicationRepository,
                      MedicationInventoryRepository medicationInventoryRepository,
                      DispensaryOrderRepository dispensaryOrderRepository,
                      DoctorRepository doctorRepository,
                      FacilityRepository facilityRepository,
                      StaffMemberRepository staffMemberRepository) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.patientRepository = patientRepository;
        this.medicationRepository = medicationRepository;
        this.medicationInventoryRepository = medicationInventoryRepository;
        this.dispensaryOrderRepository = dispensaryOrderRepository;
        this.doctorRepository = doctorRepository;
        this.facilityRepository = facilityRepository;
        this.staffMemberRepository = staffMemberRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        seedMedications();
        backfillPatientRegisteredFacility(); // Link older patient rows to a real Facility for record-access checks
        seedUser(systemAdminEmail,"NMAS@Admin2024!","SYSTEM_ADMIN");
        pdfRows.add(new String[]{"SYSTEM_ADMIN","All Facilities","System Administrator",systemAdminEmail,"NMAS@Admin2024!"});

        List<Facility> facilities = seedMpumalangaFacilities();
        int idx = 0;
        for (Facility f : facilities) {
            seedFacilityAdmin(f, idx);
            seedFacilityDoctors(f, idx);
            seedFacilityNurses(f, idx);
            seedFacilityPharmacist(f, idx);
            seedFacilityReceptionists(f, idx);
            seedFacilityPatients(f, idx);
            idx++;
        }
        // Stock is per facility: give any old shared stock to the facilities, then make sure
        // every facility has a full shelf of its own.
        migrateLegacyInventory();
        seedInventoryForAllFacilities();
        fixExistingInventoryUnits(); // Fix old rows that stored pills as Tablets/Capsules
        backfillInventoryExpiry();
        generateLoginPdf();
        System.out.println("NMAS Seeder complete. Full login list: " + System.getProperty("user.home") + "/nmas-logins.pdf");
    }

    private List<Facility> seedMpumalangaFacilities() {
        String[][] facs = {
            {"MPU-001","Rob Ferreira Hospital","Hospital","Mbombela","Kabokweni Road, Mbombela","0137412012"},
            {"MPU-002","Themba Hospital","Hospital","Kabokweni","Hospital Road, Kabokweni","0137962000"},
            {"MPU-003","Mapulaneng Hospital","Hospital","Bushbuckridge","Rooiboklaagte, Bushbuckridge","0137992000"},
            {"MPU-004","Lydenburg Hospital","Hospital","Lydenburg","Munnik Street, Lydenburg","0132350025"},
            {"MPU-005","Evander Hospital","Hospital","Evander","Krag Street, Evander","0176321000"},
            {"MPU-006","Barberton Hospital","Hospital","Barberton","Joubert Street, Barberton","0137129050"},
            {"MPU-007","Standerton Hospital","Hospital","Standerton","Joubert Street, Standerton","0175301000"},
            {"MPU-008","Witbank Hospital","Hospital","eMalahleni","Hospital Street, eMalahleni","0132345000"},
            {"MPU-009","Tintswalo Hospital","Hospital","Acornhoek","Hospital Road, Acornhoek","0137953000"},
            {"MPU-010","Mmametlhake Hospital","Hospital","Mmametlhake","Mmametlhake Village","0132607000"},
            {"MPU-011","Carolina CHC","Community Health Centre","Carolina","Joubert Street, Carolina","0177183000"},
            {"MPU-012","Piet Retief Hospital","Hospital","Mkhondo","Church Street, Piet Retief","0178261000"},
            {"MPU-013","Embhuleni Hospital","Hospital","Eerstehoek","Eerstehoek Road","0137969000"},
            {"MPU-014","Leandra CHC","Community Health Centre","Leandra","Leandra Township","0176434000"},
            {"MPU-015","Nkangala District Hospital","Hospital","Middelburg","Joubert Street, Middelburg","0132825000"},
        };
        List<Facility> result = new ArrayList<>();
        for (String[] f : facs) result.add(seedFacility(f[0],f[1],f[2],f[3],f[4],f[5]));
        return result;
    }

    private Facility seedFacility(String reg,String name,String type,String city,String address,String phone){
        return facilityRepository.findByRegistrationNumber(reg).orElseGet(()->{
            Facility f=new Facility();
            f.setRegistrationNumber(reg); f.setFacilityName(name);
            f.setFacilityType(type); f.setProvince("Mpumalanga");
            f.setCity(city); f.setAddress(address); f.setPhoneNumber(phone);
            return facilityRepository.save(f);
        });
    }

    private void seedFacilityAdmin(Facility f, int idx){
        String tag=slug(f.getFacilityName());
        String email="admin."+tag+"@nmas.gov.za";
        String pass="Admin@"+String.format("%04d",idx+1);
        if(seedUser(email,pass,"FACILITY_ADMIN")){
            StaffMember sm=new StaffMember();
            sm.setFullName("Admin "+f.getFacilityName()); sm.setEmail(email);
            sm.setRole("FACILITY_ADMIN"); sm.setFacility(f);
            sm.setEmployeeNumber("ADM-"+String.format("%04d",idx+1));
            sm.setDepartment("Administration");
            staffMemberRepository.save(sm);
        }
        // Listed every run (not only when first created) so the login PDF is always complete.
        pdfRows.add(new String[]{"FACILITY_ADMIN",f.getFacilityName(),"Admin "+f.getFacilityName(),email,pass});
    }

    private static final String[][] DR = {
        {"Thabo","Nkosi","Male","General Practitioner","MBChB","082100"},
        {"Nomsa","Sithole","Female","Internal Medicine","MBChB, FCP(SA)","082200"},
        {"Johan","Botha","Male","Cardiology","MBChB, FCP(SA) Cardiology","082300"},
        {"Zanele","Mahlangu","Female","Pediatrics","MBChB, FCPaed(SA)","082400"},
        {"Sipho","Dlamini","Male","Emergency Medicine","MBChB, FCEM(SA)","082500"},
        {"Lerato","Khumalo","Female","Neurology","MBChB, FCN(SA) Neurology","082600"},
        {"Ahmed","Patel","Male","Dermatology","MBChB, FCDerm(SA)","082700"},
    };

    private void seedFacilityDoctors(Facility f, int fIdx){
        String tag=slug(f.getFacilityName());
        for(int i=0;i<DR.length;i++){
            String fn=DR[i][0], ln=DR[i][1], gender=DR[i][2], spec=DR[i][3], qual=DR[i][4];
            String email=fn.toLowerCase()+"."+ln.toLowerCase()+"."+tag+"@nmas.gov.za";
            String pass="Doctor@"+String.format("%04d",fIdx*5+i+1);
            String phone=DR[i][5]+String.format("%04d",fIdx*10+i);
            String hpcsa="MP"+String.format("%07d",fIdx*10+i+1);
            if(doctorRepository.findByEmail(email).isEmpty()){
                Doctor d=new Doctor();
                d.setFullName(fn+" "+ln); d.setGender(gender); d.setEmail(email);
                d.setPhoneNumber(phone); d.setSpecialization(spec);
                d.setQualification(qual); d.setHpcsaNumber(hpcsa); d.setFacility(f);
                doctorRepository.save(d);
                seedUser(email,pass,"DOCTOR");
            }
            pdfRows.add(new String[]{"DOCTOR",f.getFacilityName(),fn+" "+ln+" ("+spec+")",email,pass});
        }
    }

    private static final String[][] NR = {
        {"Precious","Mokoena","Female","General Nursing","08310"},
        {"Gcinile","Zwane","Female","General Nursing","08320"},
        {"Lindiwe","Khumalo","Female","General Nursing","08330"},
        {"Bongani","Nkosi","Male","Emergency Nursing","08340"},
    };

    private void seedFacilityNurses(Facility f, int fIdx){
        String tag=slug(f.getFacilityName());
        for(int i=0;i<NR.length;i++){
            String fn=NR[i][0], ln=NR[i][1];
            String email=fn.toLowerCase()+"."+ln.toLowerCase()+"."+tag+"@nmas.gov.za";
            String pass="Nurse@"+String.format("%04d",fIdx*4+i+1);
            String phone=NR[i][4]+String.format("%05d",fIdx*10+i); // 5+5 = 10 digits
            if(staffMemberRepository.findByEmail(email).isEmpty()){
                StaffMember sm=new StaffMember();
                sm.setFullName(fn+" "+ln); sm.setGender(NR[i][2]);
                sm.setEmail(email); sm.setPhoneNumber(phone);
                sm.setRole("NURSE"); sm.setQualification(NR[i][3]);
                sm.setDepartment("Nursing"); sm.setFacility(f);
                sm.setEmployeeNumber("NRS-"+String.format("%05d",fIdx*10+i+1));
                staffMemberRepository.save(sm);
                seedUser(email,pass,"NURSE");
            }
            pdfRows.add(new String[]{"NURSE",f.getFacilityName(),fn+" "+ln,email,pass});
        }
    }

    private static final String[][] RC = {
        {"Nokuthula","Mabena","Female","08410"},
        {"Sibusiso","Tshabalala","Male","08420"},
    };

    /** Two receptionists for every facility (this is who registers patients and records walk-in visits). */
    private void seedFacilityReceptionists(Facility f, int fIdx){
        String tag=slug(f.getFacilityName());
        for(int i=0;i<RC.length;i++){
            String fn=RC[i][0], ln=RC[i][1];
            String email="reception"+(i+1)+"."+tag+"@nmas.gov.za";
            String pass="Recep@"+String.format("%04d",fIdx*RC.length+i+1);
            if(staffMemberRepository.findByEmail(email).isEmpty()&&appUserRepository.findByEmail(email).isEmpty()){
                StaffMember sm=new StaffMember();
                sm.setFullName(fn+" "+ln+" ("+f.getFacilityName()+")"); sm.setGender(RC[i][2]);
                sm.setEmail(email); sm.setPhoneNumber(RC[i][3]+String.format("%05d",fIdx*10+i));
                sm.setRole("RECEPTIONIST"); sm.setQualification("Administration");
                sm.setDepartment("Reception"); sm.setFacility(f);
                sm.setEmployeeNumber("REC-"+String.format("%05d",fIdx*10+i+1));
                staffMemberRepository.save(sm);
                seedUser(email,pass,"RECEPTIONIST");
            }
            pdfRows.add(new String[]{"RECEPTIONIST",f.getFacilityName(),fn+" "+ln,email,pass});
        }
    }

    private void seedFacilityPharmacist(Facility f, int fIdx){
        String tag=slug(f.getFacilityName());
        String email="pharmacist."+tag+"@nmas.gov.za";
        String pass="Pharm@"+String.format("%04d",fIdx+1);
        if(staffMemberRepository.findByEmail(email).isEmpty()&&appUserRepository.findByEmail(email).isEmpty()){
            StaffMember sm=new StaffMember();
            sm.setFullName("Pharmacist "+f.getFacilityName()); sm.setEmail(email);
            sm.setRole("PHARMACIST"); sm.setQualification("BPharm");
            sm.setDepartment("Pharmacy"); sm.setFacility(f);
            sm.setEmployeeNumber("PHM-"+String.format("%04d",fIdx+1));
            staffMemberRepository.save(sm);
            seedUser(email,pass,"PHARMACIST");
        }
        pdfRows.add(new String[]{"PHARMACIST",f.getFacilityName(),"Pharmacist "+f.getFacilityName(),email,pass});
    }

    private static final String[][] PT = {
        {"Thabo","Nkosi","Male"},{"Nomsa","Dlamini","Female"},{"Ayanda","Zulu","Female"},
        {"Sipho","Ndlovu","Male"},{"Fatima","Moosa","Female"},{"Blessing","Mathebula","Female"},
        {"Petrus","van Wyk","Male"},{"Zanele","Khumalo","Female"},{"Lucky","Mhlongo","Male"},
        {"Precious","Sithole","Female"},{"Gift","Mahlangu","Male"},{"Bongiwe","Shabalala","Female"},
        {"Musa","Masondo","Male"},{"Lindiwe","Nkosi","Female"},{"Johannes","Botha","Male"},
        {"Gcinile","Zwane","Female"},{"Mandla","Buthelezi","Male"},{"Nomvula","Mokoena","Female"},
        {"Thandeka","Vilakazi","Female"},{"Bongani","Cele","Male"},{"Mpho","Mokwena","Female"},
        {"Siphamandla","Ntanzi","Male"},{"Nokwanda","Shange","Female"},{"Thulani","Gumede","Male"},
        {"Nompumelelo","Mthethwa","Female"},{"Siyanda","Bhengu","Male"},{"Zanokuhle","Mkhize","Female"},
        {"Mthokozisi","Hadebe","Male"},{"Sibusisiwe","Khoza","Female"},{"Sandile","Myeni","Male"},
        {"Nokukhanya","Mbatha","Female"},{"Lungelo","Ntuli","Male"},{"Phumzile","Gwala","Female"},
        {"Mfanafuthi","Mthiyane","Male"},{"Ntombifuthi","Majola","Female"},{"Wiseman","Shezi","Male"},
        {"Nozipho","Dube","Female"},{"Mthembeni","Mthethwa","Male"},{"Sikhulekile","Msweli","Female"},
        {"Ngcebo","Shabalala","Male"},{"Thandazile","Nxumalo","Female"},{"Mlungisi","Madlala","Male"},
        {"Zinhle","Maphumulo","Female"},{"Sifiso","Ngcobo","Male"},{"Nothando","Ngubane","Female"},
        {"Mduduzi","Mthembu","Male"},{"Phiwayinkosi","Khanyile","Male"},{"Nomathemba","Mzimela","Female"},
        {"Buhlebethu","Dlamini","Female"},{"Lwethu","Mbatha","Male"},
    };

    private void seedFacilityPatients(Facility f, int fIdx){
        String[] bt={"A+","A-","B+","B-","O+","O-","AB+","AB-"};
        String[] cc={"None","Hypertension","Diabetes Type 2","Asthma","HIV/AIDS","Tuberculosis","Epilepsy","Arthritis","None","None"};
        String[] ma={"None","Discovery Health","Bonitas","Medshield","Momentum Health","None"};
        for(int i=0;i<PT.length;i++){
            try {
                String fn=PT[i][0], ln=PT[i][1], gender=PT[i][2];
                // Build a clean 13-digit numeric ID
                int year = 70 + (fIdx + i) % 30;
                int month = (i % 12) + 1;
                int day = (i % 28) + 1;
                int seq = fIdx * 100 + i;
                int genderDigit = gender.equals("Male") ? 5 : 0;
                String idBase = String.format("%02d%02d%02d%04d%d082",
                    year, month, day, seq, genderDigit);
                // Ensure exactly 13 digits
                if (idBase.length() > 13) idBase = idBase.substring(0, 13);
                while (idBase.length() < 13) idBase = idBase + "0";

                // Clean email: only lowercase letters, digits, dots
                String fnClean = fn.toLowerCase().replaceAll("[^a-z0-9]", "");
                String lnClean = ln.toLowerCase().replaceAll("[^a-z0-9]", "");
                String email = fnClean + "." + lnClean + ".f" + fIdx + "p" + i + "@patient.nmas.ac.za";
                String pass = idBase;

                if (appUserRepository.findByEmail(email).isPresent()) {
                    pdfRows.add(new String[]{"PATIENT", f.getFacilityName(), fn + " " + ln, email, pass + " (ID)"});
                    continue;
                }
                if (patientRepository.findByIdNumber(idBase).isPresent()) continue;

                Patient p = new Patient();
                p.setIdNumber(idBase);
                p.setFullName(fn + " " + ln);
                p.setDateOfBirth(LocalDate.of(1970 + (fIdx + i) % 50, month, day));
                p.setGender(gender);
                p.setNationality("South African");
                p.setEmail(email);
                p.setPhoneNumber("07" + String.format("%08d", (long)(fIdx * 1000 + i + 100)));
                p.setCity(f.getCity());
                p.setProvince("Mpumalanga");
                p.setPreferredFacility(f.getFacilityName());
                p.setRegisteredFacility(f);
                p.setBloodType(bt[i % bt.length]);
                p.setChronicConditions(cc[i % cc.length]);
                p.setMedicalAidStatus(ma[i % ma.length]);
                p.setConsentToStoreRecords(true);
                p.setConsentToShareRecords(true);
                patientRepository.save(p);

                AppUser u = new AppUser();
                u.setEmail(email);
                u.setPassword(passwordEncoder.encode(pass));
                u.setRole("PATIENT");
                appUserRepository.save(u);

                pdfRows.add(new String[]{"PATIENT", f.getFacilityName(), fn + " " + ln, email, pass + " (ID)"});

            } catch (Exception e) {
                System.err.println("⚠️  Skipped patient " + i + " for " + f.getFacilityName() + ": " + e.getMessage());
            }
        }
    }

    private void generateLoginPdf() throws Exception {
        if (pdfRows.isEmpty()) return;
        // Always the COMPLETE list of seeded accounts (staff, doctors, patients), grouped by role.
        String path=System.getProperty("user.home")+"/nmas-logins.pdf";
        java.util.List<String> order=java.util.List.of("SYSTEM_ADMIN","FACILITY_ADMIN","DOCTOR","NURSE","PHARMACIST","RECEPTIONIST","PATIENT");
        pdfRows.sort(java.util.Comparator.comparingInt(r -> { int i=order.indexOf(r[0]); return i<0?99:i; }));
        Document doc=new Document(PageSize.A4.rotate(),30,30,40,30);
        PdfWriter.getInstance(doc,new FileOutputStream(path));
        doc.open();

        Font tf=new Font(Font.HELVETICA,18,Font.BOLD,new Color(28,43,43));
        Font sf=new Font(Font.HELVETICA,10,Font.NORMAL,new Color(100,116,139));
        Font hf=new Font(Font.HELVETICA,9,Font.BOLD,Color.WHITE);
        Font cf=new Font(Font.HELVETICA,8,Font.NORMAL,new Color(30,41,59));

        Paragraph t=new Paragraph("NMAS — System Login Credentials",tf);
        t.setAlignment(Element.ALIGN_CENTER); doc.add(t);
        Paragraph s=new Paragraph("Mpumalanga Health Facilities · Generated: "
            +LocalDateTime.now().toString().substring(0,16)+" · STRICTLY CONFIDENTIAL · Passwords shown are the INITIAL passwords; any changed since are not reflected",sf);
        s.setAlignment(Element.ALIGN_CENTER); s.setSpacingAfter(16); doc.add(s);

        PdfPTable table=new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{12,24,22,28,14});
        Color hBg=new Color(28,43,43);
        for(String h:new String[]{"ROLE","FACILITY","FULL NAME","EMAIL / USERNAME","PASSWORD"}){
            PdfPCell c=new PdfPCell(new Phrase(h,hf));
            c.setBackgroundColor(hBg); c.setPadding(6); c.setBorderColor(Color.WHITE);
            table.addCell(c);
        }

        Map<String,Color> rp=new LinkedHashMap<>();
        rp.put("SYSTEM_ADMIN",new Color(139,0,0));
        rp.put("FACILITY_ADMIN",new Color(28,43,43));
        rp.put("DOCTOR",new Color(5,150,105));
        rp.put("NURSE",new Color(37,99,235));
        rp.put("PHARMACIST",new Color(124,58,237));
        rp.put("RECEPTIONIST",new Color(234,88,12));
        rp.put("PATIENT",new Color(100,116,139));
        Color[] bg={new Color(248,250,252),new Color(240,250,245)};

        int row=0;
        for(String[] r:pdfRows){
            Color b=bg[row%2];
            Color rc=rp.getOrDefault(r[0],Color.DARK_GRAY);
            Font rf=new Font(Font.HELVETICA,7,Font.BOLD,rc);
            for(int col=0;col<5;col++){
                PdfPCell c=new PdfPCell(new Phrase(r[col],col==0?rf:cf));
                c.setBackgroundColor(b); c.setPadding(5); table.addCell(c);
            }
            row++;
        }
        doc.add(table);

        Font ff=new Font(Font.HELVETICA,8,Font.ITALIC,new Color(148,163,184));
        Paragraph footer=new Paragraph("\nCONFIDENTIAL — NMAS authorised personnel only. Total accounts: "+pdfRows.size(),ff);
        footer.setAlignment(Element.ALIGN_CENTER); doc.add(footer);
        doc.close();
    }

    private boolean seedUser(String email,String pass,String role){
        if(appUserRepository.findByEmail(email).isPresent()) return false;
        AppUser u=new AppUser();
        u.setEmail(email); u.setPassword(passwordEncoder.encode(pass)); u.setRole(role);
        appUserRepository.save(u);
        return true;
    }

    private String slug(String name){
        return name.toLowerCase().replaceAll("[^a-z0-9]",".").replaceAll("\\.+",".").replaceAll("^\\.|\\.$","");
    }

    private void seedMedications(){
        // ── Tablets / capsules — counted individually, packed, dispensed in whole packs ──
        seedMed("MED-001","Paracetamol","Acetaminophen","Tablet","500mg",true,20);
        seedMed("MED-002","Ibuprofen","Ibuprofen","Tablet","400mg",true,24);
        seedMed("MED-003","Amoxicillin","Amoxicillin","Capsule","500mg",true,21);
        seedMed("MED-004","Metformin","Metformin HCl","Tablet","500mg",true,30);
        seedMed("MED-005","Amlodipine","Amlodipine Besylate","Tablet","5mg",true,30);
        seedMed("MED-006","Omeprazole","Omeprazole","Capsule","20mg",true,14);
        seedMed("MED-009","Cetirizine","Cetirizine HCl","Tablet","10mg",true,30);
        seedMed("MED-010","Azithromycin","Azithromycin","Tablet","500mg",true,3);
        seedMed("MED-011","Aspirin","Acetylsalicylic Acid","Tablet","150mg",false,28);
        seedMed("MED-012","Simvastatin","Simvastatin","Tablet","20mg",true,30);
        seedMed("MED-013","Enalapril","Enalapril Maleate","Tablet","10mg",true,30);
        seedMed("MED-014","Doxycycline","Doxycycline","Capsule","100mg",true,10);
        seedMed("MED-015","Bisoprolol","Bisoprolol Fumarate","Tablet","5mg",true,28);
        seedMed("MED-016","Loperamide","Loperamide HCl","Capsule","2mg",false,12);
        seedMed("MED-017","Folic Acid","Folic Acid","Tablet","5mg",true,30);
        seedMed("MED-018","Dulcolax","Bisacodyl","Tablet","5mg",false,10);
        seedMed("MED-034","Isoniazid","Isoniazid","Tablet","300mg",true,28);
        seedMed("MED-035","Efavirenz/Tenofovir/Emtricitabine","Efavirenz/TDF/FTC","Tablet","600/300/200mg",true,30);

        // ── Suppositories — also individually counted, packed like tablets ──
        seedMed("MED-019","Paracetamol Suppository","Acetaminophen","Suppository","250mg",false,10);

        // ── Injections / vials / ampoules — whole containers, no pill-count math ──
        seedMed("MED-008","Insulin (Regular)","Human Insulin","Injection","100IU/ml",true,null);
        seedMed("MED-020","Insulin (NPH)","Isophane Insulin","Injection","100IU/ml",true,null);
        seedMed("MED-021","Diclofenac Injection","Diclofenac Sodium","Injection","75mg/3ml",true,null);
        seedMed("MED-022","Ceftriaxone Injection","Ceftriaxone Sodium","Injection","1g",true,null);
        seedMed("MED-023","Adrenaline (Epinephrine)","Epinephrine","Ampoule","1mg/ml",true,null);
        seedMed("MED-024","Tetanus Toxoid","Tetanus Toxoid Vaccine","Vial","0.5ml",true,null);

        // ── Insulin pens / auto-injectors — whole containers ──
        seedMed("MED-025","Insulin Glargine Pen","Insulin Glargine","Injection","100U/ml (3ml pen)",true,null);

        // ── Creams / ointments / gels / lotions — dispensed as whole tubes/bottles ──
        seedMed("MED-026","Hydrocortisone Cream","Hydrocortisone","Cream","1%",false,null);
        seedMed("MED-027","Clotrimazole Cream","Clotrimazole","Cream","1%",false,null);
        seedMed("MED-028","Betamethasone Ointment","Betamethasone Valerate","Ointment","0.1%",true,null);
        seedMed("MED-029","Diclofenac Gel","Diclofenac Diethylamine","Gel","1%",false,null);
        seedMed("MED-039","Aqueous Cream","Aqueous Cream BP","Cream","100g",false,null);
        seedMed("MED-040","Calamine Lotion","Calamine","Lotion","15%",false,null);
        seedMed("MED-041","Chlorphenamine (Piriton) Cream","Chlorpheniramine Maleate","Cream","2%",false,null);
        seedMed("MED-042","Benzyl Benzoate Lotion","Benzyl Benzoate","Lotion","25%",true,null);
        seedMed("MED-043","Silver Sulfadiazine Cream","Silver Sulfadiazine","Cream","1%",true,null);
        seedMed("MED-044","Zinc Oxide Ointment","Zinc Oxide","Ointment","15%",false,null);

        // ── Syrups / suspensions / solutions — dispensed as whole bottles ──
        seedMed("MED-030","Amoxicillin Syrup","Amoxicillin","Syrup","125mg/5ml",true,null);
        seedMed("MED-031","Paracetamol Syrup","Acetaminophen","Suspension","120mg/5ml",false,null);
        seedMed("MED-032","Salbutamol Syrup","Albuterol","Syrup","2mg/5ml",true,null);

        // ── Drops — dispensed as whole dropper bottles ──
        seedMed("MED-033","Chloramphenicol Eye Drops","Chloramphenicol","Drops","0.5%",true,null);

        // ── Inhalers — dispensed as whole inhalers ──
        seedMed("MED-007","Salbutamol","Albuterol","Inhaler","100mcg",true,null);
        seedMed("MED-036","Beclomethasone Inhaler","Beclomethasone Dipropionate","Inhaler","50mcg",true,null);

        // ── Patches — dispensed as whole boxes of patches (counted like a pack) ──
        seedMed("MED-037","Fentanyl Patch","Fentanyl","Patch","25mcg/hr",true,5);

        // ── Sachets / powders — dispensed as whole boxes of sachets ──
        seedMed("MED-038","Oral Rehydration Salts","ORS","Sachet","20.5g",false,10);
    }

    private void seedMed(String code,String name,String generic,String form,String strength,boolean rx,Integer pills){
        if(medicationRepository.existsByMedicationCode(code)) return;
        Medication m=new Medication();
        m.setMedicationCode(code); m.setMedicationName(name); m.setGenericName(generic);
        m.setDosageForm(form); m.setStrength(strength); m.setManufacturer("NMAS Formulary");
        m.setRequiresPrescription(rx); m.setStockStatus("IN_STOCK"); m.setActive(true);
        m.setPillsPerPack(pills); medicationRepository.save(m);
    }

    private void seedInventoryForAllFacilities() {
        for (Facility f : facilityRepository.findAll()) {
            currentFacility = f;
            seedInventory();
        }
        currentFacility = null;
    }

    /**
     * Before stock was split per facility there was one shared list. Hand that list to the first
     * facility (keeping the same rows so existing orders stay valid), copy it for every other
     * facility, and point still-pending dispensary orders at their own facility's shelf.
     */
    private void migrateLegacyInventory() {
        List<MedicationInventory> legacy = medicationInventoryRepository.findByFacilityIsNull();
        List<Facility> facs = facilityRepository.findAll();
        if (legacy.isEmpty() || facs.isEmpty()) return;
        for (MedicationInventory old : legacy) {
            for (int i = 1; i < facs.size(); i++) {
                MedicationInventory copy = new MedicationInventory();
                copy.setFacility(facs.get(i));
                copy.setMedication(old.getMedication());
                copy.setMedicationName(old.getMedicationName());
                copy.setGenericName(old.getGenericName());
                copy.setCategory(old.getCategory());
                copy.setUnit(old.getUnit());
                copy.setQuantityInStock(old.getQuantityInStock());
                copy.setReorderLevel(old.getReorderLevel());
                copy.setSupplier(old.getSupplier());
                copy.setPillsPerPack(old.getPillsPerPack());
                copy.setExpiryDate(old.getExpiryDate());
                copy.setBatchNumber(old.getBatchNumber());
                medicationInventoryRepository.save(copy);
            }
            old.setFacility(facs.get(0));
            medicationInventoryRepository.save(old);
        }
        for (DispensaryOrder o : dispensaryOrderRepository.findAll()) {
            if (!("PENDING".equals(o.getStatus()) || "OUT_OF_STOCK".equals(o.getStatus()))) continue;
            if (o.getMedication() == null || o.getPrescription() == null || o.getPrescription().getDoctor() == null
                    || o.getPrescription().getDoctor().getFacility() == null) continue;
            medicationInventoryRepository.findFirstByMedicationIdAndFacilityId(
                    o.getMedication().getId(), o.getPrescription().getDoctor().getFacility().getId())
                    .ifPresent(row -> { o.setInventoryItem(row); dispensaryOrderRepository.save(o); });
        }
        System.out.println("NMAS: shared inventory split into per-facility stock (" + facs.size() + " facilities).");
    }

    private void seedInventory(){
        // ── Tablets / capsules / suppositories — stored & dispensed as Packs ──
        seedInv("MED-001",40,10,"Packs"); seedInv("MED-002",35,8,"Packs");
        seedInv("MED-003",30,8,"Packs");  seedInv("MED-004",25,6,"Packs");
        seedInv("MED-005",20,5,"Packs");  seedInv("MED-006",25,6,"Packs");
        seedInv("MED-009",30,8,"Packs");  seedInv("MED-010",20,5,"Packs");
        seedInv("MED-011",25,8,"Packs");  seedInv("MED-012",30,8,"Packs");
        seedInv("MED-013",25,6,"Packs");  seedInv("MED-014",18,6,"Packs");
        seedInv("MED-015",22,6,"Packs");  seedInv("MED-016",20,5,"Packs");
        seedInv("MED-017",30,8,"Packs");  seedInv("MED-018",15,5,"Packs");
        seedInv("MED-019",12,4,"Packs");
        seedInv("MED-034",25,6,"Packs");  seedInv("MED-035",25,6,"Packs");

        // ── Injections / vials / ampoules — stored & dispensed as whole Vials/Ampoules ──
        seedInv("MED-008",10,3,"Vials");  seedInv("MED-020",10,3,"Vials");
        seedInv("MED-021",12,4,"Vials");  seedInv("MED-022",15,5,"Vials");
        seedInv("MED-023",8,3,"Ampoules");seedInv("MED-024",20,5,"Vials");
        seedInv("MED-025",10,3,"Pens");

        // ── Creams / ointments / gels / lotions — stored & dispensed as whole Tubes/Bottles ──
        seedInv("MED-026",20,5,"Tubes");  seedInv("MED-027",18,5,"Tubes");
        seedInv("MED-028",15,5,"Tubes");  seedInv("MED-029",15,5,"Tubes");
        seedInv("MED-039",25,6,"Tubes");  seedInv("MED-040",18,5,"Bottles");
        seedInv("MED-041",15,5,"Tubes");  seedInv("MED-042",12,4,"Bottles");
        seedInv("MED-043",10,3,"Tubes");  seedInv("MED-044",16,5,"Tubes");

        // ── Syrups / suspensions — stored & dispensed as whole Bottles ──
        seedInv("MED-030",15,5,"Bottles");seedInv("MED-031",20,6,"Bottles");
        seedInv("MED-032",12,4,"Bottles");

        // ── Drops — stored & dispensed as whole Bottles ──
        seedInv("MED-033",14,4,"Bottles");

        // ── Inhalers — stored & dispensed as whole Inhalers ──
        seedInv("MED-007",15,5,"Inhalers");seedInv("MED-036",12,4,"Inhalers");

        // ── Patches — stored & dispensed as whole Packs (box of patches) ──
        seedInv("MED-037",10,3,"Packs");

        // ── Sachets — stored & dispensed as whole Packs (box of sachets) ──
        seedInv("MED-038",25,8,"Packs");
    }

    /**
     * Fixes existing inventory rows that were stored with unit = "Tablets" or "Capsules"
     * instead of "Packs". Converts them to pack-based tracking using the medication's
     * pillsPerPack value. Calculates the correct pack count from the existing pill count.
     * Safe to run repeatedly — skips rows that are already pack-tracked.
     */
    private void fixExistingInventoryUnits() {
        java.util.List<MedicationInventory> allInv = medicationInventoryRepository.findAll();
        for (MedicationInventory inv : allInv) {
            // Skip if already pack-tracked
            if (inv.isPackTracked()) continue;
            // Only fix Tablet/Capsule units — not Vials, Inhalers, mL etc.
            String unit = inv.getUnit();
            if (unit == null) continue;
            if (!unit.equalsIgnoreCase("Tablets") && !unit.equalsIgnoreCase("Capsules")
                    && !unit.equalsIgnoreCase("Tablet") && !unit.equalsIgnoreCase("Capsule")) continue;
            // Get pillsPerPack from linked Medication
            Integer pillsPerPack = null;
            if (inv.getMedication() != null && inv.getMedication().getPillsPerPack() != null) {
                pillsPerPack = inv.getMedication().getPillsPerPack();
            }
            if (pillsPerPack == null || pillsPerPack <= 0) continue;
            // Convert: current quantity is in pills → convert to packs (ceiling)
            int currentPills = inv.getQuantityInStock();
            int packs = (int) Math.ceil((double) currentPills / pillsPerPack);
            int reorderPacks = Math.max(1, (int) Math.ceil((double) inv.getReorderLevel() / pillsPerPack));
            inv.setUnit("Packs");
            inv.setPillsPerPack(pillsPerPack);
            inv.setQuantityInStock(packs);
            inv.setReorderLevel(reorderPacks);
            medicationInventoryRepository.save(inv);
            System.out.println("✅ Fixed inventory: " + inv.getMedicationName()
                + " → " + packs + " packs (" + (packs * pillsPerPack) + " pills)");
        }
    }

    /**
     * Older/legacy patient rows (or ones registered outside the normal
     * facility-registration flow) may only have the free-text
     * preferredFacility field, not a proper registeredFacility link. This
     * best-effort-matches that text against a real Facility by name so
     * record-access checks (same-facility vs. cross-facility) have
     * something reliable to compare against. Patients with no matching
     * facility text are left with registeredFacility = null, which simply
     * means no cross-facility restriction applies to them.
     */
    private void backfillPatientRegisteredFacility() {
        List<Patient> patients = patientRepository.findAll();
        List<Facility> facilities = facilityRepository.findAll();
        for (Patient p : patients) {
            if (p.getRegisteredFacility() != null) continue;
            if (p.getPreferredFacility() == null || p.getPreferredFacility().isBlank()) continue;
            for (Facility f : facilities) {
                if (f.getFacilityName().equalsIgnoreCase(p.getPreferredFacility().trim())) {
                    p.setRegisteredFacility(f);
                    patientRepository.save(p);
                    break;
                }
            }
        }
    }

    private void seedInv(String code, int qty, int reorder, String unit) {
        medicationRepository.findByMedicationCode(code).ifPresent(med -> {
            if (currentFacility == null) return;
            if (medicationInventoryRepository.findFirstByMedicationIdAndFacilityId(med.getId(), currentFacility.getId()).isPresent()) return;
            MedicationInventory inv = new MedicationInventory();
            inv.setFacility(currentFacility);
            inv.setMedication(med);
            inv.setMedicationName(med.getMedicationName());
            inv.setGenericName(med.getGenericName());
            // Always store as Packs when pillsPerPack is defined on the medication
            boolean hasPillsPerPack = med.getPillsPerPack() != null && med.getPillsPerPack() > 0;
            inv.setUnit(hasPillsPerPack ? "Packs" : unit);
            // Set pillsPerPack on the inventory row so isPackTracked() returns true
            // even before the Medication join is resolved in templates
            if (hasPillsPerPack) {
                inv.setPillsPerPack(med.getPillsPerPack());
            }
            inv.setQuantityInStock(qty);   // quantity = number of PACKS
            inv.setReorderLevel(reorder);  // reorder = number of PACKS
            inv.setSupplier("NMAS Formulary");
            inv.setExpiryDate(sampleExpiry(code));
            inv.setBatchNumber("LOT-" + code.replace("MED-", ""));
            medicationInventoryRepository.save(inv);
        });
    }

    /**
     * Demo expiry dates so the colour-coding is visible straight away: most stock is
     * comfortably in date, a few are inside the 6-month (and 3-month) warning window,
     * and one is already past its date. Real dates replace these when stock is restocked.
     */
    private java.time.LocalDate sampleExpiry(String code) {
        int n = 0;
        for (char c : code.toCharArray()) if (Character.isDigit(c)) n = n * 10 + (c - '0');
        java.time.LocalDate today = java.time.LocalDate.now();
        if (n % 13 == 0) return today.minusDays(20);           // already expired
        if (n % 7 == 0)  return today.plusMonths(2);           // expires within 3 months
        if (n % 5 == 0)  return today.plusMonths(5);           // expires within 6 months
        return today.plusMonths(9 + (n % 20));                 // comfortably in date
    }

    /** Existing inventory rows created before expiry dates existed get a starting date once. */
    private void backfillInventoryExpiry() {
        for (MedicationInventory inv : medicationInventoryRepository.findAll()) {
            if (inv.getExpiryDate() != null) continue;
            String code = inv.getMedication() != null && inv.getMedication().getMedicationCode() != null
                    ? inv.getMedication().getMedicationCode() : "MED-" + inv.getId();
            inv.setExpiryDate(sampleExpiry(code));
            if (inv.getBatchNumber() == null) inv.setBatchNumber("LOT-" + code.replace("MED-", ""));
            medicationInventoryRepository.save(inv);
        }
    }
}
