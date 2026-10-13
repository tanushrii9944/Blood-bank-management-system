import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * Blood Bank Management System
 * A console-based application to manage donors, blood inventory and blood requests.
 * Data is saved as pipe-delimited text files inside the "data" folder.
 */
public class BloodBankManagementSystem {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------
    private static final int MIN_DONOR_AGE = 18;
    private static final int MAX_DONOR_AGE = 65;
    private static final int DONATION_GAP_DAYS = 56;
    private static final int SHELF_LIFE_DAYS = 35;
    private static final String DATA_DIR = "data";

    // ------------------------------------------------------------------
    // Blood group with compatibility rules
    // ------------------------------------------------------------------
    enum BloodGroup {
        A_POS("A+"), A_NEG("A-"), B_POS("B+"), B_NEG("B-"),
        AB_POS("AB+"), AB_NEG("AB-"), O_POS("O+"), O_NEG("O-");

        final String label;

        BloodGroup(String label) {
            this.label = label;
        }

        static BloodGroup fromLabel(String text) {
            if (text == null) return null;
            String t = text.trim().toUpperCase();
            for (BloodGroup g : values()) {
                if (g.label.equals(t)) return g;
            }
            return null;
        }

        /** Groups whose blood this (recipient) group can safely receive. */
        List<BloodGroup> compatibleDonorGroups() {
            switch (this) {
                case A_POS:  return List.of(A_POS, A_NEG, O_POS, O_NEG);
                case A_NEG:  return List.of(A_NEG, O_NEG);
                case B_POS:  return List.of(B_POS, B_NEG, O_POS, O_NEG);
                case B_NEG:  return List.of(B_NEG, O_NEG);
                case AB_POS: return List.of(values());
                case AB_NEG: return List.of(A_NEG, B_NEG, AB_NEG, O_NEG);
                case O_POS:  return List.of(O_POS, O_NEG);
                default:     return List.of(O_NEG);
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // ------------------------------------------------------------------
    // Models
    // ------------------------------------------------------------------
    static class Donor {
        int id;
        String name;
        int age;
        String gender;
        BloodGroup group;
        String phone;
        LocalDate lastDonation; // may be null

        boolean isEligible(LocalDate today) {
            if (age < MIN_DONOR_AGE || age > MAX_DONOR_AGE) return false;
            return lastDonation == null
                    || ChronoUnit.DAYS.between(lastDonation, today) >= DONATION_GAP_DAYS;
        }

        String toLine() {
            return id + "|" + name + "|" + age + "|" + gender + "|" + group.label + "|"
                    + phone + "|" + (lastDonation == null ? "-" : lastDonation);
        }

        static Donor fromLine(String line) {
            String[] p = line.split("\\|", -1);
            Donor d = new Donor();
            d.id = Integer.parseInt(p[0]);
            d.name = p[1];
            d.age = Integer.parseInt(p[2]);
            d.gender = p[3];
            d.group = BloodGroup.fromLabel(p[4]);
            d.phone = p[5];
            d.lastDonation = p[6].equals("-") ? null : LocalDate.parse(p[6]);
            return d;
        }
    }

    static class BloodUnit {
        int id;
        BloodGroup group;
        int donorId;
        LocalDate collected;
        LocalDate expiry;

        boolean isExpired(LocalDate today) {
            return expiry.isBefore(today);
        }

        String toLine() {
            return id + "|" + group.label + "|" + donorId + "|" + collected + "|" + expiry;
        }

        static BloodUnit fromLine(String line) {
            String[] p = line.split("\\|", -1);
            BloodUnit u = new BloodUnit();
            u.id = Integer.parseInt(p[0]);
            u.group = BloodGroup.fromLabel(p[1]);
            u.donorId = Integer.parseInt(p[2]);
            u.collected = LocalDate.parse(p[3]);
            u.expiry = LocalDate.parse(p[4]);
            return u;
        }
    }

    static class BloodRequest {
        int id;
        String patient;
        String hospital;
        BloodGroup group;
        int units;
        LocalDate date;
        String status; // PENDING or FULFILLED

        String toLine() {
            return id + "|" + patient + "|" + hospital + "|" + group.label + "|"
                    + units + "|" + date + "|" + status;
        }

        static BloodRequest fromLine(String line) {
            String[] p = line.split("\\|", -1);
            BloodRequest r = new BloodRequest();
            r.id = Integer.parseInt(p[0]);
            r.patient = p[1];
            r.hospital = p[2];
            r.group = BloodGroup.fromLabel(p[3]);
            r.units = Integer.parseInt(p[4]);
            r.date = LocalDate.parse(p[5]);
            r.status = p[6];
            return r;
        }
    }

    // ------------------------------------------------------------------
    // Blood bank service (business logic + persistence)
    // ------------------------------------------------------------------
    static class BloodBank {
        private final List<Donor> donors = new ArrayList<>();
        private final List<BloodUnit> units = new ArrayList<>();
        private final List<BloodRequest> requests = new ArrayList<>();
        private int nextDonorId = 1;
        private int nextUnitId = 1;
        private int nextRequestId = 1;

        // ---------- Donors ----------
        Donor registerDonor(String name, int age, String gender, BloodGroup group, String phone) {
            Donor d = new Donor();
            d.id = nextDonorId++;
            d.name = clean(name);
            d.age = age;
            d.gender = clean(gender);
            d.group = group;
            d.phone = clean(phone);
            donors.add(d);
            save();
            return d;
        }

        Donor findDonor(int id) {
            return donors.stream().filter(d -> d.id == id).findFirst().orElse(null);
        }

        List<Donor> getDonors() {
            return donors;
        }

        List<Donor> searchDonors(BloodGroup group) {
            return donors.stream().filter(d -> d.group == group).collect(Collectors.toList());
        }

        // ---------- Donations ----------
        /** Returns null on success, otherwise a message explaining why the donation was refused. */
        String recordDonation(Donor donor) {
            LocalDate today = LocalDate.now();
            if (donor.age < MIN_DONOR_AGE || donor.age > MAX_DONOR_AGE) {
                return "Donor age must be between " + MIN_DONOR_AGE + " and " + MAX_DONOR_AGE + ".";
            }
            if (donor.lastDonation != null) {
                long days = ChronoUnit.DAYS.between(donor.lastDonation, today);
                if (days < DONATION_GAP_DAYS) {
                    return "Donor must wait " + (DONATION_GAP_DAYS - days)
                            + " more day(s) since the last donation (" + donor.lastDonation + ").";
                }
            }
            BloodUnit u = new BloodUnit();
            u.id = nextUnitId++;
            u.group = donor.group;
            u.donorId = donor.id;
            u.collected = today;
            u.expiry = today.plusDays(SHELF_LIFE_DAYS);
            units.add(u);
            donor.lastDonation = today;
            save();
            return null;
        }

        // ---------- Inventory ----------
        int removeExpired() {
            LocalDate today = LocalDate.now();
            int before = units.size();
            units.removeIf(u -> u.isExpired(today));
            int removed = before - units.size();
            if (removed > 0) save();
            return removed;
        }

        Map<BloodGroup, List<BloodUnit>> inventoryByGroup() {
            Map<BloodGroup, List<BloodUnit>> map = new EnumMap<>(BloodGroup.class);
            for (BloodGroup g : BloodGroup.values()) map.put(g, new ArrayList<>());
            LocalDate today = LocalDate.now();
            for (BloodUnit u : units) {
                if (!u.isExpired(today)) map.get(u.group).add(u);
            }
            return map;
        }

        // ---------- Requests ----------
        BloodRequest createRequest(String patient, String hospital, BloodGroup group, int count) {
            BloodRequest r = new BloodRequest();
            r.id = nextRequestId++;
            r.patient = clean(patient);
            r.hospital = clean(hospital);
            r.group = group;
            r.units = count;
            r.date = LocalDate.now();
            r.status = "PENDING";
            requests.add(r);
            tryFulfil(r);
            save();
            return r;
        }

        /**
         * Issues blood for a request if enough stock exists. The exact group is used first,
         * then compatible groups. Oldest units (nearest expiry) are issued first.
         */
        boolean tryFulfil(BloodRequest r) {
            if (!"PENDING".equals(r.status)) return false;
            removeExpired();

            List<BloodUnit> chosen = new ArrayList<>();
            List<BloodGroup> order = new ArrayList<>();
            order.add(r.group);
            for (BloodGroup g : r.group.compatibleDonorGroups()) {
                if (g != r.group) order.add(g);
            }
            for (BloodGroup g : order) {
                List<BloodUnit> stock = units.stream()
                        .filter(u -> u.group == g)
                        .sorted(Comparator.comparing((BloodUnit u) -> u.expiry))
                        .collect(Collectors.toList());
                for (BloodUnit u : stock) {
                    if (chosen.size() < r.units) chosen.add(u);
                }
                if (chosen.size() >= r.units) break;
            }
            if (chosen.size() < r.units) return false; // not enough stock, nothing issued

            units.removeAll(chosen);
            r.status = "FULFILLED";
            save();
            return true;
        }

        int processPending() {
            int fulfilled = 0;
            for (BloodRequest r : requests) {
                if ("PENDING".equals(r.status) && tryFulfil(r)) fulfilled++;
            }
            return fulfilled;
        }

        List<BloodRequest> getRequests() {
            return requests;
        }

        // ---------- Persistence ----------
        private static String clean(String s) {
            return s == null ? "" : s.replace("|", "/").trim();
        }

        void save() {
            try {
                Files.createDirectories(Paths.get(DATA_DIR));
                write("donors.txt", donors.stream().map(Donor::toLine).collect(Collectors.toList()));
                write("units.txt", units.stream().map(BloodUnit::toLine).collect(Collectors.toList()));
                write("requests.txt", requests.stream().map(BloodRequest::toLine).collect(Collectors.toList()));
            } catch (IOException e) {
                System.out.println("Warning: could not save data - " + e.getMessage());
            }
        }

        void load() {
            try {
                for (String line : read("donors.txt")) donors.add(Donor.fromLine(line));
                for (String line : read("units.txt")) units.add(BloodUnit.fromLine(line));
                for (String line : read("requests.txt")) requests.add(BloodRequest.fromLine(line));
            } catch (IOException | RuntimeException e) {
                System.out.println("Warning: could not read saved data - " + e.getMessage());
                donors.clear();
                units.clear();
                requests.clear();
            }
            nextDonorId = donors.stream().mapToInt(d -> d.id).max().orElse(0) + 1;
            nextUnitId = units.stream().mapToInt(u -> u.id).max().orElse(0) + 1;
            nextRequestId = requests.stream().mapToInt(r -> r.id).max().orElse(0) + 1;
        }

        private void write(String file, List<String> lines) throws IOException {
            Files.write(Paths.get(DATA_DIR, file), lines);
        }

        private List<String> read(String file) throws IOException {
            Path p = Paths.get(DATA_DIR, file);
            if (!Files.exists(p)) return new ArrayList<>();
            return Files.readAllLines(p).stream()
                    .filter(l -> !l.isBlank())
                    .collect(Collectors.toList());
        }
    }

    // ------------------------------------------------------------------
    // Console UI
    // ------------------------------------------------------------------
    private static final Scanner SC = new Scanner(System.in);
    private static final BloodBank BANK = new BloodBank();

    public static void main(String[] args) {
        BANK.load();
        int removed = BANK.removeExpired();
        System.out.println("=====================================");
        System.out.println("     BLOOD BANK MANAGEMENT SYSTEM");
        System.out.println("=====================================");
        if (removed > 0) System.out.println(removed + " expired unit(s) were removed on startup.");

        boolean running = true;
        while (running) {
            printMenu();
            int choice = readInt("Enter your choice: ", 0, 9);
            System.out.println();
            switch (choice) {
                case 1: registerDonor(); break;
                case 2: viewDonors(); break;
                case 3: searchDonors(); break;
                case 4: recordDonation(); break;
                case 5: viewInventory(); break;
                case 6: removeExpired(); break;
                case 7: createRequest(); break;
                case 8: processPending(); break;
                case 9: viewRequests(); break;
                case 0:
                    running = false;
                    System.out.println("Data saved. Goodbye!");
                    break;
                default: break;
            }
        }
    }

    private static void printMenu() {
        System.out.println("\n--------------- MAIN MENU ---------------");
        System.out.println("1. Register new donor");
        System.out.println("2. View all donors");
        System.out.println("3. Search donors by blood group");
        System.out.println("4. Record a blood donation");
        System.out.println("5. View blood inventory");
        System.out.println("6. Remove expired blood units");
        System.out.println("7. Create blood request");
        System.out.println("8. Process pending requests");
        System.out.println("9. View all requests");
        System.out.println("0. Exit");
        System.out.println("-----------------------------------------");
    }

    private static void registerDonor() {
        System.out.println("--- Register Donor ---");
        String name = readNonEmpty("Name: ");
        int age = readInt("Age: ", 1, 120);
        String gender = readNonEmpty("Gender (M/F/O): ");
        BloodGroup group = readGroup("Blood group");
        String phone = readNonEmpty("Phone: ");
        Donor d = BANK.registerDonor(name, age, gender, group, phone);
        System.out.println("Donor registered successfully with ID " + d.id + ".");
        if (age < MIN_DONOR_AGE || age > MAX_DONOR_AGE) {
            System.out.println("Note: this donor is not eligible to donate (age must be "
                    + MIN_DONOR_AGE + "-" + MAX_DONOR_AGE + ").");
        }
    }

    private static void viewDonors() {
        List<Donor> donors = BANK.getDonors();
        if (donors.isEmpty()) {
            System.out.println("No donors registered yet.");
            return;
        }
        printDonors(donors);
    }

    private static void searchDonors() {
        BloodGroup group = readGroup("Blood group to search");
        List<Donor> result = BANK.searchDonors(group);
        if (result.isEmpty()) {
            System.out.println("No donors found with blood group " + group + ".");
        } else {
            printDonors(result);
        }
    }

    private static void printDonors(List<Donor> donors) {
        LocalDate today = LocalDate.now();
        System.out.printf("%-5s %-20s %-4s %-7s %-6s %-14s %-12s %-9s%n",
                "ID", "Name", "Age", "Gender", "Group", "Phone", "Last Donated", "Eligible");
        System.out.println("-".repeat(86));
        for (Donor d : donors) {
            System.out.printf("%-5d %-20s %-4d %-7s %-6s %-14s %-12s %-9s%n",
                    d.id, trim(d.name, 20), d.age, d.gender, d.group, d.phone,
                    d.lastDonation == null ? "Never" : d.lastDonation.toString(),
                    d.isEligible(today) ? "Yes" : "No");
        }
    }

    private static void recordDonation() {
        if (BANK.getDonors().isEmpty()) {
            System.out.println("No donors registered yet. Register a donor first.");
            return;
        }
        int id = readInt("Donor ID: ", 1, Integer.MAX_VALUE);
        Donor d = BANK.findDonor(id);
        if (d == null) {
            System.out.println("No donor found with ID " + id + ".");
            return;
        }
        String problem = BANK.recordDonation(d);
        if (problem == null) {
            System.out.println("Donation recorded: 1 unit of " + d.group + " added to inventory.");
            System.out.println("Expiry date: " + LocalDate.now().plusDays(SHELF_LIFE_DAYS));
        } else {
            System.out.println("Donation refused: " + problem);
        }
    }

    private static void viewInventory() {
        Map<BloodGroup, List<BloodUnit>> inv = BANK.inventoryByGroup();
        System.out.printf("%-8s %-8s %-18s%n", "Group", "Units", "Nearest expiry");
        System.out.println("-".repeat(36));
        int total = 0;
        for (BloodGroup g : BloodGroup.values()) {
            List<BloodUnit> list = inv.get(g);
            total += list.size();
            String nearest = list.stream().map(u -> u.expiry).min(LocalDate::compareTo)
                    .map(LocalDate::toString).orElse("-");
            System.out.printf("%-8s %-8d %-18s%n", g, list.size(), nearest);
        }
        System.out.println("-".repeat(36));
        System.out.println("Total units available: " + total);
    }

    private static void removeExpired() {
        int removed = BANK.removeExpired();
        System.out.println(removed == 0 ? "No expired units found."
                : removed + " expired unit(s) removed from inventory.");
    }

    private static void createRequest() {
        System.out.println("--- Blood Request ---");
        String patient = readNonEmpty("Patient name: ");
        String hospital = readNonEmpty("Hospital: ");
        BloodGroup group = readGroup("Blood group needed");
        int count = readInt("Units required (1-10): ", 1, 10);
        BloodRequest r = BANK.createRequest(patient, hospital, group, count);
        if ("FULFILLED".equals(r.status)) {
            System.out.println("Request #" + r.id + " fulfilled. " + count + " unit(s) issued.");
        } else {
            System.out.println("Request #" + r.id + " saved as PENDING (not enough compatible stock).");
        }
    }

    private static void processPending() {
        int n = BANK.processPending();
        System.out.println(n == 0 ? "No pending requests could be fulfilled."
                : n + " pending request(s) fulfilled.");
    }

    private static void viewRequests() {
        List<BloodRequest> list = BANK.getRequests();
        if (list.isEmpty()) {
            System.out.println("No requests yet.");
            return;
        }
        System.out.printf("%-5s %-18s %-18s %-6s %-6s %-12s %-10s%n",
                "ID", "Patient", "Hospital", "Group", "Units", "Date", "Status");
        System.out.println("-".repeat(80));
        for (BloodRequest r : list) {
            System.out.printf("%-5d %-18s %-18s %-6s %-6d %-12s %-10s%n",
                    r.id, trim(r.patient, 18), trim(r.hospital, 18), r.group, r.units, r.date, r.status);
        }
    }

    // ------------------------------------------------------------------
    // Input helpers
    // ------------------------------------------------------------------
    private static String readNonEmpty(String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = SC.nextLine().trim();
            if (!s.isEmpty()) return s;
            System.out.println("Input cannot be empty.");
        }
    }

    private static int readInt(String prompt, int min, int max) {
        while (true) {
            System.out.print(prompt);
            try {
                int v = Integer.parseInt(SC.nextLine().trim());
                if (v >= min && v <= max) return v;
            } catch (NumberFormatException ignored) {
                // fall through to the error message
            }
            System.out.println("Please enter a number between " + min + " and " + max + ".");
        }
    }

    private static BloodGroup readGroup(String label) {
        while (true) {
            System.out.print(label + " (A+, A-, B+, B-, AB+, AB-, O+, O-): ");
            BloodGroup g = BloodGroup.fromLabel(SC.nextLine());
            if (g != null) return g;
            System.out.println("Invalid blood group.");
        }
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "~";
    }
}
