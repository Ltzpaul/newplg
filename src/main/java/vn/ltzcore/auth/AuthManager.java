package vn.ltzcore.auth;

import vn.ltzcore.storage.Database;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthManager {
    public enum Result { OK, ALREADY_REGISTERED, NOT_REGISTERED, BAD_PASSWORD, TOO_SHORT, LOCKED, ERROR }
    private static final int ITERATIONS = 160_000;
    private static final int KEY_BITS = 256;
    private final Database db;
    private final SecureRandom random = new SecureRandom();
    private final Set<UUID> sessions = ConcurrentHashMap.newKeySet();
    private final Map<UUID,Integer> failures = new ConcurrentHashMap<>();
    private final Map<UUID,Long> lockedUntil = new ConcurrentHashMap<>();

    public AuthManager(Database db) { this.db = db; }
    public boolean isAuthenticated(UUID uuid) { return sessions.contains(uuid); }
    public boolean isRegistered(UUID uuid) { try { return db.registered(uuid); } catch (Exception e) { return false; } }
    public void clear(UUID uuid) { sessions.remove(uuid); failures.remove(uuid); lockedUntil.remove(uuid); }

    public Result register(UUID uuid, String name, String password, String confirm) {
        if (password == null || password.length() < 8) return Result.TOO_SHORT;
        if (!password.equals(confirm)) return Result.BAD_PASSWORD;
        try {
            if (db.registered(uuid)) return Result.ALREADY_REGISTERED;
            byte[] salt = new byte[16]; random.nextBytes(salt);
            String saltText = Base64.getEncoder().encodeToString(salt);
            String hash = hash(password.toCharArray(), salt);
            if (!db.createUser(uuid, name, saltText, hash)) return Result.ALREADY_REGISTERED;
            sessions.add(uuid); failures.remove(uuid); return Result.OK;
        } catch (Exception e) { return Result.ERROR; }
    }

    public Result login(UUID uuid, String password) {
        long locked = lockedUntil.getOrDefault(uuid, 0L);
        if (locked > System.currentTimeMillis()) return Result.LOCKED;
        try {
            String[] credentials = db.credentials(uuid);
            if (credentials == null) return Result.NOT_REGISTERED;
            byte[] salt = Base64.getDecoder().decode(credentials[0]);
            byte[] expected = Base64.getDecoder().decode(credentials[1]);
            byte[] actual = Base64.getDecoder().decode(hash(password.toCharArray(), salt));
            if (!MessageDigest.isEqual(expected, actual)) {
                int count = failures.merge(uuid, 1, Integer::sum);
                if (count >= 5) { lockedUntil.put(uuid, System.currentTimeMillis() + 60_000L); failures.remove(uuid); }
                return Result.BAD_PASSWORD;
            }
            sessions.add(uuid); failures.remove(uuid); lockedUntil.remove(uuid); return Result.OK;
        } catch (Exception e) { return Result.ERROR; }
    }

    public Result changePassword(UUID uuid, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) return Result.TOO_SHORT;
        try {
            String[] credentials = db.credentials(uuid); if (credentials == null) return Result.NOT_REGISTERED;
            byte[] saltOld = Base64.getDecoder().decode(credentials[0]);
            byte[] expected = Base64.getDecoder().decode(credentials[1]);
            byte[] actual = Base64.getDecoder().decode(hash(oldPassword.toCharArray(), saltOld));
            if (!MessageDigest.isEqual(expected, actual)) return Result.BAD_PASSWORD;
            byte[] salt = new byte[16]; random.nextBytes(salt);
            db.changePassword(uuid, Base64.getEncoder().encodeToString(salt), hash(newPassword.toCharArray(), salt));
            return Result.OK;
        } catch (Exception e) { return Result.ERROR; }
    }

    /** Console-only bootstrap helper for the configured offline-mode Owner account. */
    public Result consoleSetPassword(UUID uuid, String name, String newPassword) {
        if (newPassword == null || newPassword.length() < 12) return Result.TOO_SHORT;
        try {
            byte[] salt = new byte[16]; random.nextBytes(salt);
            db.upsertUserPassword(uuid, name, Base64.getEncoder().encodeToString(salt), hash(newPassword.toCharArray(), salt));
            sessions.remove(uuid);
            failures.remove(uuid);
            lockedUntil.remove(uuid);
            return Result.OK;
        } catch (Exception e) { return Result.ERROR; }
    }

    public Result adminReset(UUID uuid, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) return Result.TOO_SHORT;
        try {
            byte[] salt = new byte[16]; random.nextBytes(salt);
            db.changePassword(uuid, Base64.getEncoder().encodeToString(salt), hash(newPassword.toCharArray(), salt));
            sessions.remove(uuid); return Result.OK;
        } catch (Exception e) { return Result.ERROR; }
    }

    public long lockRemaining(UUID uuid) { return Math.max(0L, lockedUntil.getOrDefault(uuid,0L)-System.currentTimeMillis()); }

    private String hash(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
        try { return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        finally { spec.clearPassword(); java.util.Arrays.fill(password, '\0'); }
    }
}
