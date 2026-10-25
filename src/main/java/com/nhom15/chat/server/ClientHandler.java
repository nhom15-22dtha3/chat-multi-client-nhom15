package com.nhom15.chat.server;

import com.nhom15.chat.common.FramedIO;
import com.nhom15.chat.common.Message;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.net.Socket;
import com.nhom15.chat.common.CryptoUtil;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Base64;
import javax.crypto.SecretKey;
import java.util.concurrent.ConcurrentHashMap;

public class ClientHandler implements Runnable {
    // Cặp khóa RSA tạo mới mỗi lần Server chạy, không lưu trong code.
    private static final KeyPair RSA = CryptoUtil.generateRsa();
    // Tài khoản lưu trong users.json (chỉ có salt và mã băm mật khẩu).
    private static final UserStore USERS = new UserStore(new File("users.json"));
    private final Socket socket;
    private final ConcurrentHashMap<String, ClientHandler> online;
    private DataInputStream in;
    private DataOutputStream out;
    private String username;
    private volatile SecretKey aesKey; // null = chưa mã hóa

    public ClientHandler(Socket socket, ConcurrentHashMap<String, ClientHandler> online) {
        this.socket = socket;
        this.online = online;
    }

    // synchronized: tránh hai luồng ghi chen nhau làm hỏng gói tin.
    public synchronized void send(Message m) throws IOException {
        FramedIO.send(out, m, aesKey);
    }

