# Forge Gym Management System

A full-stack, zero-framework gym management platform built with **Java 21**, **PostgreSQL**, **JDBC**, and **Vanilla HTML/CSS/JavaScript**. Designed with a unified dark fitness-inspired UI, Forge provides two dedicated, role-isolated workspaces:

1. **Admin Portal** — Complete club operations: member directory, membership plans & subscriptions, upgrade request approvals, attendance check-ins, payment tracking, announcements, and analytics.
2. **Member Portal** — Personalized self-service portal: live membership & validity tracking, attendance charts, online renewals and approved tier upgrades via Razorpay Test Mode, downloadable payment receipts, persistent notifications, and profile/password management.

---

## Tech Stack

| Layer | Technology | Notes |
| :--- | :--- | :--- |
| **Backend** | Java 21 (`com.sun.net.httpserver.HttpServer`) | Pure JDK standard library — no Spring Boot, Hibernate, Maven, or Gradle |
| **Database** | PostgreSQL 14+ / 18+ | Normalized relational schema with foreign keys, check constraints, triggers, and generated columns |
| **Data Access** | JDBC (`java.sql.*`) + PostgreSQL Driver (`42.7.4`) | Parameterized `PreparedStatement` queries with transactional row locking (`SELECT ... FOR UPDATE`) |
| **Frontend** | HTML5, CSS3, Vanilla JavaScript (ES6+) | Responsive dark theme, zero frontend frameworks or build bundlers |
| **Payments** | Razorpay Standard Checkout + REST API (**Test Mode**) | Server-side order creation, HMAC-SHA256 signature verification, and capture status checks |
| **Security** | PBKDF2WithHmacSHA256 (210,000 iterations) | Random 16-byte salts, timing-safe comparisons, `HttpOnly` + `SameSite=Strict` expiring session cookies, RBAC |

---

## Key Features

### Role-Based Authentication & Security
- **Two Strict Roles (`ADMIN` and `MEMBER`)**: Unified login at `/` automatically routes Admins to the Admin Portal and Members to `/member/member-dashboard.html`.
- **Self-Service Registration**: Supports first-time Admin setup, Manager sign-up, and Member sign-up (automatically linking to an existing unlinked member record by phone/email if previously added by an Admin).
- **Strict Data Isolation**: Backend enforces role authorization (`403 Forbidden` across portals) and always derives `member_id` from the server-side session rather than trusting client-supplied IDs.

