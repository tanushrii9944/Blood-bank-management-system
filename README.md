# Blood Bank Management System (Java)

A simple, console-based **Blood Bank Management System** written in core Java. It helps a blood bank manage donors, track blood stock, and handle hospital/patient blood requests. No external libraries or database are needed. Data is saved automatically to plain text files.

## Features

- **Donor management**: register donors, list all donors, search by blood group
- **Eligibility checks**: donors must be aged 18-65 and wait 56 days between donations
- **Blood inventory**: record donations, view stock per blood group with nearest expiry date
- **Expiry handling**: each unit expires 35 days after collection; expired units can be removed (also done automatically on startup)
- **Blood requests**: create requests for a patient/hospital; blood is issued immediately if stock is available
- **Smart matching**: uses the exact blood group first, then compatible groups (e.g. O- can be given to any group); oldest units are issued first (FIFO)
- **Pending requests**: requests that cannot be met are kept as `PENDING` and can be processed later
- **Persistent storage**: data is saved to a `data/` folder and reloaded on the next run

## Requirements

- Java Development Kit (JDK) **11 or higher**

## How to Run

```bash
# 1. Clone the repository
git clone https://github.com/<your-username>/<your-repo-name>.git
cd <your-repo-name>

# 2. Compile
javac BloodBankManagementSystem.java

# 3. Run
java BloodBankManagementSystem
```

On JDK 11+ you can also run it directly without compiling first:

```bash
java BloodBankManagementSystem.java
```

## Menu Options

| Option | Description |
|--------|-------------|
| 1 | Register new donor |
| 2 | View all donors |
| 3 | Search donors by blood group |
| 4 | Record a blood donation |
| 5 | View blood inventory |
| 6 | Remove expired blood units |
| 7 | Create blood request |
| 8 | Process pending requests |
| 9 | View all requests |
| 0 | Exit |

## Sample Run

```
=====================================
     BLOOD BANK MANAGEMENT SYSTEM
=====================================

--------------- MAIN MENU ---------------
1. Register new donor
...
Enter your choice: 1

--- Register Donor ---
Name: Riya
Age: 25
Gender (M/F/O): F
Blood group (A+, A-, B+, B-, AB+, AB-, O+, O-): O-
Phone: 9876543210
Donor registered successfully with ID 1.
```

## Project Structure

```
.
├── BloodBankManagementSystem.java   # Complete application (models, logic, console UI)
├── README.md
└── data/                            # Created automatically at runtime
    ├── donors.txt
    ├── units.txt
    └── requests.txt
```

Inside the source file:

| Class | Purpose |
|-------|---------|
| `BloodGroup` | Enum of the 8 blood groups with donor-compatibility rules |
| `Donor` | Donor details and eligibility check |
| `BloodUnit` | One unit of blood with collection and expiry dates |
| `BloodRequest` | A patient/hospital request and its status |
| `BloodBank` | Business logic and file persistence |
| `BloodBankManagementSystem` | `main` method and console menu |

## Configurable Rules

These constants are at the top of `BloodBankManagementSystem.java`:

```java
MIN_DONOR_AGE = 18;
MAX_DONOR_AGE = 65;
DONATION_GAP_DAYS = 56;
SHELF_LIFE_DAYS = 35;
```

## Tip for GitHub

Add a `.gitignore` file containing the lines below so that runtime data and compiled files are not uploaded:

```
data/
*.class
```

## Possible Improvements

- Replace text files with a database (MySQL / SQLite via JDBC)
- Add a GUI using Swing or JavaFX
- Add login for admin and staff roles
- Split the code into separate files/packages (`model`, `service`, `ui`)
- Add unit tests with JUnit

## Disclaimer

This is an educational project. It is not intended for real medical or clinical use.

## License

Free to use and modify for learning purposes. Add a license file (for example MIT) if you want to set formal terms.