    @Override
    public void run() {
        try {
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());

            while (true) {
                Message m = FramedIO.receive(in, aesKey);
                // Không in mật khẩu ra log
                boolean secret = "LOGIN".equals(m.action) || "REGISTER".equals(m.action);
                System.out.println("[SERVER] Nhận: " + (secret ? new Message(m.action, m.from, m.to, "***").toJson() : m.toJson()));
                if (!handle(m)) break;
            }
        } catch (IOException e) {
            System.out.println("[SERVER] Mất kết nối: " + username);
        } finally {
            disconnect();
        }
    }

    /** Trả về false khi client muốn thoát. */
    private boolean handle(Message m) throws IOException {
        if (m.action == null) return true;
        switch (m.action) {
            case "PUBKEY_REQ":
                send(new Message("PUBKEY", "server", "", CryptoUtil.publicKeyToBase64(RSA.getPublic())));
                return true;
            case "SESSION_KEY": {
                try {
                    byte[] raw = CryptoUtil.rsaDecrypt(RSA.getPrivate(), Base64.getDecoder().decode(m.content));
                    SecretKey k = CryptoUtil.aesKeyFromBytes(raw);
                    send(new Message("SECURE_OK", "server", "", "AES-256-GCM")); // gửi thường, sau đó mới mã hóa
                    aesKey = k;
                    System.out.println("[SERVER] Đã thiết lập AES-256-GCM với " + socket.getRemoteSocketAddress());
                } catch (GeneralSecurityException | IllegalArgumentException e) {
                    send(new Message("ERROR", "server", "", "Khóa phiên không hợp lệ"));
                }
                return true;
            }
            case "REGISTER":
                register(m);
                return true;
            case "LOGIN":
                login(m);
                return true;
            case "MSG_PRIVATE":
                privateMessage(m);
                return true;
            case "MSG_GROUP":
                groupMessage(m);
                return true;
            case "FILE_OFFER":
                fileOffer(m);
                return true;
            case "FILE_ACCEPT":
                fileAnswer(m, true);
                return true;
            case "FILE_REJECT":
                fileAnswer(m, false);
                return true;
            case "LOGOUT":
                return false;
            default:
                return true;
        }
    }

    /** Chat 1-1: chuyển tiếp tới đúng người nhận và gửi lại bản sao cho người gửi. */
    private void privateMessage(Message m) throws IOException {
        if (username == null) return; // chưa đăng nhập thì bỏ qua
        Message msg = new Message("MSG_PRIVATE", username, m.to, m.content); // ép from = người gửi thật
        ClientHandler target = (m.to == null) ? null : online.get(m.to);
        if (target == null) {
            send(new Message("ERROR", "server", username, "Người nhận không online: " + m.to));
            return;
        }
        target.send(msg);
        if (target != this) send(msg);
    }

    /** Chat nhóm: gửi cho tất cả người đang online. */
    private void groupMessage(Message m) {
        if (username == null) return;
        Message msg = new Message("MSG_GROUP", username, "ALL", m.content);
        for (ClientHandler h : online.values()) {
            try {
                h.send(msg);
            } catch (IOException e) {
                System.out.println("[SERVER] Không gửi được cho một client, bỏ qua");
            }
        }
    }

    private static String validName(String name) {
        if (name.isEmpty() || name.length() > 20 || name.contains(",")) {
            return "Tên không hợp lệ (1-20 ký tự, không chứa dấu phẩy)";
        }
        return null;
    }

    private void register(Message m) throws IOException {
        String name = m.from == null ? "" : m.from.trim();
        String bad = validName(name);
        if (bad == null) bad = USERS.register(name, m.content);
        if (bad != null) {
            send(new Message("REGISTER_FAIL", "server", name, bad));
            return;
        }
        System.out.println("[SERVER] Đăng ký tài khoản mới: " + name);
        send(new Message("REGISTER_OK", "server", name, "Đăng ký thành công"));
    }

    private void login(Message m) throws IOException {
        String name = m.from == null ? "" : m.from.trim();
        if (username != null) {
            send(new Message("LOGIN_FAIL", "server", name, "Bạn đã đăng nhập rồi"));
            return;
        }
        String bad = validName(name);
        if (bad != null) {
            send(new Message("LOGIN_FAIL", "server", name, bad));
            return;
        }
        String stored = USERS.verify(name, m.content);
        if (stored == null) {
            send(new Message("LOGIN_FAIL", "server", name, "Sai tên hoặc mật khẩu"));
            return;
        }
        name = stored; // dùng đúng tên hiển thị đã đăng ký
        // putIfAbsent là thao tác nguyên tử: một tài khoản chỉ đăng nhập được ở một nơi.
        if (online.putIfAbsent(name, this) != null) {
            send(new Message("LOGIN_FAIL", "server", name, "Tài khoản đang đăng nhập ở nơi khác"));
            return;
        }
        username = name;
        System.out.println("[SERVER] Đăng nhập: " + username + " | Online: " + online.size());
        send(new Message("LOGIN_OK", "server", username, "Đăng nhập thành công"));
        broadcastUserList();
    }

    /** Nhận đề nghị gửi file, tạo mã truyền và chuyển cho người nhận. */
    private void fileOffer(Message m) {
        if (username == null) return;
        ClientHandler target = (m.to == null) ? null : online.get(m.to);
        if (target == null || m.to.equals(username)) {
            sendQuiet(this, new Message("ERROR", "server", username, "Người nhận không online: " + m.to));
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(m.content).getAsJsonObject();
            String fileName = new File(o.get("fileName").getAsString()).getName(); // bỏ đường dẫn
            long size = o.get("size").getAsLong();
            if (size <= 0 || size > FileRelayServer.MAX_SIZE) {
                sendQuiet(this, new Message("ERROR", "server", username, "Kích thước file không hợp lệ"));
                return;
            }
            FileRelayServer.Transfer t = FileRelayServer.register(username, m.to, fileName, size);
            JsonObject out2 = new JsonObject();
            out2.addProperty("fileName", fileName);
            out2.addProperty("size", size);
            out2.addProperty("transferId", t.id);
            // Khóa AES của lần truyền và SHA-256 do người gửi tạo; Server chỉ chuyển tiếp cho người nhận
            if (!o.has("key") || !o.has("sha256")) {
                FileRelayServer.remove(t.id);
                sendQuiet(this, new Message("ERROR", "server", username, "Thiếu khóa hoặc SHA-256 của file"));
                return;
            }
            out2.addProperty("key", o.get("key").getAsString());
            out2.addProperty("sha256", o.get("sha256").getAsString());
            sendQuiet(target, new Message("FILE_OFFER", username, m.to, out2.toString()));
        } catch (RuntimeException e) {
            sendQuiet(this, new Message("ERROR", "server", username, "FILE_OFFER không hợp lệ"));
        }
    }

    /** Người nhận đồng ý hoặc từ chối; báo lại cho người gửi. */
    private void fileAnswer(Message m, boolean ok) {
        if (username == null) return;
        FileRelayServer.Transfer t = FileRelayServer.get(m.content);
        if (t == null || !t.receiver.equals(username)) {
            sendQuiet(this, new Message("ERROR", "server", username, "Mã truyền file không hợp lệ"));
            return;
        }
        if (ok) t.accepted = true; else FileRelayServer.remove(t.id);
        ClientHandler sender = online.get(t.sender);
        if (sender != null) {
            sendQuiet(sender, new Message(ok ? "FILE_ACCEPT" : "FILE_REJECT", username, t.sender, t.id));
        }
    }

    private void sendQuiet(ClientHandler h, Message msg) {
        try { h.send(msg); } catch (IOException ignored) { }
    }

    private void disconnect() {
        if (username != null) {
            online.remove(username);
            System.out.println("[SERVER] Thoát: " + username + " | Online: " + online.size());
            username = null;
            broadcastUserList();
        }
        try { socket.close(); } catch (IOException ignored) {}
    }

    /** Gửi danh sách người đang online (cách nhau bằng dấu phẩy) cho mọi client. */
    private void broadcastUserList() {
        String list = String.join(",", online.keySet());
        for (ClientHandler h : online.values()) {
            try {
                h.send(new Message("USER_LIST", "server", null, list));
            } catch (IOException e) {
                System.out.println("[SERVER] Không gửi được cho một client, bỏ qua");
            }
        }
    }
}