### Admin Portal (`/`)
- **Operations Dashboard**: Live KPIs (total members, active/expired memberships, total revenue, today's check-ins, pending upgrade requests) plus recent registrations and payments.
- **Member Directory**: Create, edit, search (ID/name/phone), inspect full history modals, and safely delete members without financial history.
- **Membership Plans**: Create, edit, and archive plans with duration, pricing, membership tier (`Basic`, `Standard`, `Premium`, `Elite`), and facility benefits. Launching a new plan automatically notifies members.
- **Memberships & Upgrade Approvals**: Assign plans with automatic expiry calculation and review (`Approve` / `Reject` with notes) member tier upgrade requests.
- **Payments & Attendance**: Record Cash, UPI, Card, or Razorpay payments; filter by search/date range; mark pending offline dues paid; record daily check-ins (database uniqueness constraint prevents duplicate same-day attendance).
- **Reports & Broadcasts**: Monthly revenue bar chart, 30-day attendance volume, upcoming/expired renewal alerts, monthly payment ledger, and club-wide member announcements.

### Member Portal (`/member/`)
- **Personalized Dashboard (`member-dashboard.html`)**: Current plan overview with remaining-days progress bar, dynamic renewal reminders (30-day, 7-day, and expired alerts), monthly attendance bar chart, payment summary, active upgrade status banner, and quick-action links.
- **Membership Management (`membership.html`)**: Pricing cards displaying plan tiers and benefits, online renewal via Razorpay Test Mode, upgrade request submission & status tracking, and full subscription history.
- **Payments & Receipts (`payments.html`)**: Pending fee alerts with **Pay Now**, transaction history filters (`All`, `Successful`, `Pending`, `Failed`), Razorpay Payment IDs, duplicate-payment prevention, and one-click downloadable branded **Forge Gym** receipts.
- **Notifications**: Header notification bell with live unread badge backed by PostgreSQL for expiry reminders, renewals, failed/pending payments, upgrade decisions, new plans, and gym announcements.
- **Profile & Security (`profile.html`)**: Update permitted personal details, view read-only membership/account metadata, and securely change password.

---

## Project Structure

```text
Gym_Management_System/
├── src/
│   ├── GymManagementServer.java      # HTTP server, auth/RBAC, Admin APIs, schema bootstrap, Razorpay helpers
│   └── MemberPortalHandler.java      # Member Portal APIs, upgrade workflow, notifications, receipts
├── web/
│   ├── index.html                    # Unified Login/Signup screen & Admin Portal SPA
│   ├── css/styles.css                # Dark-themed Admin Portal & Auth stylesheet
│   ├── js/app.js                     # Admin Portal frontend controller
│   └── member/                       # Dedicated Member Portal
│       ├── member-dashboard.html     # Personalized member dashboard
│       ├── membership.html           # Plans, renewals, upgrade requests & subscription history
│       ├── payments.html             # Transaction history, pending dues & receipts
│       ├── profile.html              # Personal profile & password management
│       ├── css/member.css            # Member Portal dark fitness stylesheet
│       └── js/                       # member-common.js, member-dashboard.js, member-membership.js,
│                                     # member-payments.js, member-profile.js
├── database.sql                      # Complete idempotent PostgreSQL schema & starter plans
├── config.example.properties         # Configuration template (copy to config/app.properties)
├── lib/postgresql-42.7.4.jar         # PostgreSQL JDBC Driver
├── compile.bat / run.bat             # Windows compile and launch scripts
├── setup-database.bat                # PostgreSQL schema initialization script
└── .vscode/                          # VS Code tasks, launch config, and Java 21 settings
```

---

## Database Schema Overview

PostgreSQL database `gym_management` consists of 10 relational tables defined in [`database.sql`](database.sql):

- `admin` — Administrator/manager credentials (`PBKDF2` hashes)
- `members` — Core member demographic and contact records (with `updated_at` trigger)
- `member_accounts` — 1-to-1 portal login accounts for `MEMBER` role linked to `members`
- `membership_plans` — Club plans with `duration_months`, `price`, `membership_type`, `benefits`, and soft-archive flag
- `member_memberships` — Member subscription history (`ACTIVE`, `EXPIRED`, `UPCOMING`, `CANCELLED`)
- `membership_upgrade_requests` — Member upgrade approval workflow (`PENDING`, `APPROVED`, `REJECTED`, `COMPLETED`, `CANCELLED`)
- `payments` — Payment ledger (`CASH`, `UPI`, `CARD`, `RAZORPAY`; statuses `PAID`, `PENDING`, `FAILED`, `REFUNDED`)
- `razorpay_transactions` — Server-side Razorpay order/payment/signature tracking and upgrade/renewal target binding
- `attendance` — Daily check-ins with stored generated `attendance_date` and unique constraint `(member_id, attendance_date)`
- `notifications` — Persistent member notifications with unread tracking and deduplication keys

---

## API Overview

| Group | Endpoints | Access |
| :--- | :--- | :--- |
| **Auth** | `GET /api/setup/status`, `POST /api/setup`, `POST /api/signup`, `POST /api/member/signup`, `POST /api/login`, `POST /api/logout`, `GET /api/me` | Public / Session |
| **Admin Portal** | `GET /api/dashboard`, `CRUD /api/members`, `CRUD /api/plans`, `GET/POST /api/memberships`, `GET/PUT /api/upgrade-requests`, `GET/POST/PUT /api/payments`, `POST /api/razorpay/order`, `POST /api/razorpay/verify`, `GET/POST /api/attendance`, `GET /api/reports`, `POST /api/announcements` | `ADMIN` only |
| **Member Portal** | `GET /api/member/dashboard`, `GET /api/member/plans`, `GET /api/member/subscriptions`, `GET/POST/DELETE /api/member/upgrade-requests`, `GET /api/member/payments`, `GET /api/member/payments/receipt`, `POST /api/member/razorpay/order`, `POST /api/member/razorpay/verify`, `POST /api/member/razorpay/fail`, `GET/PUT /api/member/notifications`, `GET/PUT /api/member/profile`, `PUT /api/member/password` | `MEMBER` only (scoped to session) |

---

## Hosting & Deployment Note

Because Forge Gym Management System runs a **Java 21 backend server** connected to a **PostgreSQL database**, it **cannot be hosted on static-only services like GitHub Pages** (GitHub Pages only serves static HTML/CSS/JS files and cannot run Java or PostgreSQL).

To publish a live URL accessible from any device, deploy the repository to a cloud platform that supports **Java / Docker + PostgreSQL** (such as **Render**, **Railway**, or **Fly.io**) using environment variables for database and Razorpay Test Mode credentials.
