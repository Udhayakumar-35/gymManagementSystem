import com.sun.net.httpserver.HttpExchange;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Member Portal and Upgrade/Notification API handlers for Forge Gym Management System. */
class MemberPortalHandler {

    static void route(HttpExchange x, String subPath, String method, Map<String, String> q, GymManagementServer.Session session) throws Exception {
        switch (subPath) {
            case "/dashboard" -> { if (method.equals("GET")) dashboard(x, session); else GymManagementServer.badMethod(x); }
            case "/plans" -> { if (method.equals("GET")) plans(x, session); else GymManagementServer.badMethod(x); }
            case "/subscriptions" -> { if (method.equals("GET")) subscriptions(x, session); else GymManagementServer.badMethod(x); }
            case "/upgrade-requests" -> upgradeRequests(x, method, q, session);
            case "/payments" -> { if (method.equals("GET")) payments(x, q, session); else GymManagementServer.badMethod(x); }
            case "/payments/receipt" -> { if (method.equals("GET")) receipt(x, q, session); else GymManagementServer.badMethod(x); }
            case "/razorpay/order" -> { if (method.equals("POST")) createRazorpayOrder(x, q, session); else GymManagementServer.badMethod(x); }
            case "/razorpay/verify" -> { if (method.equals("POST")) verifyRazorpayPayment(x, q, session); else GymManagementServer.badMethod(x); }
            case "/razorpay/fail" -> { if (method.equals("POST")) failRazorpayPayment(x, q, session); else GymManagementServer.badMethod(x); }
            case "/notifications" -> notifications(x, method, q, session);
            case "/profile" -> profile(x, method, q, session);
            case "/password" -> { if (method.equals("PUT") || method.equals("POST")) changePassword(x, q, session); else GymManagementServer.badMethod(x); }
            default -> GymManagementServer.json(x, 404, Map.of("error", "Member endpoint not found."));
        }
    }

