import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** Small, dependency-light HTTP application for the Forge Gym Management System (Admin + Member Portals). */
public class GymManagementServer {
    record Session(String username, String role, Long memberId, long expiresAtMillis) {}

    private static final Properties CONFIG = new Properties();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final long SESSION_TTL_MS = 8L * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 210_000;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build();

    public static void main(String[] args) throws Exception {
        Path config = Path.of("config", "app.properties");
        if (!Files.exists(config)) throw new IllegalStateException("Missing config/app.properties. Copy config.example.properties and set your database credentials.");
        try (var input = Files.newInputStream(config)) { CONFIG.load(input); }
        try { Class.forName("org.postgresql.Driver"); } catch (ClassNotFoundException ignored) {}
        ensureSchema();
        int port = Integer.parseInt(CONFIG.getProperty("server.port", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/", GymManagementServer::api);
        server.createContext("/", GymManagementServer::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.start();
        System.out.println("Gym Management System is running at http://localhost:" + port);
    }

    static Connection db() throws SQLException {
        return DriverManager.getConnection(CONFIG.getProperty("db.url"), CONFIG.getProperty("db.username"), CONFIG.getProperty("db.password"));
    }

    private static void api(HttpExchange x) throws IOException {
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        try {
            String path = x.getRequestURI().getPath().substring("/api".length());
            String method = x.getRequestMethod();
            Map<String, String> q = params(x);
            if (path.equals("/setup/status") && method.equals("GET")) { setupStatus(x); return; }
            if (path.equals("/setup") && method.equals("POST")) { setup(x, q); return; }
            if (path.equals("/signup") && method.equals("POST")) { signup(x, q); return; }
            if (path.equals("/member/signup") && method.equals("POST")) { memberSignup(x, q); return; }
            if (path.equals("/login") && method.equals("POST")) { login(x, q); return; }
            if (path.equals("/logout") && method.equals("POST")) { logout(x); return; }
            if (path.equals("/me") && method.equals("GET")) { me(x); return; }

            Session session = currentSession(x);
            if (session == null) { json(x, 401, Map.of("error", "Please sign in to continue.")); return; }

            // Member Portal APIs (strictly restricted to MEMBER role)
            if (path.startsWith("/member/")) {
                if (!"MEMBER".equals(session.role()) || session.memberId() == null) {
                    json(x, 403, Map.of("error", "Member portal access required."));
                    return;
                }
                MemberPortalHandler.route(x, path.substring("/member".length()), method, q, session);
                return;
            }

            // Admin Portal APIs (strictly restricted to ADMIN role)
            if (!"ADMIN".equals(session.role())) {
                json(x, 403, Map.of("error", "Administrator access required."));
                return;
            }

            switch (path) {
                case "/dashboard" -> { if (method.equals("GET")) dashboard(x); else badMethod(x); }
                case "/members" -> members(x, method, q);
                case "/plans" -> plans(x, method, q);
                case "/memberships" -> memberships(x, method, q);
                case "/payments" -> payments(x, method, q);
                case "/razorpay/order" -> { if (method.equals("POST")) createRazorpayOrder(x, q); else badMethod(x); }
                case "/razorpay/verify" -> { if (method.equals("POST")) verifyRazorpayPayment(x, q); else badMethod(x); }
                case "/attendance" -> attendance(x, method, q);
                case "/reports" -> { if (method.equals("GET")) reports(x, q); else badMethod(x); }
                case "/upgrade-requests" -> MemberPortalHandler.adminUpgradeRequests(x, method, q, session);
                case "/announcements" -> MemberPortalHandler.adminAnnouncements(x, method, q);
                default -> json(x, 404, Map.of("error", "Endpoint not found."));
            }
        } catch (IllegalArgumentException e) { json(x, 400, Map.of("error", e.getMessage())); }
        catch (GatewayException e) { json(x, e.status, Map.of("error", e.getMessage())); }
        catch (SQLException e) {
            String state = e.getSQLState();
            boolean duplicate = "23505".equals(state);
            boolean fk = "23503".equals(state);
            int status = (duplicate || fk) ? 409 : 500;
            String message = fk ? "This record has history and cannot be deleted." : duplicate ? "That value is already in use or this action is a duplicate." : "Database operation failed. Check that PostgreSQL is running and the schema is installed.";
            json(x, status, Map.of("error", message));
            System.err.println("Database error (" + state + "): " + e.getMessage());
        } catch (Exception e) {
            json(x, 500, Map.of("error", "The request could not be completed."));
            e.printStackTrace();
        }
    }

    private static void setupStatus(HttpExchange x) throws Exception {
        try (Connection c = db(); PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM admin"); ResultSet r = p.executeQuery()) {
            r.next(); json(x, 200, Map.of("setupRequired", r.getInt(1) == 0));
        }
    }

    private static void setup(HttpExchange x, Map<String, String> q) throws Exception {
        String username = required(q, "username");
        String password = required(q, "password");
        if (!username.matches("[A-Za-z0-9_.-]{3,50}")) throw new IllegalArgumentException("Username must be 3–50 letters, numbers, dots, underscores, or hyphens.");
        if (password.length() < 6 || password.length() > 128) throw new IllegalArgumentException("Choose a password between 6 and 128 characters.");
        String hash = hashPassword(password);
        try (Connection c = db()) {
            c.setAutoCommit(false);
            try (PreparedStatement count = c.prepareStatement("SELECT COUNT(*) FROM admin"); ResultSet r = count.executeQuery()) {
                r.next(); if (r.getInt(1) != 0) { c.rollback(); json(x, 409, Map.of("error", "An administrator is already configured.")); return; }
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO admin(username,password_hash) VALUES(?,?)")) { p.setString(1, username); p.setString(2, hash); p.executeUpdate(); }
            c.commit();
        }
        createSession(x, username, "ADMIN", null);
        json(x, 201, Map.of("username", username, "role", "ADMIN", "redirect", "/"));
    }

    private static void signup(HttpExchange x, Map<String, String> q) throws Exception {
        String username = required(q, "username");
        String password = required(q, "password");
        if (!username.matches("[A-Za-z0-9_.-]{3,50}"))
            throw new IllegalArgumentException("Username must be 3–50 letters, numbers, dots, underscores, or hyphens.");
        if (password.length() < 6 || password.length() > 128)
            throw new IllegalArgumentException("Choose a password between 6 and 128 characters.");

        try (Connection c = db()) {
            if (isUsernameTaken(c, username)) {
                json(x, 409, Map.of("error", "Username is already taken. Please choose another."));
                return;
            }
            String hash = hashPassword(password);
            try (PreparedStatement p = c.prepareStatement("INSERT INTO admin(username,password_hash) VALUES(?,?)")) {
                p.setString(1, username);
                p.setString(2, hash);
                p.executeUpdate();
            }
        }
        createSession(x, username, "ADMIN", null);
        json(x, 201, Map.of("username", username, "role", "ADMIN", "redirect", "/"));
    }

    /** Register a new Member account (links existing unlinked member by phone/email or creates a new member record). */
    private static void memberSignup(HttpExchange x, Map<String, String> q) throws Exception {
        String username = required(q, "username");
        String password = required(q, "password");
        String fullName = required(q, "full_name");
        int age = (int) number(q, "age");
        String gender = required(q, "gender");
        String phone = required(q, "phone");
        String email = q.get("email");
        String address = required(q, "address");
        String emergency = required(q, "emergency_contact");

        if (!username.matches("[A-Za-z0-9_.-]{3,50}"))
            throw new IllegalArgumentException("Username must be 3–50 letters, numbers, dots, underscores, or hyphens.");
        if (password.length() < 6 || password.length() > 128)
            throw new IllegalArgumentException("Choose a password between 6 and 128 characters.");
        if (age < 10 || age > 100)
            throw new IllegalArgumentException("Age must be between 10 and 100.");
        if (email != null && !email.isBlank() && !email.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new IllegalArgumentException("Enter a valid email address.");

        String hash = hashPassword(password);
        long memberId;
        try (Connection c = db()) {
            if (isUsernameTaken(c, username)) {
                json(x, 409, Map.of("error", "Username is already taken. Please choose another."));
                return;
            }
            c.setAutoCommit(false);
            try {
                // Check if member already exists by phone or email (e.g. added earlier by Admin)
                Long existingMemberId = null;
                try (PreparedStatement find = c.prepareStatement(
                        "SELECT member_id FROM members WHERE phone = ? OR (email IS NOT NULL AND LOWER(email) = LOWER(?)) LIMIT 1")) {
                    find.setString(1, phone);
                    find.setString(2, email == null ? "" : email.trim());
                    try (ResultSet r = find.executeQuery()) {
                        if (r.next()) existingMemberId = r.getLong("member_id");
                    }
                }

                if (existingMemberId != null) {
                    // Check if already linked to a member account
                    try (PreparedStatement chk = c.prepareStatement("SELECT 1 FROM member_accounts WHERE member_id=?")) {
                        chk.setLong(1, existingMemberId);
                        try (ResultSet r = chk.executeQuery()) {
                            if (r.next()) {
                                c.rollback();
                                json(x, 409, Map.of("error", "An account with this phone number or email already exists. Please sign in."));
                                return;
                            }
                        }
                    }
                    memberId = existingMemberId;
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE members SET full_name=?, age=?, gender=?, email=COALESCE(?, email), address=?, emergency_contact=? WHERE member_id=?")) {
                        upd.setString(1, fullName);
                        upd.setInt(2, age);
                        upd.setString(3, gender);
                        setNullable(upd, 4, email);
                        upd.setString(5, address);
                        upd.setString(6, emergency);
                        upd.setLong(7, memberId);
                        upd.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO members(full_name, age, gender, phone, email, address, joining_date, emergency_contact) VALUES(?,?,?,?,?,?,CURRENT_DATE,?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ins.setString(1, fullName);
                        ins.setInt(2, age);
                        ins.setString(3, gender);
                        ins.setString(4, phone);
                        setNullable(ins, 5, email);
                        ins.setString(6, address);
                        ins.setString(7, emergency);
                        ins.executeUpdate();
                        memberId = generated(ins);
                    }
                }

                try (PreparedStatement insAcc = c.prepareStatement(
                        "INSERT INTO member_accounts(member_id, username, password_hash, role) VALUES(?,?,?,'MEMBER')")) {
                    insAcc.setLong(1, memberId);
                    insAcc.setString(2, username);
                    insAcc.setString(3, hash);
                    insAcc.executeUpdate();
                }

                createNotification(c, memberId, "WELCOME",
                        "Welcome to Forge Gym!",
                        "Your Member Portal is ready. Explore membership plans, track check-ins, and manage renewals anytime.",
                        "welcome_" + memberId);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        createSession(x, username, "MEMBER", memberId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", username);
        out.put("role", "MEMBER");
        out.put("memberId", memberId);
        out.put("fullName", fullName);
        out.put("redirect", "/member/member-dashboard.html");
        json(x, 201, out);
    }

    private static boolean isUsernameTaken(Connection c, String username) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT 1 FROM admin WHERE LOWER(username)=LOWER(?) UNION ALL SELECT 1 FROM member_accounts WHERE LOWER(username)=LOWER(?) LIMIT 1")) {
            p.setString(1, username);
            p.setString(2, username);
            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }

    static String hashPassword(String password) throws Exception {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); spec.clearPassword();
        return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derived);
    }

    static boolean verifyPassword(String password, String stored) throws Exception {
        String[] parts = stored.split(":"); if (parts.length != 3) return false;
        int iterations = Integer.parseInt(parts[0]); if (iterations < 100_000 || iterations > 2_000_000) return false;
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(parts[1]), iterations, 256);
        byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); spec.clearPassword();
        return MessageDigest.isEqual(actual, Base64.getDecoder().decode(parts[2]));
    }

    private static void login(HttpExchange x, Map<String, String> q) throws Exception {
        String identifier = required(q, "username");
        String password = required(q, "password");

        try (Connection c = db()) {
            // 1. Try ADMIN account
            try (PreparedStatement p = c.prepareStatement("SELECT username, password_hash FROM admin WHERE LOWER(username)=LOWER(?)")) {
                p.setString(1, identifier);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        if (verifyPassword(password, r.getString("password_hash"))) {
                            String matchedUser = r.getString("username");
                            createSession(x, matchedUser, "ADMIN", null);
                            json(x, 200, Map.of("username", matchedUser, "role", "ADMIN", "redirect", "/"));
                            return;
                        }
                        json(x, 401, Map.of("error", "Username or password is incorrect."));
                        return;
                    }
                }
            }

            // 2. Try MEMBER account (match username, email, or phone)
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT a.username, a.password_hash, a.member_id, m.full_name "
                            + "FROM member_accounts a JOIN members m ON m.member_id = a.member_id "
                            + "WHERE LOWER(a.username)=LOWER(?) OR (m.email IS NOT NULL AND LOWER(m.email)=LOWER(?)) OR m.phone=? LIMIT 1")) {
                p.setString(1, identifier);
                p.setString(2, identifier);
                p.setString(3, identifier);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next() && verifyPassword(password, r.getString("password_hash"))) {
                        String matchedUser = r.getString("username");
                        long memberId = r.getLong("member_id");
                        String fullName = r.getString("full_name");
                        createSession(x, matchedUser, "MEMBER", memberId);
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("username", matchedUser);
                        out.put("role", "MEMBER");
                        out.put("memberId", memberId);
                        out.put("fullName", fullName);
                        out.put("redirect", "/member/member-dashboard.html");
                        json(x, 200, out);
                        return;
                    }
                }
            }
        }
        json(x, 401, Map.of("error", "Username or password is incorrect."));
    }

    private static void me(HttpExchange x) throws Exception {
        Session s = currentSession(x);
        if (s == null) { json(x, 401, Map.of("error", "Please sign in to continue.")); return; }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", s.username());
        out.put("role", s.role());
        if ("MEMBER".equals(s.role()) && s.memberId() != null) {
            out.put("memberId", s.memberId());
            try (Connection c = db(); PreparedStatement p = c.prepareStatement("SELECT full_name FROM members WHERE member_id=?")) {
                p.setLong(1, s.memberId());
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) out.put("fullName", r.getString("full_name"));
                }
            }
        }
        json(x, 200, out);
    }

    private static void createSession(HttpExchange x, String username, String role, Long memberId) {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); String token = HexFormat.of().formatHex(bytes);
        SESSIONS.put(token, new Session(username, role, memberId, System.currentTimeMillis() + SESSION_TTL_MS));
        x.getResponseHeaders().add("Set-Cookie", "GMSSESSION=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800");
    }

    private static void logout(HttpExchange x) throws IOException {
        String token = cookie(x, "GMSSESSION");
        if (token != null) SESSIONS.remove(token);
        x.getResponseHeaders().add("Set-Cookie", "GMSSESSION=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        json(x, 200, Map.of("ok", true));
    }

    private static Session currentSession(HttpExchange x) {
        String token = cookie(x, "GMSSESSION");
        if (token == null) return null;
        Session s = SESSIONS.get(token);
        if (s == null) return null;
        if (System.currentTimeMillis() > s.expiresAtMillis()) {
            SESSIONS.remove(token);
            return null;
        }
        return s;
    }

    private static String cookie(HttpExchange x, String name) {
        String header = x.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) return pair[1];
        }
        return null;
    }

    // ==================== ADMIN ENDPOINTS ====================

    private static void dashboard(HttpExchange x) throws Exception {
        Map<String,Object> out = new LinkedHashMap<>();
        try (Connection c = db()) {
            out.put("members", scalar(c, "SELECT COUNT(*) FROM members"));
            out.put("activeMemberships", scalar(c, "SELECT COUNT(*) FROM member_memberships WHERE status='ACTIVE' AND CURRENT_DATE BETWEEN start_date AND end_date"));
            out.put("expiredMemberships", scalar(c, "SELECT COUNT(*) FROM member_memberships WHERE end_date<CURRENT_DATE OR status='EXPIRED'"));
            out.put("revenue", scalar(c, "SELECT COALESCE(SUM(amount),0) FROM payments WHERE status='PAID'"));
            out.put("todayAttendance", scalar(c, "SELECT COUNT(*) FROM attendance WHERE attendance_date=CURRENT_DATE"));
            out.put("pendingUpgradeRequests", scalar(c, "SELECT COUNT(*) FROM membership_upgrade_requests WHERE status='PENDING'"));
            out.put("recentMembers", rows(c, "SELECT member_id,full_name,phone,joining_date FROM members ORDER BY joining_date DESC,member_id DESC LIMIT 6"));
            out.put("recentPayments", rows(c, "SELECT p.payment_id,m.full_name,p.amount,p.payment_method,p.payment_date,p.status FROM payments p JOIN members m ON m.member_id=p.member_id ORDER BY p.payment_date DESC LIMIT 6"));
        }
        json(x, 200, out);
    }

    private static void members(HttpExchange x, String method, Map<String,String> q) throws Exception {
        if (method.equals("GET")) {
            if (q.containsKey("id")) {
                long id=number(q,"id");
                try(Connection c=db();PreparedStatement p=c.prepareStatement("SELECT m.*, a.username AS portal_username FROM members m LEFT JOIN member_accounts a ON a.member_id=m.member_id WHERE m.member_id=?")){
                    p.setLong(1,id);try(ResultSet r=p.executeQuery()){if(!r.next()){json(x,404,Map.of("error","Member not found."));return;}Map<String,Object> detail=new LinkedHashMap<>();ResultSetMetaData m=r.getMetaData();for(int i=1;i<=m.getColumnCount();i++){Object v=r.getObject(i);if(v instanceof java.sql.Date d)v=d.toLocalDate().toString();else if(v instanceof Timestamp t)v=t.toLocalDateTime().toString();detail.put(m.getColumnLabel(i),v);}
                        detail.put("memberships",rowsForMember(c,"SELECT mp.plan_name,mm.start_date,mm.end_date,mm.status FROM member_memberships mm JOIN membership_plans mp ON mp.plan_id=mm.plan_id WHERE mm.member_id=? ORDER BY mm.start_date DESC",id));
                        detail.put("payments",rowsForMember(c,"SELECT amount,payment_method,payment_date,status FROM payments WHERE member_id=? ORDER BY payment_date DESC LIMIT 10",id));
                        detail.put("attendanceCount",scalarForMember(c,"SELECT COUNT(*) FROM attendance WHERE member_id=?",id));json(x,200,detail);return;}
                }
            }
            String search = q.getOrDefault("search", "").trim();
            try (Connection c = db(); PreparedStatement p = c.prepareStatement(
                    "SELECT m.*, a.username AS portal_username FROM members m LEFT JOIN member_accounts a ON a.member_id=m.member_id "
                            + "WHERE CAST(m.member_id AS TEXT) LIKE ? OR m.full_name LIKE ? OR m.phone LIKE ? ORDER BY m.member_id DESC")) {
                String pattern = "%" + search + "%"; p.setString(1, pattern); p.setString(2, pattern); p.setString(3, pattern);
                try (ResultSet r = p.executeQuery()) { json(x, 200, resultRows(r)); }
            }
        } else if (method.equals("POST")) {
            try (Connection c = db(); PreparedStatement p = c.prepareStatement("INSERT INTO members(full_name,age,gender,phone,email,address,joining_date,emergency_contact) VALUES(?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                bindMember(p,q,1); p.executeUpdate(); json(x,201,Map.of("memberId", generated(p)));
            }
        } else if (method.equals("PUT")) {
            long id = number(q,"id"); try (Connection c=db(); PreparedStatement p=c.prepareStatement("UPDATE members SET full_name=?,age=?,gender=?,phone=?,email=?,address=?,joining_date=?,emergency_contact=? WHERE member_id=?")) { bindMember(p,q,1); p.setLong(9,id); if(p.executeUpdate()==0) { json(x,404,Map.of("error","Member not found.")); return; } json(x,200,Map.of("ok",true)); }
        } else if (method.equals("DELETE")) {
            try(Connection c=db(); PreparedStatement p=c.prepareStatement("DELETE FROM members WHERE member_id=?")){p.setLong(1,number(q,"id")); if(p.executeUpdate()==0){json(x,404,Map.of("error","Member not found."));return;} json(x,200,Map.of("ok",true));}
        } else badMethod(x);
    }

    private static void bindMember(PreparedStatement p, Map<String,String> q, int start) throws Exception {
        String name=required(q,"full_name"), phone=required(q,"phone"), gender=required(q,"gender");
        int age=(int)number(q,"age"); if(age<10||age>100)throw new IllegalArgumentException("Age must be between 10 and 100.");
        p.setString(start,name);p.setInt(start+1,age);p.setString(start+2,gender);p.setString(start+3,phone);setNullable(p,start+4,q.get("email"));p.setString(start+5,required(q,"address"));p.setDate(start+6,java.sql.Date.valueOf(q.getOrDefault("joining_date",LocalDate.now().toString())));p.setString(start+7,required(q,"emergency_contact"));
    }

    private static void plans(HttpExchange x,String method,Map<String,String> q)throws Exception{
        if(method.equals("GET")){try(Connection c=db();PreparedStatement p=c.prepareStatement("SELECT * FROM membership_plans ORDER BY duration_months, price" );ResultSet r=p.executeQuery()){json(x,200,resultRows(r));}}
        else if(method.equals("POST")){
            String planName = required(q,"plan_name");
            int duration = (int)number(q,"duration_months");
            BigDecimal price = new BigDecimal(required(q,"price"));
            String mType = q.getOrDefault("membership_type", "Standard").trim();
            if (mType.isBlank()) mType = "Standard";
            try(Connection c=db();PreparedStatement p=c.prepareStatement("INSERT INTO membership_plans(plan_name,duration_months,price,description,membership_type,benefits,active) VALUES(?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                p.setString(1,planName);p.setInt(2,duration);p.setBigDecimal(3,price);setNullable(p,4,q.get("description"));p.setString(5,mType);setNullable(p,6,q.get("benefits"));p.setBoolean(7,true);
                p.executeUpdate();
                long planId = generated(p);
                notifyAllMembers(c, "NEW_PLAN", "New Membership Plan: " + planName,
                        "Forge Gym launched the " + planName + " (" + mType + ") plan — " + duration + " month(s) at ₹" + price + ".");
                json(x,201,Map.of("planId",planId));
            }
        }
        else if(method.equals("PUT")){
            String mType = q.getOrDefault("membership_type", "Standard").trim();
            if (mType.isBlank()) mType = "Standard";
            try(Connection c=db();PreparedStatement p=c.prepareStatement("UPDATE membership_plans SET plan_name=?,duration_months=?,price=?,description=?,membership_type=?,benefits=?,active=? WHERE plan_id=?")){
                p.setString(1,required(q,"plan_name"));p.setInt(2,(int)number(q,"duration_months"));p.setBigDecimal(3,new BigDecimal(required(q,"price")));setNullable(p,4,q.get("description"));p.setString(5,mType);setNullable(p,6,q.get("benefits"));p.setBoolean(7,Boolean.parseBoolean(q.getOrDefault("active","true")));p.setLong(8,number(q,"id"));
                if(p.executeUpdate()==0){json(x,404,Map.of("error","Plan not found."));return;}json(x,200,Map.of("ok",true));
            }
        }
        else if(method.equals("DELETE")){try(Connection c=db();PreparedStatement p=c.prepareStatement("UPDATE membership_plans SET active=FALSE WHERE plan_id=?")){p.setLong(1,number(q,"id"));if(p.executeUpdate()==0){json(x,404,Map.of("error","Plan not found."));return;}json(x,200,Map.of("ok",true));}}
        else badMethod(x);
    }

    private static void memberships(HttpExchange x,String method,Map<String,String> q)throws Exception{
        if(method.equals("GET")){String sql="SELECT mm.membership_id,mm.member_id,m.full_name,mm.plan_id,mp.plan_name,mm.start_date,mm.end_date,CASE WHEN mm.status='CANCELLED' THEN mm.status WHEN mm.end_date<CURRENT_DATE THEN 'EXPIRED' WHEN mm.start_date<=CURRENT_DATE THEN 'ACTIVE' ELSE 'UPCOMING' END AS status FROM member_memberships mm JOIN members m ON m.member_id=mm.member_id JOIN membership_plans mp ON mp.plan_id=mm.plan_id ORDER BY mm.end_date DESC";try(Connection c=db();PreparedStatement p=c.prepareStatement(sql);ResultSet r=p.executeQuery()){json(x,200,resultRows(r));}}
        else if(method.equals("POST")){long member=number(q,"member_id"),plan=number(q,"plan_id");LocalDate start=LocalDate.parse(q.getOrDefault("start_date",LocalDate.now().toString()));long id;try(Connection c=db()){c.setAutoCommit(false);try(PreparedStatement p=c.prepareStatement("SELECT duration_months FROM membership_plans WHERE plan_id=? AND active=TRUE")){p.setLong(1,plan);try(ResultSet r=p.executeQuery()){if(!r.next()){json(x,400,Map.of("error","Choose an active membership plan."));return;} LocalDate end=start.plusMonths(r.getInt(1)).minusDays(1);try(PreparedStatement ins=c.prepareStatement("INSERT INTO member_memberships(member_id,plan_id,start_date,end_date,status) VALUES(?,?,?,?,'ACTIVE')",Statement.RETURN_GENERATED_KEYS)){ins.setLong(1,member);ins.setLong(2,plan);ins.setDate(3,java.sql.Date.valueOf(start));ins.setDate(4,java.sql.Date.valueOf(end));ins.executeUpdate();id=generated(ins);}}}c.commit();}json(x,201,Map.of("membershipId",id));}
        else badMethod(x);
    }

    /** Ensure all required Member Portal, Upgrade Request, Notification, and Razorpay tables/columns exist in PostgreSQL. */
    private static void ensureSchema() throws SQLException {
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS member_accounts ("
                    + "account_id BIGSERIAL PRIMARY KEY, "
                    + "member_id BIGINT NOT NULL UNIQUE REFERENCES members(member_id) ON UPDATE CASCADE ON DELETE CASCADE, "
                    + "username VARCHAR(50) NOT NULL UNIQUE, "
                    + "password_hash VARCHAR(255) NOT NULL, "
                    + "role VARCHAR(20) NOT NULL DEFAULT 'MEMBER' CHECK (role = 'MEMBER'), "
                    + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("ALTER TABLE membership_plans ADD COLUMN IF NOT EXISTS membership_type VARCHAR(40) NOT NULL DEFAULT 'Standard'");
            s.execute("ALTER TABLE membership_plans ADD COLUMN IF NOT EXISTS benefits TEXT NULL");
            s.execute("ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_check");
            s.execute("ALTER TABLE payments ADD CONSTRAINT payments_status_check CHECK (status IN ('PAID','PENDING','FAILED','REFUNDED'))");
            s.execute("CREATE TABLE IF NOT EXISTS membership_upgrade_requests ("
                    + "request_id BIGSERIAL PRIMARY KEY, "
                    + "member_id BIGINT NOT NULL REFERENCES members(member_id) ON UPDATE CASCADE ON DELETE CASCADE, "
                    + "current_membership_id BIGINT NULL REFERENCES member_memberships(membership_id) ON UPDATE CASCADE ON DELETE SET NULL, "
                    + "current_plan_id BIGINT NULL REFERENCES membership_plans(plan_id) ON UPDATE CASCADE ON DELETE SET NULL, "
                    + "requested_plan_id BIGINT NOT NULL REFERENCES membership_plans(plan_id) ON UPDATE CASCADE ON DELETE RESTRICT, "
                    + "price_difference NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (price_difference >= 0), "
                    + "reason VARCHAR(500) NULL, "
                    + "status VARCHAR(25) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','COMPLETED','CANCELLED')), "
                    + "admin_notes VARCHAR(500) NULL, "
                    + "reviewed_by VARCHAR(50) NULL, "
                    + "reviewed_at TIMESTAMPTZ NULL, "
                    + "payment_id BIGINT NULL REFERENCES payments(payment_id) ON UPDATE CASCADE ON DELETE SET NULL, "
                    + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_upgrade_req_member ON membership_upgrade_requests(member_id, status)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_upgrade_req_status ON membership_upgrade_requests(status, created_at DESC)");
            s.execute("CREATE TABLE IF NOT EXISTS razorpay_transactions ("
                    + "payment_id BIGINT PRIMARY KEY REFERENCES payments(payment_id) ON UPDATE CASCADE ON DELETE RESTRICT, "
                    + "order_id VARCHAR(40) NOT NULL UNIQUE, "
                    + "gateway_payment_id VARCHAR(50) NULL UNIQUE, "
                    + "verified_signature CHAR(64) NULL, "
                    + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("ALTER TABLE razorpay_transactions ADD COLUMN IF NOT EXISTS target_plan_id BIGINT NULL REFERENCES membership_plans(plan_id) ON UPDATE CASCADE ON DELETE SET NULL");
            s.execute("ALTER TABLE razorpay_transactions ADD COLUMN IF NOT EXISTS purpose VARCHAR(30) NOT NULL DEFAULT 'PAYMENT'");
            s.execute("ALTER TABLE razorpay_transactions ADD COLUMN IF NOT EXISTS upgrade_request_id BIGINT NULL REFERENCES membership_upgrade_requests(request_id) ON UPDATE CASCADE ON DELETE SET NULL");
            s.execute("ALTER TABLE razorpay_transactions ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(500) NULL");
            s.execute("CREATE TABLE IF NOT EXISTS notifications ("
                    + "notification_id BIGSERIAL PRIMARY KEY, "
                    + "member_id BIGINT NOT NULL REFERENCES members(member_id) ON UPDATE CASCADE ON DELETE CASCADE, "
                    + "type VARCHAR(40) NOT NULL, "
                    + "title VARCHAR(160) NOT NULL, "
                    + "message VARCHAR(600) NOT NULL, "
                    + "is_read BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "dedupe_key VARCHAR(120) NULL, "
                    + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_notifications_member ON notifications(member_id, is_read, created_at DESC)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_dedupe ON notifications(member_id, dedupe_key) WHERE dedupe_key IS NOT NULL");
        }
    }

    static String razorpayKeyId() throws GatewayException {
        String keyId = System.getenv("RAZORPAY_KEY_ID");
        if (keyId == null || keyId.isBlank()) keyId = CONFIG.getProperty("razorpay.key_id", "");
        keyId = keyId.trim();
        String secret = razorpayKeySecretRaw();
        if (keyId.isBlank() || secret.isBlank() || keyId.contains("REPLACE_ME") || secret.contains("REPLACE_ME"))
            throw new GatewayException(503, "Set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET environment variables (or config/app.properties) with Razorpay Test Mode keys.");
        if (!keyId.startsWith("rzp_test_"))
            throw new GatewayException(403, "Only Razorpay Test Mode keys (starting with rzp_test_) are permitted in this environment.");
        return keyId;
    }

    private static String razorpayKeySecretRaw() {
        String secret = System.getenv("RAZORPAY_KEY_SECRET");
        if (secret == null || secret.isBlank()) secret = CONFIG.getProperty("razorpay.key_secret", "");
        return secret.trim();
    }

    static String razorpayKeySecret() throws GatewayException {
        razorpayKeyId();
        return razorpayKeySecretRaw();
    }

    static String razorpayRequest(String method, String endpoint, String body) throws GatewayException {
        String keyId = razorpayKeyId();
        String keySecret = razorpayKeySecret();
        String auth = Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://api.razorpay.com/v1" + endpoint))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json");
        if (method.equals("POST")) builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        else builder.GET();
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new GatewayException(502, "Razorpay rejected the request. Check that your Test Mode keys are valid and UPI is enabled.");
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(502, "The Razorpay request was interrupted. Please try again.");
        } catch (IOException e) {
            throw new GatewayException(502, "Could not connect to Razorpay. Check your internet connection and try again.");
        }
    }

    private static void createRazorpayOrder(HttpExchange x, Map<String,String> q) throws Exception {
        long memberId = number(q, "member_id");
        BigDecimal amount;
        try { amount = new BigDecimal(required(q, "amount")).setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("Enter an amount with no more than two decimal places."); }
        long paise;
        try { paise = amount.movePointRight(2).longValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("The payment amount is too large."); }
        if (paise < 100) throw new IllegalArgumentException("Razorpay payments must be at least ₹1.00.");

        String name, email, phone;
        try (Connection c = db(); PreparedStatement p = c.prepareStatement("SELECT full_name,email,phone FROM members WHERE member_id=?")) {
            p.setLong(1, memberId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Select a valid member before starting checkout.");
                name = r.getString("full_name"); email = r.getString("email"); phone = r.getString("phone");
            }
            String membership = q.get("membership_id");
            if (membership != null && !membership.isBlank()) {
                try (PreparedStatement check = c.prepareStatement("SELECT COUNT(*) FROM member_memberships WHERE membership_id=? AND member_id=?")) {
                    check.setLong(1, Long.parseLong(membership)); check.setLong(2, memberId);
                    try (ResultSet r = check.executeQuery()) { r.next(); if (r.getInt(1) == 0) throw new IllegalArgumentException("The selected membership does not belong to this member."); }
                }
            }
        }

        String enteredEmail=q.getOrDefault("payer_email", "").trim();
        if (!enteredEmail.isBlank()) email=enteredEmail;
        if (email == null) email="";
        if (email.isBlank() || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new IllegalArgumentException("Enter a valid payer email so Razorpay can prefill UPI Checkout.");
        String receipt = "gms-" + UUID.randomUUID();
        String body = "{\"amount\":" + paise + ",\"currency\":\"INR\",\"receipt\":\"" + receipt + "\",\"notes\":{\"member_id\":\"" + memberId + "\"}}";
        String orderResponse = razorpayRequest("POST", "/orders", body);
        String orderId = jsonStringValue(orderResponse, "id");
        if (orderId == null || !orderId.matches("order_[A-Za-z0-9]+")) throw new GatewayException(502, "Razorpay returned an invalid order response.");

        long localPaymentId;
        try (Connection c = db()) {
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement("INSERT INTO payments(member_id,membership_id,amount,payment_method,payment_date,status,notes) VALUES(?,?,?,'RAZORPAY',CURRENT_TIMESTAMP,'PENDING','Razorpay Checkout')", Statement.RETURN_GENERATED_KEYS)) {
                p.setLong(1, memberId); setLongNullable(p, 2, q.get("membership_id")); p.setBigDecimal(3, amount); p.executeUpdate(); localPaymentId = generated(p);
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO razorpay_transactions(payment_id,order_id) VALUES(?,?)")) { p.setLong(1, localPaymentId); p.setString(2, orderId); p.executeUpdate(); }
            c.commit();
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("keyId", razorpayKeyId()); result.put("orderId", orderId); result.put("amount", paise); result.put("currency", "INR");
        result.put("name", name); result.put("email", email == null ? "" : email); result.put("contact", phone);
        json(x, 201, result);
    }

    private static void verifyRazorpayPayment(HttpExchange x, Map<String,String> q) throws Exception {
        String orderFromCheckout = required(q, "razorpay_order_id");
        String gatewayPaymentId = required(q, "razorpay_payment_id");
        String signature = required(q, "razorpay_signature");
        if (!orderFromCheckout.matches("order_[A-Za-z0-9]+") || !gatewayPaymentId.matches("pay_[A-Za-z0-9]+") || !signature.matches("[A-Fa-f0-9]{64}"))
            throw new IllegalArgumentException("Razorpay returned invalid payment details.");

        long localPaymentId; BigDecimal expectedAmount; String storedOrderId; String existingStatus; String existingGatewayPayment;
        try (Connection c = db(); PreparedStatement p = c.prepareStatement("SELECT p.payment_id,p.amount,p.status,t.order_id,t.gateway_payment_id FROM payments p JOIN razorpay_transactions t ON t.payment_id=p.payment_id WHERE t.order_id=?")) {
            p.setString(1, orderFromCheckout);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("No matching pending Razorpay order was found.");
                localPaymentId=r.getLong("payment_id"); expectedAmount=r.getBigDecimal("amount"); existingStatus=r.getString("status"); storedOrderId=r.getString("order_id"); existingGatewayPayment=r.getString("gateway_payment_id");
            }
        }
        String secret = razorpayKeySecret();
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expectedSignature = mac.doFinal((storedOrderId + "|" + gatewayPaymentId).getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expectedSignature, HexFormat.of().parseHex(signature))) throw new IllegalArgumentException("Razorpay payment signature verification failed.");
        if ("PAID".equals(existingStatus)) {
            if (gatewayPaymentId.equals(existingGatewayPayment)) { json(x, 200, Map.of("status", "PAID", "message", "Payment was already verified.")); return; }
            throw new IllegalArgumentException("This order has already been paid with a different payment.");
        }

        String paymentResponse = razorpayRequest("GET", "/payments/" + gatewayPaymentId, null);
        String remoteOrderId = jsonStringValue(paymentResponse, "order_id");
        String remoteStatus = jsonStringValue(paymentResponse, "status");
        String currency = jsonStringValue(paymentResponse, "currency");
        Long remoteAmount = jsonLongValue(paymentResponse, "amount");
        long expectedPaise = expectedAmount.movePointRight(2).longValueExact();
        if (!storedOrderId.equals(remoteOrderId) || !"INR".equals(currency) || remoteAmount == null || remoteAmount != expectedPaise)
            throw new IllegalArgumentException("The Razorpay payment does not match this order's amount and currency.");
        if (!"captured".equals(remoteStatus)) { json(x, 202, Map.of("status", "PENDING", "message", "Razorpay has not captured this payment yet. It remains pending.")); return; }

        try (Connection c = db()) {
            c.setAutoCommit(false);
            try (PreparedStatement lock = c.prepareStatement("SELECT p.status,t.gateway_payment_id FROM payments p JOIN razorpay_transactions t ON t.payment_id=p.payment_id WHERE p.payment_id=? FOR UPDATE")) {
                lock.setLong(1, localPaymentId);
                try (ResultSet r = lock.executeQuery()) {
                    if (!r.next()) throw new IllegalArgumentException("The local payment record no longer exists.");
                    String currentStatus=r.getString(1), currentGatewayId=r.getString(2);
                    if ("PAID".equals(currentStatus)) {
                        if (gatewayPaymentId.equals(currentGatewayId)) { c.commit(); json(x, 200, Map.of("status", "PAID", "message", "Payment was already verified.")); return; }
                        throw new IllegalArgumentException("This order has already been paid with a different payment.");
                    }
                    if (!"PENDING".equals(currentStatus)) throw new IllegalArgumentException("This payment is not pending verification.");
                }
            }
            try (PreparedStatement p = c.prepareStatement("UPDATE payments SET status='PAID',payment_date=CURRENT_TIMESTAMP WHERE payment_id=? AND status='PENDING'")) { p.setLong(1,localPaymentId); if (p.executeUpdate()!=1) throw new SQLException("Could not update the local payment status."); }
            try (PreparedStatement p = c.prepareStatement("UPDATE razorpay_transactions SET gateway_payment_id=?,verified_signature=? WHERE payment_id=?")) { p.setString(1,gatewayPaymentId);p.setString(2,signature);p.setLong(3,localPaymentId);p.executeUpdate(); }
            c.commit();
        }
        json(x, 200, Map.of("status", "PAID", "message", "Razorpay payment verified and recorded."));
    }

    static String jsonStringValue(String json, String key) {
        Matcher m=Pattern.compile("\\\""+Pattern.quote(key)+"\\\"\\s*:\\s*\\\"((?:\\\\\\\\.|[^\\\"\\\\\\\\])*)\\\"").matcher(json);
        if(!m.find()) return null;
        return m.group(1).replace("\\\\\"", "\\\"").replace("\\\\\\\\", "\\\\");
    }
    static Long jsonLongValue(String json, String key) {
        Matcher m=Pattern.compile("\\\""+Pattern.quote(key)+"\\\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find()?Long.parseLong(m.group(1)):null;
    }

    private static void payments(HttpExchange x, String method, Map<String,String> q) throws Exception {
        if (method.equals("GET")) {
            String sql = "SELECT p.payment_id,p.member_id,m.full_name,p.membership_id,p.amount,p.payment_method,p.payment_date,p.status,p.notes,t.order_id AS gateway_order_id,t.gateway_payment_id FROM payments p JOIN members m ON m.member_id=p.member_id LEFT JOIN razorpay_transactions t ON t.payment_id=p.payment_id WHERE (?='' OR CAST(p.member_id AS TEXT)=? OR m.full_name LIKE ?) AND (CAST(? AS DATE) IS NULL OR CAST(p.payment_date AS DATE)>=CAST(? AS DATE)) AND (CAST(? AS DATE) IS NULL OR CAST(p.payment_date AS DATE)<=CAST(? AS DATE)) ORDER BY p.payment_date DESC";
            try (Connection c=db(); PreparedStatement p=c.prepareStatement(sql)) {
                String search=q.getOrDefault("search", ""); p.setString(1,search); p.setString(2,search); p.setString(3,"%"+search+"%");
                bindDateFilter(p,4,5,q.get("from")); bindDateFilter(p,6,7,q.get("to"));
                try (ResultSet r=p.executeQuery()) { json(x,200,resultRows(r)); }
            }
        } else if (method.equals("POST")) {
            String paymentMethod=required(q,"payment_method").toUpperCase(Locale.ROOT);
            if ("RAZORPAY".equals(paymentMethod)) throw new IllegalArgumentException("Use Razorpay Checkout to create a gateway payment.");
            if (!Set.of("CASH","UPI","CARD").contains(paymentMethod)) throw new IllegalArgumentException("Choose Cash, UPI, or Card.");
            BigDecimal amount=new BigDecimal(required(q,"amount"));
            if (amount.signum()<=0) throw new IllegalArgumentException("Payment amount must be greater than zero.");
            long memberId = number(q,"member_id");
            String status=q.getOrDefault("status","PAID").toUpperCase(Locale.ROOT);
            if(!Set.of("PAID","PENDING").contains(status)) throw new IllegalArgumentException("Choose a valid payment status.");
            try (Connection c=db(); PreparedStatement p=c.prepareStatement("INSERT INTO payments(member_id,membership_id,amount,payment_method,payment_date,status,notes) VALUES(?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
                p.setLong(1,memberId); setLongNullable(p,2,q.get("membership_id")); p.setBigDecimal(3,amount); p.setString(4,paymentMethod);
                String date=q.getOrDefault("payment_date",LocalDate.now().toString()); p.setTimestamp(5,Timestamp.valueOf(date.length()==10?date+" 12:00:00":date.replace('T',' ')));
                p.setString(6,status); setNullable(p,7,q.get("notes")); p.executeUpdate();
                long pid = generated(p);
                if ("PENDING".equals(status)) {
                    createNotification(c, memberId, "PAYMENT_PENDING", "Pending Membership Fee",
                            "A pending fee of ₹" + amount + " has been recorded on your account. You can pay online in the Payments page.",
                            "pending_fee_" + pid);
                }
                json(x,201,Map.of("paymentId",pid));
            }
        } else if (method.equals("PUT")) {
            String status=required(q,"status").toUpperCase(Locale.ROOT);
            if(!Set.of("PAID","PENDING","FAILED","REFUNDED").contains(status)) throw new IllegalArgumentException("Invalid payment status.");
            long id=number(q,"id");
            try(Connection c=db();PreparedStatement check=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM razorpay_transactions WHERE payment_id=?)")) {
                check.setLong(1,id); try(ResultSet r=check.executeQuery()) { r.next(); if(r.getBoolean(1)) throw new IllegalArgumentException("Razorpay payments can only be marked paid after server-side gateway verification."); }
            }
            try(Connection c=db();PreparedStatement p=c.prepareStatement("UPDATE payments SET status=? WHERE payment_id=?")) {
                p.setString(1,status);p.setLong(2,id);if(p.executeUpdate()==0){json(x,404,Map.of("error","Payment not found."));return;}json(x,200,Map.of("ok",true));
            }
        } else badMethod(x);
    }

    private static void attendance(HttpExchange x,String method,Map<String,String> q)throws Exception{
        if(method.equals("GET")){String sql="SELECT a.attendance_id,a.member_id,m.full_name,a.check_in_at,a.attendance_date FROM attendance a JOIN members m ON m.member_id=a.member_id WHERE (CAST(? AS DATE) IS NULL OR a.attendance_date=CAST(? AS DATE)) AND (?='' OR CAST(a.member_id AS TEXT)=? OR m.full_name LIKE ?) ORDER BY a.check_in_at DESC";try(Connection c=db();PreparedStatement p=c.prepareStatement(sql)){String day=q.getOrDefault("date","");String s=q.getOrDefault("search","");bindDateFilter(p,1,2,day);p.setString(3,s);p.setString(4,s);p.setString(5,"%"+s+"%");try(ResultSet r=p.executeQuery()){json(x,200,resultRows(r));}}}
        else if(method.equals("POST")){try(Connection c=db();PreparedStatement p=c.prepareStatement("INSERT INTO attendance(member_id) VALUES(?)",Statement.RETURN_GENERATED_KEYS)){p.setLong(1,number(q,"member_id"));p.executeUpdate();json(x,201,Map.of("attendanceId",generated(p)));}}
        else badMethod(x);
    }

    private static void reports(HttpExchange x,Map<String,String> q)throws Exception{
        Map<String,Object> out=new LinkedHashMap<>();String month=q.getOrDefault("month",LocalDate.now().toString().substring(0,7));
        try(Connection c=db()){
            out.put("summary",Map.of("members",scalar(c,"SELECT COUNT(*) FROM members"),"activeMemberships",scalar(c,"SELECT COUNT(*) FROM member_memberships WHERE status<>'CANCELLED' AND CURRENT_DATE BETWEEN start_date AND end_date"),"expiredMemberships",scalar(c,"SELECT COUNT(*) FROM member_memberships WHERE end_date<CURRENT_DATE AND status<>'CANCELLED'"),"revenue",scalar(c,"SELECT COALESCE(SUM(amount),0) FROM payments WHERE status='PAID'")));
            try(PreparedStatement p=c.prepareStatement("SELECT TO_CHAR(payment_date,'YYYY-MM') AS month,COALESCE(SUM(amount),0) AS revenue FROM payments WHERE status='PAID' AND payment_date>=(CURRENT_DATE - INTERVAL '11 MONTH') GROUP BY TO_CHAR(payment_date,'YYYY-MM') ORDER BY month");ResultSet r=p.executeQuery()){out.put("monthlyRevenue",resultRows(r));}
            try(PreparedStatement p=c.prepareStatement("SELECT p.payment_id,m.full_name,p.amount,p.payment_method,p.payment_date,p.status FROM payments p JOIN members m ON m.member_id=p.member_id WHERE TO_CHAR(p.payment_date,'YYYY-MM')=? ORDER BY p.payment_date DESC")){p.setString(1,month);try(ResultSet r=p.executeQuery()){out.put("payments",resultRows(r));}}
            out.put("attendanceToday",scalar(c,"SELECT COUNT(*) FROM attendance WHERE attendance_date=CURRENT_DATE"));
            try(PreparedStatement p=c.prepareStatement("SELECT attendance_date AS visit_date,COUNT(*) AS visits FROM attendance WHERE attendance_date>=(CURRENT_DATE - INTERVAL '29 DAY') GROUP BY attendance_date ORDER BY attendance_date DESC");ResultSet r=p.executeQuery()){out.put("attendanceByDay",resultRows(r));}
            try(PreparedStatement p=c.prepareStatement("SELECT mm.membership_id,m.full_name,mp.plan_name,mm.start_date,mm.end_date,CASE WHEN mm.end_date<CURRENT_DATE THEN 'EXPIRED' ELSE 'RENEWAL DUE' END AS renewal_status FROM member_memberships mm JOIN members m ON m.member_id=mm.member_id JOIN membership_plans mp ON mp.plan_id=mm.plan_id WHERE mm.end_date BETWEEN CURRENT_DATE AND (CURRENT_DATE + INTERVAL '30 DAY') OR mm.end_date<CURRENT_DATE ORDER BY mm.end_date DESC LIMIT 100");ResultSet r=p.executeQuery()){out.put("renewals",resultRows(r));}
        }json(x,200,out);
    }

    // ==================== SHARED HELPERS ====================

    static void createNotification(Connection c, long memberId, String type, String title, String message, String dedupeKey) throws SQLException {
        String sql = dedupeKey == null
                ? "INSERT INTO notifications(member_id, type, title, message) VALUES(?,?,?,?)"
                : "INSERT INTO notifications(member_id, type, title, message, dedupe_key) VALUES(?,?,?,?,?) ON CONFLICT (member_id, dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, memberId);
            p.setString(2, type);
            p.setString(3, title);
            p.setString(4, message);
            if (dedupeKey != null) p.setString(5, dedupeKey);
            p.executeUpdate();
        }
    }

    static int notifyAllMembers(Connection c, String type, String title, String message) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO notifications(member_id, type, title, message) SELECT member_id, ?, ?, ? FROM members")) {
            p.setString(1, type);
            p.setString(2, title);
            p.setString(3, message);
            return p.executeUpdate();
        }
    }

    /** Bind an optional HTML date input safely for PostgreSQL. */
    private static void bindDateFilter(PreparedStatement p,int nullIndex,int dateIndex,String value)throws SQLException {
        if(value==null||value.isBlank()){p.setNull(nullIndex,Types.DATE);p.setNull(dateIndex,Types.DATE);return;}
        java.sql.Date date=java.sql.Date.valueOf(LocalDate.parse(value));p.setDate(nullIndex,date);p.setDate(dateIndex,date);
    }

    static Object scalar(Connection c,String sql)throws SQLException{try(PreparedStatement p=c.prepareStatement(sql);ResultSet r=p.executeQuery()){r.next();Object v=r.getObject(1);return v==null?0:v;}}
    static Object scalarForMember(Connection c,String sql,long id)throws SQLException{try(PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,id);try(ResultSet r=p.executeQuery()){r.next();Object v=r.getObject(1);return v==null?0:v;}}}
    static List<Map<String,Object>> rows(Connection c,String sql)throws SQLException{try(PreparedStatement p=c.prepareStatement(sql);ResultSet r=p.executeQuery()){return resultRows(r);}}
    static List<Map<String,Object>> rowsForMember(Connection c,String sql,long id)throws SQLException{try(PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,id);try(ResultSet r=p.executeQuery()){return resultRows(r);}}}
    static List<Map<String,Object>> resultRows(ResultSet r)throws SQLException{List<Map<String,Object>> list=new ArrayList<>();ResultSetMetaData meta=r.getMetaData();while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=meta.getColumnCount();i++){Object v=r.getObject(i);if(v instanceof java.sql.Date d)v=d.toLocalDate().toString();else if(v instanceof Timestamp t)v=t.toLocalDateTime().toString();else if(v instanceof java.time.temporal.TemporalAccessor ta)v=ta.toString();row.put(meta.getColumnLabel(i),v);}list.add(row);}return list;}
    static long generated(Statement p)throws SQLException{try(ResultSet r=p.getGeneratedKeys()){if(!r.next())throw new SQLException("No generated key returned.");return r.getLong(1);}}
    static void setNullable(PreparedStatement p,int i,String v)throws SQLException{if(v==null||v.isBlank())p.setNull(i,Types.VARCHAR);else p.setString(i,v.trim());}
    static void setLongNullable(PreparedStatement p,int i,String v)throws SQLException{if(v==null||v.isBlank())p.setNull(i,Types.BIGINT);else p.setLong(i,Long.parseLong(v));}
    static String required(Map<String,String> q,String key){String v=q.get(key);if(v==null||v.isBlank())throw new IllegalArgumentException("Please provide " + key.replace('_',' ') + ".");return v.trim();}
    static long number(Map<String,String> q,String key){try{return Long.parseLong(required(q,key));}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid number for " + key.replace('_',' ') + ".");}}
    private static Map<String,String> params(HttpExchange x)throws IOException{Map<String,String> out=new HashMap<>();String raw=x.getRequestURI().getRawQuery();if("POST".equals(x.getRequestMethod())||"PUT".equals(x.getRequestMethod())){String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);if(!body.isBlank())raw=raw==null?body:raw+"&"+body;}if(raw!=null)for(String pair:raw.split("&")){String[] p=pair.split("=",2);out.put(decode(p[0]),p.length==2?decode(p[1]):"");}return out;}
    private static String decode(String s){return URLDecoder.decode(s,StandardCharsets.UTF_8);}
    static final class GatewayException extends Exception { final int status; GatewayException(int status,String message){super(message);this.status=status;} }

    private static void staticFile(HttpExchange x)throws IOException{
        String request=x.getRequestURI().getPath();
        if(request.equals("/")) request="/index.html";
        else if(request.equals("/member") || request.equals("/member/")) request="/member/member-dashboard.html";
        Path root=Path.of("web").toAbsolutePath().normalize();
        Path file=root.resolve(request.substring(1)).normalize();
        if(!file.startsWith(root)||!Files.isRegularFile(file)){send(x,404,"text/plain; charset=utf-8","Not found");return;}
        String fname = file.getFileName().toString();
        String ext = fname.contains(".") ? fname.substring(fname.lastIndexOf('.')+1).toLowerCase(Locale.ROOT) : "";
        String type=switch(ext){
            case "html"->"text/html; charset=utf-8";
            case "css"->"text/css; charset=utf-8";
            case "js"->"text/javascript; charset=utf-8";
            case "svg"->"image/svg+xml";
            case "png"->"image/png";
            case "jpg","jpeg"->"image/jpeg";
            case "webp"->"image/webp";
            case "ico"->"image/x-icon";
            default->"application/octet-stream";
        };
        x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        send(x,200,type,Files.readAllBytes(file));
    }

    static void badMethod(HttpExchange x)throws IOException{json(x,405,Map.of("error","Method not allowed."));}
    static void json(HttpExchange x,int status,Object value)throws IOException{send(x,status,"application/json; charset=utf-8",toJson(value).getBytes(StandardCharsets.UTF_8));}
    private static void send(HttpExchange x,int status,String type,String value)throws IOException{send(x,status,type,value.getBytes(StandardCharsets.UTF_8));}
    private static void send(HttpExchange x,int status,String type,byte[] body)throws IOException{x.getResponseHeaders().set("Content-Type",type);x.sendResponseHeaders(status,body.length);try(var out=x.getResponseBody()){out.write(body);}}
    private static String toJson(Object v){if(v==null)return "null";if(v instanceof String||v instanceof Character||v instanceof java.time.temporal.TemporalAccessor)return "\""+escape(v.toString())+"\"";if(v instanceof Number||v instanceof Boolean)return v.toString();if(v instanceof Map<?,?>m){StringJoiner j=new StringJoiner(",","{","}");m.forEach((k,val)->j.add(toJson(k.toString())+":"+toJson(val)));return j.toString();}if(v instanceof Iterable<?>it){StringJoiner j=new StringJoiner(",","[","]");for(Object item:it)j.add(toJson(item));return j.toString();}return toJson(v.toString());}
    private static String escape(String s){StringBuilder b=new StringBuilder();for(char c:s.toCharArray()){switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}}return b.toString();}
}
