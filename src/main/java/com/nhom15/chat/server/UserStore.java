package com.nhom15.chat.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Lưu tài khoản vào file JSON. Mật khẩu không bao giờ được lưu thẳng:
 * chỉ lưu salt ngẫu nhiên và mã băm PBKDF2-HMAC-SHA256.
 * Tên được so sánh theo dạng chuẩn hóa (bỏ dấu, không phân biệt hoa thường)
 * nên "Hoàng", "Hoang", "HOÀNG" là cùng một tài khoản.
 */
public final class UserStore {
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final int MIN_PASSWORD = 4;
    public static final int MAX_PASSWORD = 64;

    private static final class Account {
        String name;   // tên hiển thị đúng như lúc đăng ký
        String salt;   // Base64
        String hash;   // Base64
    }

    private final File file;
    private final Map<String, Account> accounts = new HashMap<>(); // khóa = tên đã chuẩn hóa

    public UserStore(File file) {
        this.file = file;
        load();
    }

    /** Bỏ dấu tiếng Việt, đổi về chữ thường để so sánh tên. */
    public static String canon(String name) {
        String s = Normalizer.normalize(name.trim(), Normalizer.Form.NFD);
        s = s.replaceAll("\\p{M}+", "").replace('đ', 'd').replace('Đ', 'D');
        return s.toLowerCase(Locale.ROOT);
    }

    /** Trả về null nếu đăng ký được, ngược lại trả về lý do. */
    public synchronized String register(String name, String password) {
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            return "Mật khẩu phải dài " + MIN_PASSWORD + "-" + MAX_PASSWORD + " ký tự";
        }
        String key = canon(name);
        if (accounts.containsKey(key)) return "Tên đã được đăng ký";
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        Account a = new Account();
        a.name = name.trim();
        a.salt = Base64.getEncoder().encodeToString(salt);
        try {
            a.hash = Base64.getEncoder().encodeToString(hash(password, salt));
        } catch (GeneralSecurityException e) {
            return "Lỗi hệ thống khi băm mật khẩu";
        }
        accounts.put(key, a);
        try {
            save();
        } catch (IOException e) {
            accounts.remove(key);
            return "Không lưu được tài khoản: " + e.getMessage();
        }
        return null;
    }

    /** Trả về tên hiển thị nếu đúng mật khẩu, ngược lại trả về null. */
    public synchronized String verify(String name, String password) {
        Account a = accounts.get(canon(name));
        if (a == null || password == null) return null;
        try {
            byte[] expected = Base64.getDecoder().decode(a.hash);
            byte[] actual = hash(password, Base64.getDecoder().decode(a.salt));
            return MessageDigest.isEqual(expected, actual) ? a.name : null;
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private static byte[] hash(String password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private void load() {
        if (!file.isFile()) return;
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            List<Account> list = GSON.fromJson(json, new TypeToken<List<Account>>() { }.getType());
            if (list != null) for (Account a : list) accounts.put(canon(a.name), a);
        } catch (IOException | RuntimeException e) {
            System.out.println("[USERS] Không đọc được " + file + ": " + e.getMessage());
        }
    }

    private void save() throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        Files.write(tmp.toPath(), GSON.toJson(new ArrayList<>(accounts.values())).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