    /** Compute and persist dynamic expiry notifications without duplicating alerts. */
    static void syncMemberExpiryNotifications(Connection c, long memberId) throws SQLException {
        String sql = "SELECT mm.membership_id, mp.plan_name, mm.start_date, mm.end_date, mm.status "
                + "FROM member_memberships mm JOIN membership_plans mp ON mp.plan_id = mm.plan_id "
                + "WHERE mm.member_id = ? AND mm.status <> 'CANCELLED' "
                + "ORDER BY mm.end_date DESC LIMIT 1";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, memberId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return;
                long membershipId = r.getLong("membership_id");
                String planName = r.getString("plan_name");
                LocalDate endDate = r.getDate("end_date").toLocalDate();
                long daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), endDate);

                if (daysRemaining < 0) {
                    GymManagementServer.createNotification(c, memberId, "EXPIRY_EXPIRED",
                            "Membership Expired",
                            "Your " + planName + " plan expired on " + endDate + ". Renew now to continue gym access.",
                            "expired_" + membershipId);
                } else if (daysRemaining <= 7) {
                    String dayWord = daysRemaining == 0 ? "today" : (daysRemaining == 1 ? "in 1 day" : "in " + daysRemaining + " days");
                    GymManagementServer.createNotification(c, memberId, "EXPIRY_7D",
                            "Membership Expiring Soon",
                            "Your " + planName + " plan expires " + dayWord + " (" + endDate + "). Renew soon to avoid interruption.",
                            "expiry_7d_" + membershipId);
                } else if (daysRemaining <= 30) {
                    GymManagementServer.createNotification(c, memberId, "EXPIRY_30D",
                            "Membership Renewal Reminder",
                            "Your " + planName + " plan will expire in " + daysRemaining + " days on " + endDate + ".",
                            "expiry_30d_" + membershipId);
                }
            }
        }
    }

    private static Map<String, Object> loadCurrentMembership(Connection c, long memberId) throws SQLException {
        String sql = "SELECT mm.membership_id, mm.plan_id, mp.plan_name, mp.membership_type, mp.duration_months, mp.price, "
                + "mm.start_date, mm.end_date, "
                + "CASE WHEN mm.status='CANCELLED' THEN 'CANCELLED' "
                + "WHEN mm.end_date < CURRENT_DATE THEN 'EXPIRED' "
                + "WHEN mm.start_date <= CURRENT_DATE THEN 'ACTIVE' "
                + "ELSE 'UPCOMING' END AS computed_status "
                + "FROM member_memberships mm JOIN membership_plans mp ON mp.plan_id = mm.plan_id "
                + "WHERE mm.member_id = ? AND mm.status <> 'CANCELLED' "
                + "ORDER BY CASE WHEN mm.start_date <= CURRENT_DATE AND mm.end_date >= CURRENT_DATE THEN 0 "
                + "WHEN mm.start_date > CURRENT_DATE THEN 1 ELSE 2 END, mm.end_date DESC LIMIT 1";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, memberId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                LocalDate startDate = r.getDate("start_date").toLocalDate();
                LocalDate endDate = r.getDate("end_date").toLocalDate();
                LocalDate today = LocalDate.now();
                String status = r.getString("computed_status");
                long daysRemaining = ChronoUnit.DAYS.between(today, endDate);
                long totalDays = Math.max(1, ChronoUnit.DAYS.between(startDate, endDate) + 1);

                // Check if member already has a future renewal beyond this end_date
                LocalDate maxEndDate = endDate;
                try (PreparedStatement mp = c.prepareStatement(
                        "SELECT MAX(end_date) FROM member_memberships WHERE member_id=? AND status<>'CANCELLED'")) {
                    mp.setLong(1, memberId);
                    try (ResultSet mr = mp.executeQuery()) {
                        if (mr.next() && mr.getDate(1) != null) maxEndDate = mr.getDate(1).toLocalDate();
                    }
                }
                long effectiveDaysRemaining = ChronoUnit.DAYS.between(today, maxEndDate);
                String renewalStatus;
                if (effectiveDaysRemaining < 0) renewalStatus = "RENEWAL REQUIRED";
                else if (maxEndDate.isAfter(endDate)) renewalStatus = "RENEWED AHEAD";
                else if (effectiveDaysRemaining <= 7) renewalStatus = "EXPIRING SOON";
                else if (effectiveDaysRemaining <= 30) renewalStatus = "RENEWAL DUE";
                else renewalStatus = "UP TO DATE";

                m.put("membership_id", r.getLong("membership_id"));
                m.put("plan_id", r.getLong("plan_id"));
                m.put("plan_name", r.getString("plan_name"));
                m.put("membership_type", r.getString("membership_type"));
                m.put("duration_months", r.getInt("duration_months"));
                m.put("price", r.getBigDecimal("price"));
                m.put("start_date", startDate.toString());
                m.put("end_date", endDate.toString());
                m.put("effective_end_date", maxEndDate.toString());
                m.put("status", status);
                m.put("days_remaining", Math.max(0, daysRemaining));
                m.put("raw_days_remaining", daysRemaining);
                m.put("effective_days_remaining", effectiveDaysRemaining);
                m.put("total_days", totalDays);
                m.put("renewal_status", renewalStatus);
                return m;
            }
        }
    }

    private static Map<String, Object> buildRenewalReminder(Map<String, Object> currentMembership, boolean hasPendingPayment) {
        Map<String, Object> rem = new LinkedHashMap<>();
        if (currentMembership == null) {
            rem.put("level", hasPendingPayment ? "PENDING" : "NONE");
            rem.put("title", hasPendingPayment ? "Pending Membership Payment" : "No Active Membership");
            rem.put("message", hasPendingPayment
                    ? "You have a pending membership payment. Complete payment to activate your plan."
                    : "Explore available gym plans to activate your membership at Forge Gym.");
            rem.put("daysRemaining", null);
            return rem;
        }
        long effDays = ((Number) currentMembership.get("effective_days_remaining")).longValue();
        String planName = String.valueOf(currentMembership.get("plan_name"));
        String endDate = String.valueOf(currentMembership.get("effective_end_date"));
        if (effDays < 0) {
            rem.put("level", "EXPIRED");
            rem.put("title", "Membership Renewal Required");
            rem.put("message", "Your " + planName + " membership expired on " + endDate + ". Renew now to regain uninterrupted gym access.");
            rem.put("daysRemaining", 0);
        } else if (effDays <= 7) {
            rem.put("level", "URGENT");
            rem.put("title", "Membership Expiring Soon");
            rem.put("message", "Only " + effDays + (effDays == 1 ? " day" : " days") + " left on your " + planName + " plan (ends " + endDate + "). Renew today!");
            rem.put("daysRemaining", effDays);
        } else if (effDays <= 30) {
            rem.put("level", "UPCOMING");
            rem.put("title", "Membership Renewal Reminder");
            rem.put("message", "Your " + planName + " membership expires in " + effDays + " days on " + endDate + ". Renew early to stay on track.");
            rem.put("daysRemaining", effDays);
        } else {
            rem.put("level", "OK");
            rem.put("title", "Membership Active");
            rem.put("message", "Your " + planName + " membership is in good standing through " + endDate + ".");
            rem.put("daysRemaining", effDays);
        }
        return rem;
    }

    private static void dashboard(HttpExchange x, GymManagementServer.Session session) throws Exception {
        long memberId = session.memberId();
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection c = GymManagementServer.db()) {
            syncMemberExpiryNotifications(c, memberId);

            // Member basic info
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT m.member_id, m.full_name, m.email, m.phone, m.joining_date, a.username "
                            + "FROM members m JOIN member_accounts a ON a.member_id = m.member_id WHERE m.member_id = ?")) {
                p.setLong(1, memberId);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) { GymManagementServer.json(x, 404, Map.of("error", "Member profile not found.")); return; }
                    Map<String, Object> mem = new LinkedHashMap<>();
                    mem.put("member_id", r.getLong("member_id"));
                    mem.put("full_name", r.getString("full_name"));
                    mem.put("email", r.getString("email"));
                    mem.put("phone", r.getString("phone"));
                    mem.put("joining_date", r.getDate("joining_date").toLocalDate().toString());
                    mem.put("username", r.getString("username"));
                    out.put("member", mem);
                }
            }

            // Membership overview
            Map<String, Object> currentMembership = loadCurrentMembership(c, memberId);

            // Pending fees check
            long pendingCount = ((Number) GymManagementServer.scalarForMember(c,
                    "SELECT COUNT(*) FROM payments WHERE member_id=? AND status='PENDING'", memberId)).longValue();
            Object pendingTotal = GymManagementServer.scalarForMember(c,
                    "SELECT COALESCE(SUM(amount), 0) FROM payments WHERE member_id=? AND status='PENDING'", memberId);

            // If no active membership but pending payment exists, report status as PENDING
            if (currentMembership == null && pendingCount > 0) {
                Map<String, Object> pendingPlaceholder = new LinkedHashMap<>();
                pendingPlaceholder.put("plan_name", "Pending Activation");
                pendingPlaceholder.put("status", "PENDING");
                pendingPlaceholder.put("days_remaining", 0);
                pendingPlaceholder.put("renewal_status", "PAYMENT PENDING");
                out.put("currentMembership", pendingPlaceholder);
            } else {
                out.put("currentMembership", currentMembership);
            }
            out.put("renewalReminder", buildRenewalReminder(currentMembership, pendingCount > 0));

            // Attendance overview
            Map<String, Object> att = new LinkedHashMap<>();
            att.put("currentMonthCount", GymManagementServer.scalarForMember(c,
                    "SELECT COUNT(*) FROM attendance WHERE member_id=? AND DATE_TRUNC('month', attendance_date)=DATE_TRUNC('month', CURRENT_DATE)", memberId));
            att.put("totalCount", GymManagementServer.scalarForMember(c,
                    "SELECT COUNT(*) FROM attendance WHERE member_id=?", memberId));
            Object lastCheckIn = null;
            try (PreparedStatement p = c.prepareStatement("SELECT MAX(attendance_date) FROM attendance WHERE member_id=?")) {
                p.setLong(1, memberId);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next() && r.getDate(1) != null) lastCheckIn = r.getDate(1).toLocalDate().toString();
                }
            }
            att.put("lastCheckInDate", lastCheckIn);
            att.put("monthlyTrend", GymManagementServer.rowsForMember(c,
                    "SELECT TO_CHAR(attendance_date, 'YYYY-MM') AS month, COUNT(*) AS visits "
                            + "FROM attendance WHERE member_id=? AND attendance_date >= (CURRENT_DATE - INTERVAL '5 MONTH') "
                            + "GROUP BY TO_CHAR(attendance_date, 'YYYY-MM') ORDER BY month", memberId));
            out.put("attendance", att);

            // Payment overview
            Map<String, Object> pay = new LinkedHashMap<>();
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT payment_id, amount, payment_date, status, payment_method FROM payments "
                            + "WHERE member_id=? ORDER BY payment_date DESC LIMIT 1")) {
                p.setLong(1, memberId);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        pay.put("lastPaymentId", r.getLong("payment_id"));
                        pay.put("lastPaymentAmount", r.getBigDecimal("amount"));
                        Timestamp ts = r.getTimestamp("payment_date");
                        pay.put("lastPaymentDate", ts != null ? ts.toLocalDateTime().toString() : null);
                        pay.put("lastPaymentStatus", r.getString("status"));
                        pay.put("lastPaymentMethod", r.getString("payment_method"));
                    } else {
                        pay.put("lastPaymentId", null);
                        pay.put("lastPaymentAmount", null);
                        pay.put("lastPaymentDate", null);
                        pay.put("lastPaymentStatus", null);
                        pay.put("lastPaymentMethod", null);
                    }
                }
            }
            pay.put("pendingCount", pendingCount);
            pay.put("pendingTotal", pendingTotal);
            pay.put("totalPaid", GymManagementServer.scalarForMember(c,
                    "SELECT COALESCE(SUM(amount), 0) FROM payments WHERE member_id=? AND status='PAID'", memberId));
            out.put("paymentOverview", pay);

            // Active upgrade request (PENDING or APPROVED)
            List<Map<String, Object>> activeReqs = GymManagementServer.rowsForMember(c,
                    "SELECT r.request_id, r.requested_plan_id, rp.plan_name AS requested_plan_name, "
                            + "rp.duration_months AS requested_duration_months, rp.price AS requested_plan_price, "
                            + "cp.plan_name AS current_plan_name, r.price_difference, r.status, r.admin_notes, r.created_at "
                            + "FROM membership_upgrade_requests r "
                            + "JOIN membership_plans rp ON rp.plan_id = r.requested_plan_id "
                            + "LEFT JOIN membership_plans cp ON cp.plan_id = r.current_plan_id "
                            + "WHERE r.member_id=? AND r.status IN ('PENDING','APPROVED') "
                            + "ORDER BY r.created_at DESC LIMIT 1", memberId);
            out.put("activeUpgradeRequest", activeReqs.isEmpty() ? null : activeReqs.get(0));

            out.put("unreadNotifications", GymManagementServer.scalarForMember(c,
                    "SELECT COUNT(*) FROM notifications WHERE member_id=? AND is_read=FALSE", memberId));
        }
        GymManagementServer.json(x, 200, out);
    }

    private static void plans(HttpExchange x, GymManagementServer.Session session) throws Exception {
        long memberId = session.memberId();
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection c = GymManagementServer.db()) {
            out.put("plans", GymManagementServer.rows(c,
                    "SELECT plan_id, plan_name, duration_months, price, description, membership_type, benefits, active "
                            + "FROM membership_plans WHERE active=TRUE ORDER BY duration_months, price"));
            out.put("currentMembership", loadCurrentMembership(c, memberId));
        }
        GymManagementServer.json(x, 200, out);
    }

    private static void subscriptions(HttpExchange x, GymManagementServer.Session session) throws Exception {
        long memberId = session.memberId();
        String sql = "SELECT mm.membership_id, mm.plan_id, mp.plan_name, mp.membership_type, mp.duration_months, mp.price AS plan_price, "
                + "mm.start_date, mm.end_date, mm.created_at AS renewal_date, "
                + "CASE WHEN mm.status='CANCELLED' THEN 'CANCELLED' "
                + "WHEN mm.end_date < CURRENT_DATE THEN 'EXPIRED' "
                + "WHEN mm.start_date <= CURRENT_DATE THEN 'ACTIVE' "
                + "ELSE 'UPCOMING' END AS status, "
                + "COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.membership_id = mm.membership_id AND p.status='PAID'), mp.price) AS amount_paid "
                + "FROM member_memberships mm JOIN membership_plans mp ON mp.plan_id = mm.plan_id "
                + "WHERE mm.member_id = ? ORDER BY mm.start_date DESC, mm.membership_id DESC";
        try (Connection c = GymManagementServer.db()) {
            GymManagementServer.json(x, 200, GymManagementServer.rowsForMember(c, sql, memberId));
        }
    }

    private static void upgradeRequests(HttpExchange x, String method, Map<String, String> q, GymManagementServer.Session session) throws Exception {
        long memberId = session.memberId();
        if (method.equals("GET")) {
            String sql = "SELECT r.request_id, r.current_plan_id, cp.plan_name AS current_plan_name, "
                    + "r.requested_plan_id, rp.plan_name AS requested_plan_name, rp.membership_type AS requested_membership_type, "
                    + "rp.duration_months AS requested_duration_months, rp.price AS requested_plan_price, "
                    + "r.price_difference, r.reason, r.status, r.admin_notes, r.reviewed_by, r.reviewed_at, r.created_at "
                    + "FROM membership_upgrade_requests r "
                    + "JOIN membership_plans rp ON rp.plan_id = r.requested_plan_id "
                    + "LEFT JOIN membership_plans cp ON cp.plan_id = r.current_plan_id "
                    + "WHERE r.member_id = ? ORDER BY r.created_at DESC";
            try (Connection c = GymManagementServer.db()) {
                GymManagementServer.json(x, 200, GymManagementServer.rowsForMember(c, sql, memberId));
            }
        } else if (method.equals("POST")) {
            long requestedPlanId = GymManagementServer.number(q, "requested_plan_id");
            String reason = q.getOrDefault("reason", "").trim();
            if (reason.length() > 500) throw new IllegalArgumentException("Reason must be 500 characters or fewer.");

            try (Connection c = GymManagementServer.db()) {
                c.setAutoCommit(false);
                try {
                    // Prevent duplicate open upgrade requests
                    try (PreparedStatement chk = c.prepareStatement(
                            "SELECT COUNT(*) FROM membership_upgrade_requests WHERE member_id=? AND status IN ('PENDING','APPROVED')")) {
                        chk.setLong(1, memberId);
                        try (ResultSet r = chk.executeQuery()) {
                            r.next();
                            if (r.getInt(1) > 0) {
                                c.rollback();
                                GymManagementServer.json(x, 409, Map.of("error", "You already have an active upgrade request. Wait for review or complete payment first."));
                                return;
                            }
                        }
                    }

                    // Validate requested plan
                    String requestedPlanName;
                    BigDecimal requestedPrice;
                    try (PreparedStatement p = c.prepareStatement("SELECT plan_name, price FROM membership_plans WHERE plan_id=? AND active=TRUE")) {
                        p.setLong(1, requestedPlanId);
                        try (ResultSet r = p.executeQuery()) {
                            if (!r.next()) { c.rollback(); throw new IllegalArgumentException("Choose a valid active plan to upgrade to."); }
                            requestedPlanName = r.getString("plan_name");
                            requestedPrice = r.getBigDecimal("price");
                        }
                    }

                    Map<String, Object> current = loadCurrentMembership(c, memberId);
                    Long currentMembershipId = null;
                    Long currentPlanId = null;
                    BigDecimal payable = requestedPrice;

                    if (current != null && ("ACTIVE".equals(current.get("status")) || "UPCOMING".equals(current.get("status")))) {
                        currentMembershipId = (Long) current.get("membership_id");
                        currentPlanId = (Long) current.get("plan_id");
                        if (currentPlanId == requestedPlanId) {
                            c.rollback();
                            throw new IllegalArgumentException("You are already subscribed to this plan. Use Renew instead.");
                        }
                        BigDecimal currentPrice = (BigDecimal) current.get("price");
                        BigDecimal diff = requestedPrice.subtract(currentPrice);
                        if (diff.compareTo(BigDecimal.ONE) >= 0) {
                            payable = diff;
                        } else {
                            payable = requestedPrice;
                        }
                    }

                    long requestId;
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO membership_upgrade_requests(member_id, current_membership_id, current_plan_id, requested_plan_id, price_difference, reason, status) "
                                    + "VALUES(?,?,?,?,?,?,'PENDING')", Statement.RETURN_GENERATED_KEYS)) {
                        ins.setLong(1, memberId);
                        if (currentMembershipId == null) ins.setNull(2, Types.BIGINT); else ins.setLong(2, currentMembershipId);
                        if (currentPlanId == null) ins.setNull(3, Types.BIGINT); else ins.setLong(3, currentPlanId);
                        ins.setLong(4, requestedPlanId);
                        ins.setBigDecimal(5, payable.setScale(2, RoundingMode.HALF_UP));
                        GymManagementServer.setNullable(ins, 6, reason);
                        ins.executeUpdate();
                        requestId = GymManagementServer.generated(ins);
                    }

                    GymManagementServer.createNotification(c, memberId, "UPGRADE_SUBMITTED",
                            "Upgrade Request Submitted",
                            "Your request to upgrade to the " + requestedPlanName + " plan has been submitted for admin approval.",
                            null);
                    c.commit();
                    GymManagementServer.json(x, 201, Map.of("requestId", requestId, "status", "PENDING", "payableAmount", payable));
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
            }
        } else if (method.equals("DELETE")) {
            long requestId = GymManagementServer.number(q, "id");
            try (Connection c = GymManagementServer.db();
                 PreparedStatement p = c.prepareStatement(
                         "UPDATE membership_upgrade_requests SET status='CANCELLED' WHERE request_id=? AND member_id=? AND status='PENDING'")) {
                p.setLong(1, requestId);
                p.setLong(2, memberId);
                if (p.executeUpdate() == 0) {
                    GymManagementServer.json(x, 404, Map.of("error", "Pending upgrade request not found."));
                    return;
                }
                GymManagementServer.json(x, 200, Map.of("ok", true));
            }
        } else {
            GymManagementServer.badMethod(x);
        }
    }

    private static void payments(HttpExchange x, Map<String, String> q, GymManagementServer.Session session) throws Exception {
        long memberId = session.memberId();
        String statusFilter = q.getOrDefault("status", "ALL").trim().toUpperCase(Locale.ROOT);
        String dbStatus = switch (statusFilter) {
            case "SUCCESS", "PAID" -> "PAID";
            case "FAILED" -> "FAILED";
            case "PENDING" -> "PENDING";
            case "REFUNDED" -> "REFUNDED";
            default -> "ALL";
        };
        String sql = "SELECT p.payment_id, p.membership_id, p.amount, p.payment_method, p.payment_date, p.status, p.notes, "
                + "COALESCE(mp_target.plan_name, mp_mem.plan_name, 'Gym Membership') AS plan_name, "
                + "t.order_id AS razorpay_order_id, t.gateway_payment_id AS razorpay_payment_id, t.purpose, t.upgrade_request_id, t.failure_reason "
                + "FROM payments p "
                + "LEFT JOIN razorpay_transactions t ON t.payment_id = p.payment_id "
                + "LEFT JOIN membership_plans mp_target ON mp_target.plan_id = t.target_plan_id "
                + "LEFT JOIN member_memberships mm ON mm.membership_id = p.membership_id "
                + "LEFT JOIN membership_plans mp_mem ON mp_mem.plan_id = mm.plan_id "
                + "WHERE p.member_id = ? AND (? = 'ALL' OR p.status = ?) "
                + "ORDER BY p.payment_date DESC, p.payment_id DESC";
        try (Connection c = GymManagementServer.db(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, memberId);
            p.setString(2, dbStatus);
            p.setString(3, dbStatus);
            try (ResultSet r = p.executeQuery()) {
                GymManagementServer.json(x, 200, GymManagementServer.resultRows(r));
            }
        }
    }

    private static void receipt(HttpExchange x, Map<String, String> q, GymManagementServer.Session session) throws Exception {
        long memberId = session.memberId();
        long paymentId = GymManagementServer.number(q, "id");
        String sql = "SELECT p.payment_id, p.amount, p.payment_method, p.payment_date, p.status, p.notes, "
                + "m.full_name, m.email, m.phone, "
                + "COALESCE(mp_target.plan_name, mp_mem.plan_name, 'Gym Membership') AS plan_name, "
                + "COALESCE(mp_target.duration_months, mp_mem.duration_months) AS duration_months, "
                + "mm.start_date, mm.end_date, "
                + "t.order_id AS razorpay_order_id, t.gateway_payment_id AS razorpay_payment_id "
                + "FROM payments p "
                + "JOIN members m ON m.member_id = p.member_id "
                + "LEFT JOIN razorpay_transactions t ON t.payment_id = p.payment_id "
                + "LEFT JOIN membership_plans mp_target ON mp_target.plan_id = t.target_plan_id "
                + "LEFT JOIN member_memberships mm ON mm.membership_id = p.membership_id "
                + "LEFT JOIN membership_plans mp_mem ON mp_mem.plan_id = mm.plan_id "
                + "WHERE p.payment_id = ? AND p.member_id = ?";
        try (Connection c = GymManagementServer.db(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, paymentId);
            p.setLong(2, memberId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) {
                    GymManagementServer.json(x, 404, Map.of("error", "Transaction receipt not found."));
                    return;
                }
                if (!"PAID".equals(r.getString("status"))) {
                    GymManagementServer.json(x, 400, Map.of("error", "Receipts are only available for successful (paid) transactions."));
                    return;
                }
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("gymName", "Forge Gym");
                rec.put("receiptNumber", "FRG-" + String.format("%06d", r.getLong("payment_id")));
                rec.put("transactionId", r.getLong("payment_id"));
                rec.put("memberName", r.getString("full_name"));
                rec.put("memberEmail", r.getString("email"));
                rec.put("memberPhone", r.getString("phone"));
                rec.put("membershipPlan", r.getString("plan_name"));
                rec.put("durationMonths", r.getObject("duration_months"));
                java.sql.Date sd = r.getDate("start_date"), ed = r.getDate("end_date");
                rec.put("startDate", sd != null ? sd.toLocalDate().toString() : null);
                rec.put("endDate", ed != null ? ed.toLocalDate().toString() : null);
                rec.put("amountPaid", r.getBigDecimal("amount"));
                rec.put("paymentMethod", r.getString("payment_method"));
                Timestamp ts = r.getTimestamp("payment_date");
                rec.put("paymentDate", ts != null ? ts.toLocalDateTime().toString() : null);
                rec.put("paymentStatus", "Success");
                rec.put("razorpayOrderId", r.getString("razorpay_order_id"));
                rec.put("razorpayPaymentId", r.getString("razorpay_payment_id"));
                GymManagementServer.json(x, 200, rec);
            }
        }
    }

    private static void createRazorpayOrder(HttpExchange x, Map<String, String> q, GymManagementServer.Session session) throws Exception {
        long memberId = session.memberId();
        String purpose = q.getOrDefault("purpose", "RENEWAL").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("RENEWAL", "UPGRADE", "PENDING_PAYMENT").contains(purpose)) {
            throw new IllegalArgumentException("Invalid payment purpose.");
        }

        // Validate Razorpay Test Mode credentials upfront before modifying DB
        String keyId = GymManagementServer.razorpayKeyId();

        try (Connection c = GymManagementServer.db()) {
            String memberName, memberEmail, memberPhone;
            try (PreparedStatement p = c.prepareStatement("SELECT full_name, email, phone FROM members WHERE member_id=?")) {
                p.setLong(1, memberId);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw new IllegalArgumentException("Member profile not found.");
                    memberName = r.getString("full_name");
                    memberEmail = r.getString("email");
                    memberPhone = r.getString("phone");
                }
            }
            if (memberEmail == null || memberEmail.isBlank()) memberEmail = session.username() + "@forgegym.local";

            BigDecimal amount;
            Long targetPlanId = null;
            Long upgradeRequestId = null;
            Long existingPaymentId = null;
            String description;

            if ("RENEWAL".equals(purpose)) {
                targetPlanId = GymManagementServer.number(q, "plan_id");
                String planName;
                try (PreparedStatement p = c.prepareStatement("SELECT plan_name, price FROM membership_plans WHERE plan_id=? AND active=TRUE")) {
                    p.setLong(1, targetPlanId);
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next()) throw new IllegalArgumentException("Selected membership plan is not available.");
                        planName = r.getString("plan_name");
                        amount = r.getBigDecimal("price").setScale(2, RoundingMode.HALF_UP);
                    }
                }
                description = "Forge Gym — " + planName + " Renewal";

                // Reuse recent PENDING Razorpay order for the exact same plan to prevent duplicates
                try (PreparedStatement dup = c.prepareStatement(
                        "SELECT p.payment_id, p.amount, t.order_id FROM payments p "
                                + "JOIN razorpay_transactions t ON t.payment_id = p.payment_id "
                                + "WHERE p.member_id=? AND p.status='PENDING' AND t.purpose='RENEWAL' AND t.target_plan_id=? "
                                + "AND p.payment_date >= (CURRENT_TIMESTAMP - INTERVAL '30 MINUTE') "
                                + "ORDER BY p.payment_id DESC LIMIT 1")) {
                    dup.setLong(1, memberId);
                    dup.setLong(2, targetPlanId);
                    try (ResultSet r = dup.executeQuery()) {
                        if (r.next()) {
                            long reusedPaise = r.getBigDecimal("amount").movePointRight(2).longValueExact();
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("keyId", keyId);
                            result.put("orderId", r.getString("order_id"));
                            result.put("paymentId", r.getLong("payment_id"));
                            result.put("amount", reusedPaise);
                            result.put("currency", "INR");
                            result.put("name", memberName);
                            result.put("email", memberEmail);
                            result.put("contact", memberPhone);
                            result.put("description", description);
                            GymManagementServer.json(x, 200, result);
                            return;
                        }
                    }
                }
            } else if ("UPGRADE".equals(purpose)) {
                upgradeRequestId = GymManagementServer.number(q, "upgrade_request_id");
                String planName;
                try (PreparedStatement p = c.prepareStatement(
                        "SELECT r.requested_plan_id, r.price_difference, r.status, mp.plan_name "
                                + "FROM membership_upgrade_requests r JOIN membership_plans mp ON mp.plan_id = r.requested_plan_id "
                                + "WHERE r.request_id=? AND r.member_id=?")) {
                    p.setLong(1, upgradeRequestId);
                    p.setLong(2, memberId);
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next()) throw new IllegalArgumentException("Upgrade request not found.");
                        String st = r.getString("status");
                        if ("COMPLETED".equals(st)) throw new IllegalArgumentException("This upgrade has already been paid and completed.");
                        if (!"APPROVED".equals(st)) throw new IllegalArgumentException("This upgrade request must be approved by an admin before payment.");
                        targetPlanId = r.getLong("requested_plan_id");
                        amount = r.getBigDecimal("price_difference").setScale(2, RoundingMode.HALF_UP);
                        planName = r.getString("plan_name");
                    }
                }
                description = "Forge Gym — Upgrade to " + planName;

                // Reuse existing PENDING Razorpay order for this upgrade request
                try (PreparedStatement dup = c.prepareStatement(
                        "SELECT p.payment_id, p.amount, t.order_id FROM payments p "
                                + "JOIN razorpay_transactions t ON t.payment_id = p.payment_id "
                                + "WHERE p.member_id=? AND p.status='PENDING' AND t.upgrade_request_id=? "
                                + "ORDER BY p.payment_id DESC LIMIT 1")) {
                    dup.setLong(1, memberId);
                    dup.setLong(2, upgradeRequestId);
                    try (ResultSet r = dup.executeQuery()) {
                        if (r.next()) {
                            long reusedPaise = r.getBigDecimal("amount").movePointRight(2).longValueExact();
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("keyId", keyId);
                            result.put("orderId", r.getString("order_id"));
                            result.put("paymentId", r.getLong("payment_id"));
                            result.put("amount", reusedPaise);
                            result.put("currency", "INR");
                            result.put("name", memberName);
                            result.put("email", memberEmail);
                            result.put("contact", memberPhone);
                            result.put("description", description);
                            GymManagementServer.json(x, 200, result);
                            return;
                        }
                    }
                }
            } else {
                // PENDING_PAYMENT
                existingPaymentId = GymManagementServer.number(q, "payment_id");
                try (PreparedStatement p = c.prepareStatement(
                        "SELECT p.payment_id, p.amount, p.status, p.notes, t.order_id, t.target_plan_id, t.purpose, t.upgrade_request_id "
                                + "FROM payments p LEFT JOIN razorpay_transactions t ON t.payment_id = p.payment_id "
                                + "WHERE p.payment_id=? AND p.member_id=?")) {
                    p.setLong(1, existingPaymentId);
                    p.setLong(2, memberId);
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next()) throw new IllegalArgumentException("Pending payment not found.");
                        if (!"PENDING".equals(r.getString("status")))
                            throw new IllegalArgumentException("This payment is no longer pending.");
                        amount = r.getBigDecimal("amount").setScale(2, RoundingMode.HALF_UP);
                        String existingOrderId = r.getString("order_id");
                        description = r.getString("notes") != null && !r.getString("notes").isBlank()
                                ? r.getString("notes") : "Forge Gym — Pending Fee Payment";
                        if (existingOrderId != null && !existingOrderId.isBlank()) {
                            long reusedPaise = amount.movePointRight(2).longValueExact();
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("keyId", keyId);
                            result.put("orderId", existingOrderId);
                            result.put("paymentId", existingPaymentId);
                            result.put("amount", reusedPaise);
                            result.put("currency", "INR");
                            result.put("name", memberName);
                            result.put("email", memberEmail);
                            result.put("contact", memberPhone);
                            result.put("description", description);
                            GymManagementServer.json(x, 200, result);
                            return;
                        }
                    }
                }
            }

            long paise = amount.movePointRight(2).longValueExact();
            if (paise < 100) throw new IllegalArgumentException("Razorpay payments must be at least ₹1.00.");

            String receipt = "gms-m" + memberId + "-" + UUID.randomUUID().toString().substring(0, 12);
            String body = "{\"amount\":" + paise + ",\"currency\":\"INR\",\"receipt\":\"" + receipt
                    + "\",\"notes\":{\"member_id\":\"" + memberId + "\",\"purpose\":\"" + purpose + "\"}}";
            String orderResponse = GymManagementServer.razorpayRequest("POST", "/orders", body);
            String orderId = GymManagementServer.jsonStringValue(orderResponse, "id");
            if (orderId == null || !orderId.matches("order_[A-Za-z0-9]+")) {
                throw new GymManagementServer.GatewayException(502, "Razorpay returned an invalid order response.");
            }

            long localPaymentId;
            c.setAutoCommit(false);
            try {
                if (existingPaymentId != null) {
                    localPaymentId = existingPaymentId;
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE payments SET payment_method='RAZORPAY' WHERE payment_id=? AND member_id=? AND status='PENDING'")) {
                        upd.setLong(1, localPaymentId);
                        upd.setLong(2, memberId);
                        if (upd.executeUpdate() != 1) throw new IllegalArgumentException("Pending payment could not be locked.");
                    }
                } else {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO payments(member_id, membership_id, amount, payment_method, payment_date, status, notes) "
                                    + "VALUES(?, NULL, ?, 'RAZORPAY', CURRENT_TIMESTAMP, 'PENDING', ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ins.setLong(1, memberId);
                        ins.setBigDecimal(2, amount);
                        ins.setString(3, description);
                        ins.executeUpdate();
                        localPaymentId = GymManagementServer.generated(ins);
                    }
                }
                try (PreparedStatement insTx = c.prepareStatement(
                        "INSERT INTO razorpay_transactions(payment_id, order_id, target_plan_id, purpose, upgrade_request_id) "
                                + "VALUES(?,?,?,?,?)")) {
                    insTx.setLong(1, localPaymentId);
                    insTx.setString(2, orderId);
                    if (targetPlanId == null) insTx.setNull(3, Types.BIGINT); else insTx.setLong(3, targetPlanId);
                    insTx.setString(4, purpose);
                    if (upgradeRequestId == null) insTx.setNull(5, Types.BIGINT); else insTx.setLong(5, upgradeRequestId);
                    insTx.executeUpdate();
                }

                GymManagementServer.createNotification(c, memberId, "PAYMENT_PENDING",
                        "Payment Initiated",
                        "A Razorpay payment of ₹" + amount + " for " + description + " is pending completion.",
                        "pending_pay_" + localPaymentId);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("keyId", keyId);
            result.put("orderId", orderId);
            result.put("paymentId", localPaymentId);
            result.put("amount", paise);
            result.put("currency", "INR");
            result.put("name", memberName);
            result.put("email", memberEmail);
            result.put("contact", memberPhone);
            result.put("description", description);
            GymManagementServer.json(x, 201, result);
        }
    }

    private static void verifyRazorpayPayment(HttpExchange x, Map<String, String> q, GymManagementServer.Session session) throws Exception {
        long memberId = session.memberId();
        String orderFromCheckout = GymManagementServer.required(q, "razorpay_order_id");
        String gatewayPaymentId = GymManagementServer.required(q, "razorpay_payment_id");
        String signature = GymManagementServer.required(q, "razorpay_signature");
        if (!orderFromCheckout.matches("order_[A-Za-z0-9]+") || !gatewayPaymentId.matches("pay_[A-Za-z0-9]+") || !signature.matches("[A-Fa-f0-9]{64}")) {
            throw new IllegalArgumentException("Razorpay returned invalid payment verification details.");
        }

        long localPaymentId;
        BigDecimal expectedAmount;
        String storedOrderId, existingStatus, existingGatewayPayment, purpose;
        Long targetPlanId, upgradeRequestId;

        try (Connection c = GymManagementServer.db();
             PreparedStatement p = c.prepareStatement(
                     "SELECT p.payment_id, p.amount, p.status, t.order_id, t.gateway_payment_id, t.target_plan_id, t.purpose, t.upgrade_request_id "
                             + "FROM payments p JOIN razorpay_transactions t ON t.payment_id = p.payment_id "
                             + "WHERE t.order_id = ? AND p.member_id = ?")) {
            p.setString(1, orderFromCheckout);
            p.setLong(2, memberId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("No matching Razorpay order was found for your account.");
                localPaymentId = r.getLong("payment_id");
                expectedAmount = r.getBigDecimal("amount");
                existingStatus = r.getString("status");
                storedOrderId = r.getString("order_id");
                existingGatewayPayment = r.getString("gateway_payment_id");
                targetPlanId = (Long) r.getObject("target_plan_id");
                purpose = r.getString("purpose");
                upgradeRequestId = (Long) r.getObject("upgrade_request_id");
            }
        }

        // Verify HMAC-SHA256 signature with server-side secret
        String secret = GymManagementServer.razorpayKeySecret();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expectedSignature = mac.doFinal((storedOrderId + "|" + gatewayPaymentId).getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expectedSignature, HexFormat.of().parseHex(signature))) {
            throw new IllegalArgumentException("Razorpay payment signature verification failed.");
        }

        if ("PAID".equals(existingStatus)) {
            if (gatewayPaymentId.equals(existingGatewayPayment)) {
                GymManagementServer.json(x, 200, Map.of("status", "PAID", "message", "Payment was already verified and applied."));
                return;
            }
            throw new IllegalArgumentException("This order has already been paid with a different transaction.");
        }

        // Query Razorpay API to confirm capture, amount, and currency
        String paymentResponse = GymManagementServer.razorpayRequest("GET", "/payments/" + gatewayPaymentId, null);
        String remoteOrderId = GymManagementServer.jsonStringValue(paymentResponse, "order_id");
        String remoteStatus = GymManagementServer.jsonStringValue(paymentResponse, "status");
        String currency = GymManagementServer.jsonStringValue(paymentResponse, "currency");
        Long remoteAmount = GymManagementServer.jsonLongValue(paymentResponse, "amount");
        long expectedPaise = expectedAmount.movePointRight(2).longValueExact();

        if (!storedOrderId.equals(remoteOrderId) || !"INR".equals(currency) || remoteAmount == null || remoteAmount != expectedPaise) {
            throw new IllegalArgumentException("The Razorpay payment does not match this order's amount or currency.");
        }
        if (!"captured".equals(remoteStatus)) {
            GymManagementServer.json(x, 202, Map.of("status", "PENDING", "message", "Razorpay has not captured this payment yet. Membership will activate once captured."));
            return;
        }

        String finalMessage = "Payment verified successfully.";
        try (Connection c = GymManagementServer.db()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement lock = c.prepareStatement(
                        "SELECT p.status, t.gateway_payment_id FROM payments p "
                                + "JOIN razorpay_transactions t ON t.payment_id = p.payment_id "
                                + "WHERE p.payment_id = ? AND p.member_id = ? FOR UPDATE")) {
                    lock.setLong(1, localPaymentId);
                    lock.setLong(2, memberId);
                    try (ResultSet r = lock.executeQuery()) {
                        if (!r.next()) throw new IllegalArgumentException("Payment record not found.");
                        String currStatus = r.getString(1), currGw = r.getString(2);
                        if ("PAID".equals(currStatus)) {
                            if (gatewayPaymentId.equals(currGw)) {
                                c.commit();
                                GymManagementServer.json(x, 200, Map.of("status", "PAID", "message", "Payment was already verified."));
                                return;
                            }
                            throw new IllegalArgumentException("Order already paid with a different payment.");
                        }
                        if (!"PENDING".equals(currStatus)) {
                            throw new IllegalArgumentException("This payment is not pending verification.");
                        }
                    }
                }

                Long activatedMembershipId = null;

                if ("RENEWAL".equals(purpose) && targetPlanId != null) {
                    int durationMonths;
                    String planName;
                    try (PreparedStatement pp = c.prepareStatement("SELECT plan_name, duration_months FROM membership_plans WHERE plan_id=?")) {
                        pp.setLong(1, targetPlanId);
                        try (ResultSet r = pp.executeQuery()) {
                            if (!r.next()) throw new SQLException("Target plan not found.");
                            planName = r.getString("plan_name");
                            durationMonths = r.getInt("duration_months");
                        }
                    }

                    // Extend seamlessly from current latest end_date if still active, else start today
                    LocalDate start = LocalDate.now();
                    try (PreparedStatement cur = c.prepareStatement(
                            "SELECT MAX(end_date) FROM member_memberships WHERE member_id=? AND status<>'CANCELLED' AND end_date >= CURRENT_DATE")) {
                        cur.setLong(1, memberId);
                        try (ResultSet r = cur.executeQuery()) {
                            if (r.next() && r.getDate(1) != null) {
                                start = r.getDate(1).toLocalDate().plusDays(1);
                            }
                        }
                    }
                    LocalDate end = start.plusMonths(durationMonths).minusDays(1);

                    try (PreparedStatement insMem = c.prepareStatement(
                            "INSERT INTO member_memberships(member_id, plan_id, start_date, end_date, status) VALUES(?,?,?,?,'ACTIVE')",
                            Statement.RETURN_GENERATED_KEYS)) {
                        insMem.setLong(1, memberId);
                        insMem.setLong(2, targetPlanId);
                        insMem.setDate(3, java.sql.Date.valueOf(start));
                        insMem.setDate(4, java.sql.Date.valueOf(end));
                        insMem.executeUpdate();
                        activatedMembershipId = GymManagementServer.generated(insMem);
                    }
                    GymManagementServer.createNotification(c, memberId, "RENEWAL_SUCCESS",
                            "Membership Renewed Successfully",
                            "Your " + planName + " plan is active from " + start + " to " + end + ". Receipt #" + localPaymentId + " is ready.",
                            "renewal_paid_" + localPaymentId);
                    finalMessage = "Payment verified! Your " + planName + " membership is renewed (" + start + " to " + end + ").";

                } else if ("UPGRADE".equals(purpose) && upgradeRequestId != null && targetPlanId != null) {
                    int durationMonths;
                    String planName;
                    try (PreparedStatement pp = c.prepareStatement("SELECT plan_name, duration_months FROM membership_plans WHERE plan_id=?")) {
                        pp.setLong(1, targetPlanId);
                        try (ResultSet r = pp.executeQuery()) {
                            if (!r.next()) throw new SQLException("Requested upgrade plan not found.");
                            planName = r.getString("plan_name");
                            durationMonths = r.getInt("duration_months");
                        }
                    }

                    // Cancel existing active memberships and activate upgraded plan starting today
                    try (PreparedStatement cancelOld = c.prepareStatement(
                            "UPDATE member_memberships SET status='CANCELLED' WHERE member_id=? AND status='ACTIVE' AND end_date >= CURRENT_DATE")) {
                        cancelOld.setLong(1, memberId);
                        cancelOld.executeUpdate();
                    }

                    LocalDate start = LocalDate.now();
                    LocalDate end = start.plusMonths(durationMonths).minusDays(1);
                    try (PreparedStatement insMem = c.prepareStatement(
                            "INSERT INTO member_memberships(member_id, plan_id, start_date, end_date, status) VALUES(?,?,?,?,'ACTIVE')",
                            Statement.RETURN_GENERATED_KEYS)) {
                        insMem.setLong(1, memberId);
                        insMem.setLong(2, targetPlanId);
                        insMem.setDate(3, java.sql.Date.valueOf(start));
                        insMem.setDate(4, java.sql.Date.valueOf(end));
                        insMem.executeUpdate();
                        activatedMembershipId = GymManagementServer.generated(insMem);
                    }

                    try (PreparedStatement updReq = c.prepareStatement(
                            "UPDATE membership_upgrade_requests SET status='COMPLETED', payment_id=? WHERE request_id=? AND member_id=?")) {
                        updReq.setLong(1, localPaymentId);
                        updReq.setLong(2, upgradeRequestId);
                        updReq.setLong(3, memberId);
                        updReq.executeUpdate();
                    }

                    GymManagementServer.createNotification(c, memberId, "UPGRADE_COMPLETED",
                            "Membership Upgraded!",
                            "Your membership has been upgraded to " + planName + " (valid through " + end + ").",
                            "upgrade_paid_" + localPaymentId);
                    finalMessage = "Payment verified! Your membership has been upgraded to " + planName + ".";

                } else {
                    GymManagementServer.createNotification(c, memberId, "PAYMENT_SUCCESS",
                            "Payment Confirmed",
                            "Your payment of ₹" + expectedAmount + " has been verified. Receipt #" + localPaymentId + " is ready to download.",
                            "pay_ok_" + localPaymentId);
                }

                try (PreparedStatement updPay = c.prepareStatement(
                        "UPDATE payments SET status='PAID', payment_date=CURRENT_TIMESTAMP, membership_id=COALESCE(?, membership_id) "
                                + "WHERE payment_id=? AND status='PENDING'")) {
                    if (activatedMembershipId == null) updPay.setNull(1, Types.BIGINT); else updPay.setLong(1, activatedMembershipId);
                    updPay.setLong(2, localPaymentId);
                    if (updPay.executeUpdate() != 1) throw new SQLException("Could not update local payment status.");
                }
                try (PreparedStatement updTx = c.prepareStatement(
                        "UPDATE razorpay_transactions SET gateway_payment_id=?, verified_signature=? WHERE payment_id=?")) {
                    updTx.setString(1, gatewayPaymentId);
                    updTx.setString(2, signature);
                    updTx.setLong(3, localPaymentId);
                    updTx.executeUpdate();
                }

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        GymManagementServer.json(x, 200, Map.of("status", "PAID", "paymentId", localPaymentId, "message", finalMessage));
    }

    private static void failRazorpayPayment(HttpExchange x, Map<String, String> q, GymManagementServer.Session session) throws Exception {
        long memberId = session.memberId();
        String orderId = GymManagementServer.required(q, "order_id");
        String reason = q.getOrDefault("reason", "Payment failed or was declined by the gateway.").trim();
        if (reason.length() > 500) reason = reason.substring(0, 500);

        try (Connection c = GymManagementServer.db()) {
            c.setAutoCommit(false);
            try {
                Long paymentId = null;
                BigDecimal amount = null;
                try (PreparedStatement find = c.prepareStatement(
                        "SELECT p.payment_id, p.amount, p.status FROM payments p "
                                + "JOIN razorpay_transactions t ON t.payment_id = p.payment_id "
                                + "WHERE t.order_id = ? AND p.member_id = ? FOR UPDATE")) {
                    find.setString(1, orderId);
                    find.setLong(2, memberId);
                    try (ResultSet r = find.executeQuery()) {
                        if (!r.next()) {
                            c.rollback();
                            GymManagementServer.json(x, 404, Map.of("error", "Order not found."));
                            return;
                        }
                        if (!"PENDING".equals(r.getString("status"))) {
                            c.commit();
                            GymManagementServer.json(x, 200, Map.of("status", r.getString("status")));
                            return;
                        }
                        paymentId = r.getLong("payment_id");
                        amount = r.getBigDecimal("amount");
                    }
                }
                try (PreparedStatement updPay = c.prepareStatement(
                        "UPDATE payments SET status='FAILED' WHERE payment_id=? AND status='PENDING'")) {
                    updPay.setLong(1, paymentId);
                    updPay.executeUpdate();
                }
                try (PreparedStatement updTx = c.prepareStatement(
                        "UPDATE razorpay_transactions SET failure_reason=? WHERE payment_id=?")) {
                    updTx.setString(1, reason);
                    updTx.setLong(2, paymentId);
                    updTx.executeUpdate();
                }
                GymManagementServer.createNotification(c, memberId, "PAYMENT_FAILED",
                        "Payment Failed",
                        "Your payment of ₹" + amount + " failed (" + reason + "). Your membership was not modified.",
                        "pay_fail_" + paymentId);
                c.commit();
                GymManagementServer.json(x, 200, Map.of("status", "FAILED", "message", "Payment marked as failed. Membership was not modified."));
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static void notifications(HttpExchange x, String method, Map<String, String> q, GymManagementServer.Session session) throws Exception {
        long memberId = session.memberId();
        if (method.equals("GET")) {
            try (Connection c = GymManagementServer.db()) {
                syncMemberExpiryNotifications(c, memberId);
                List<Map<String, Object>> list = GymManagementServer.rowsForMember(c,
                        "SELECT notification_id, type, title, message, is_read, created_at "
                                + "FROM notifications WHERE member_id=? ORDER BY created_at DESC LIMIT 50", memberId);
                Object unread = GymManagementServer.scalarForMember(c,
                        "SELECT COUNT(*) FROM notifications WHERE member_id=? AND is_read=FALSE", memberId);
                GymManagementServer.json(x, 200, Map.of("notifications", list, "unreadCount", unread));
            }
        } else if (method.equals("PUT")) {
            try (Connection c = GymManagementServer.db()) {
                if ("true".equalsIgnoreCase(q.get("all"))) {
                    try (PreparedStatement p = c.prepareStatement("UPDATE notifications SET is_read=TRUE WHERE member_id=? AND is_read=FALSE")) {
                        p.setLong(1, memberId);
                        p.executeUpdate();
                    }
                } else {
                    long notifId = GymManagementServer.number(q, "id");
                    try (PreparedStatement p = c.prepareStatement("UPDATE notifications SET is_read=TRUE WHERE notification_id=? AND member_id=?")) {
                        p.setLong(1, notifId);
                        p.setLong(2, memberId);
                        p.executeUpdate();
                    }
                }
                Object unread = GymManagementServer.scalarForMember(c,
                        "SELECT COUNT(*) FROM notifications WHERE member_id=? AND is_read=FALSE", memberId);
                GymManagementServer.json(x, 200, Map.of("ok", true, "unreadCount", unread));
            }
        } else {
            GymManagementServer.badMethod(x);
        }
    }

    private static void profile(HttpExchange x, String method, Map<String, String> q, GymManagementServer.Session session) throws Exception {
        long memberId = session.memberId();
        if (method.equals("GET")) {
            try (Connection c = GymManagementServer.db();
                 PreparedStatement p = c.prepareStatement(
                         "SELECT m.member_id, m.full_name, m.age, m.gender, m.phone, m.email, m.address, m.joining_date, m.emergency_contact, "
                                 + "a.username, a.role, a.created_at AS account_created_at "
                                 + "FROM members m JOIN member_accounts a ON a.member_id = m.member_id WHERE m.member_id = ?")) {
                p.setLong(1, memberId);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) { GymManagementServer.json(x, 404, Map.of("error", "Profile not found.")); return; }
                    Map<String, Object> prof = new LinkedHashMap<>();
                    ResultSetMetaData meta = r.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        Object v = r.getObject(i);
                        if (v instanceof java.sql.Date d) v = d.toLocalDate().toString();
                        else if (v instanceof Timestamp t) v = t.toLocalDateTime().toString();
                        else if (v instanceof java.time.temporal.TemporalAccessor ta) v = ta.toString();
                        prof.put(meta.getColumnLabel(i), v);
                    }
                    prof.put("currentMembership", loadCurrentMembership(c, memberId));
                    GymManagementServer.json(x, 200, prof);
                }
            }
        } else if (method.equals("PUT")) {
            // Only permitted personal fields can be edited by the member
            String fullName = GymManagementServer.required(q, "full_name");
            int age = (int) GymManagementServer.number(q, "age");
            if (age < 10 || age > 100) throw new IllegalArgumentException("Age must be between 10 and 100.");
            String gender = GymManagementServer.required(q, "gender");
            String phone = GymManagementServer.required(q, "phone");
            String email = q.get("email");
            if (email != null && !email.isBlank() && !email.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new IllegalArgumentException("Enter a valid email address.");
            }
            String address = GymManagementServer.required(q, "address");
            String emergency = GymManagementServer.required(q, "emergency_contact");

            try (Connection c = GymManagementServer.db();
                 PreparedStatement p = c.prepareStatement(
                         "UPDATE members SET full_name=?, age=?, gender=?, phone=?, email=?, address=?, emergency_contact=? WHERE member_id=?")) {
                p.setString(1, fullName);
                p.setInt(2, age);
                p.setString(3, gender);
                p.setString(4, phone);
                GymManagementServer.setNullable(p, 5, email);
                p.setString(6, address);
                p.setString(7, emergency);
                p.setLong(8, memberId);
                p.executeUpdate();
            }
            GymManagementServer.json(x, 200, Map.of("ok", true, "message", "Profile updated successfully."));
        } else {
            GymManagementServer.badMethod(x);
        }
    }

    private static void changePassword(HttpExchange x, Map<String, String> q, GymManagementServer.Session session) throws Exception {
        long memberId = session.memberId();
        String currentPassword = GymManagementServer.required(q, "current_password");
        String newPassword = GymManagementServer.required(q, "new_password");
        if (newPassword.length() < 6 || newPassword.length() > 128) {
            throw new IllegalArgumentException("New password must be between 6 and 128 characters.");
        }
        if (currentPassword.equals(newPassword)) {
            throw new IllegalArgumentException("New password must be different from your current password.");
        }

        try (Connection c = GymManagementServer.db()) {
            try (PreparedStatement p = c.prepareStatement("SELECT password_hash FROM member_accounts WHERE member_id=?")) {
                p.setLong(1, memberId);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next() || !GymManagementServer.verifyPassword(currentPassword, r.getString("password_hash"))) {
                        GymManagementServer.json(x, 400, Map.of("error", "Current password is incorrect."));
                        return;
                    }
                }
            }
            String newHash = GymManagementServer.hashPassword(newPassword);
            try (PreparedStatement upd = c.prepareStatement("UPDATE member_accounts SET password_hash=? WHERE member_id=?")) {
                upd.setString(1, newHash);
                upd.setLong(2, memberId);
                upd.executeUpdate();
            }
        }
        GymManagementServer.json(x, 200, Map.of("ok", true, "message", "Password updated securely."));
    }

    // ==================== ADMIN INTEGRATION ENDPOINTS ====================

    static void adminUpgradeRequests(HttpExchange x, String method, Map<String, String> q, GymManagementServer.Session session) throws Exception {
        if (method.equals("GET")) {
            String statusFilter = q.getOrDefault("status", "ALL").trim().toUpperCase(Locale.ROOT);
            String sql = "SELECT r.request_id, r.member_id, m.full_name, m.phone, "
                    + "cp.plan_name AS current_plan_name, rp.plan_name AS requested_plan_name, "
                    + "rp.duration_months AS requested_duration_months, rp.price AS requested_plan_price, "
                    + "r.price_difference, r.reason, r.status, r.admin_notes, r.reviewed_by, r.reviewed_at, r.created_at "
                    + "FROM membership_upgrade_requests r "
                    + "JOIN members m ON m.member_id = r.member_id "
                    + "JOIN membership_plans rp ON rp.plan_id = r.requested_plan_id "
                    + "LEFT JOIN membership_plans cp ON cp.plan_id = r.current_plan_id "
                    + "WHERE (? = 'ALL' OR r.status = ?) "
                    + "ORDER BY CASE WHEN r.status='PENDING' THEN 0 WHEN r.status='APPROVED' THEN 1 ELSE 2 END, r.created_at DESC";
            try (Connection c = GymManagementServer.db(); PreparedStatement p = c.prepareStatement(sql)) {
                p.setString(1, statusFilter);
                p.setString(2, statusFilter);
                try (ResultSet r = p.executeQuery()) {
                    GymManagementServer.json(x, 200, GymManagementServer.resultRows(r));
                }
            }
        } else if (method.equals("PUT")) {
            long requestId = GymManagementServer.number(q, "id");
            String decision = GymManagementServer.required(q, "status").toUpperCase(Locale.ROOT);
            if (!Set.of("APPROVED", "REJECTED").contains(decision)) {
                throw new IllegalArgumentException("Status must be APPROVED or REJECTED.");
            }
            String adminNotes = q.getOrDefault("admin_notes", "").trim();
            if (adminNotes.length() > 500) throw new IllegalArgumentException("Notes must be 500 characters or fewer.");

            try (Connection c = GymManagementServer.db()) {
                c.setAutoCommit(false);
                try {
                    long memberId;
                    String requestedPlanName;
                    BigDecimal payable;
                    try (PreparedStatement sel = c.prepareStatement(
                            "SELECT r.member_id, r.status, r.price_difference, mp.plan_name "
                                    + "FROM membership_upgrade_requests r JOIN membership_plans mp ON mp.plan_id = r.requested_plan_id "
                                    + "WHERE r.request_id = ? FOR UPDATE")) {
                        sel.setLong(1, requestId);
                        try (ResultSet r = sel.executeQuery()) {
                            if (!r.next()) { c.rollback(); GymManagementServer.json(x, 404, Map.of("error", "Upgrade request not found.")); return; }
                            if (!"PENDING".equals(r.getString("status"))) {
                                c.rollback();
                                GymManagementServer.json(x, 409, Map.of("error", "Only pending upgrade requests can be reviewed."));
                                return;
                            }
                            memberId = r.getLong("member_id");
                            requestedPlanName = r.getString("plan_name");
                            payable = r.getBigDecimal("price_difference");
                        }
                    }

                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE membership_upgrade_requests SET status=?, admin_notes=?, reviewed_by=?, reviewed_at=CURRENT_TIMESTAMP WHERE request_id=?")) {
                        upd.setString(1, decision);
                        GymManagementServer.setNullable(upd, 2, adminNotes);
                        upd.setString(3, session.username());
                        upd.setLong(4, requestId);
                        upd.executeUpdate();
                    }

                    if ("APPROVED".equals(decision)) {
                        String msg = "Your request to upgrade to " + requestedPlanName + " was approved! Complete payment of ₹" + payable + " in your Member Portal to activate."
                                + (adminNotes.isBlank() ? "" : " Note: " + adminNotes);
                        GymManagementServer.createNotification(c, memberId, "UPGRADE_APPROVED",
                                "Upgrade Request Approved", msg, null);
                    } else {
                        String msg = "Your request to upgrade to " + requestedPlanName + " was not approved."
                                + (adminNotes.isBlank() ? "" : " Reason: " + adminNotes);
                        GymManagementServer.createNotification(c, memberId, "UPGRADE_REJECTED",
                                "Upgrade Request Declined", msg, null);
                    }

                    c.commit();
                    GymManagementServer.json(x, 200, Map.of("ok", true, "status", decision));
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
            }
        } else {
            GymManagementServer.badMethod(x);
        }
    }

    static void adminAnnouncements(HttpExchange x, String method, Map<String, String> q) throws Exception {
        if (!method.equals("POST")) { GymManagementServer.badMethod(x); return; }
        String title = GymManagementServer.required(q, "title");
        String message = GymManagementServer.required(q, "message");
        if (title.length() > 160) throw new IllegalArgumentException("Title must be 160 characters or fewer.");
        if (message.length() > 600) throw new IllegalArgumentException("Message must be 600 characters or fewer.");
        int count;
        try (Connection c = GymManagementServer.db()) {
            count = GymManagementServer.notifyAllMembers(c, "ANNOUNCEMENT", title, message);
        }
        GymManagementServer.json(x, 201, Map.of("ok", true, "notifiedMembers", count));
    }
}
