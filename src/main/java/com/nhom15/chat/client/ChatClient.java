package com.nhom15.chat.client;

import com.nhom15.chat.common.CryptoUtil;
import com.nhom15.chat.common.FramedIO;
import com.nhom15.chat.common.Message;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.SecretKey;

public class ChatClient {
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private String username;
    private String host;
    private SecretKey aesKey; // null cho tới khi trao đổi khóa xong

    public void connect(String host, int port) throws IOException {
        this.host = host;
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        exchangeKeys();
    }

    /**
     * Trao đổi khóa: xin khóa công khai RSA của Server, tạo khóa AES-256 ngẫu nhiên,
     * mã hóa khóa AES bằng RSA rồi gửi lên. Từ đó mọi gói tin đều mã hóa AES-GCM.
     */
    private void exchangeKeys() throws IOException {
        socket.setSoTimeout(5000);
        try {
            FramedIO.send(out, new Message("PUBKEY_REQ", "", "", ""));
            Message pub = FramedIO.receive(in);
            if (!"PUBKEY".equals(pub.action)) throw new IOException("Server không gửi khóa công khai");
            SecretKey key = CryptoUtil.newAesKey();
            byte[] enc = CryptoUtil.rsaEncrypt(CryptoUtil.publicKeyFromBase64(pub.content), key.getEncoded());
            FramedIO.send(out, new Message("SESSION_KEY", "", "", Base64.getEncoder().encodeToString(enc)));
            Message ok = FramedIO.receive(in);
            if (!"SECURE_OK".equals(ok.action)) throw new IOException("Server từ chối khóa phiên");
            aesKey = key;
        } catch (GeneralSecurityException e) {
            throw new IOException("Trao đổi khóa thất bại", e);
        } finally {
            socket.setSoTimeout(0);
        }
    }

    public boolean isSecure() { return aesKey != null; }

    /** Trả về null nếu đăng ký thành công, ngược lại trả về lý do thất bại. */
    public String register(String name, String password) throws IOException {
        send(new Message("REGISTER", name, "", password));
        while (true) {
            Message m = receive();
            if ("REGISTER_OK".equals(m.action)) return null;
            if ("REGISTER_FAIL".equals(m.action)) return m.content;
        }
    }

    /** Trả về null nếu đăng nhập thành công, ngược lại trả về lý do thất bại. */
    public String login(String name, String password) throws IOException {
        send(new Message("LOGIN", name, "", password));
        while (true) {
            Message m = receive();
            if ("LOGIN_OK".equals(m.action)) {
                username = m.to; // tên hiển thị do Server xác nhận
                return null;
            }
            if ("LOGIN_FAIL".equals(m.action)) return m.content;
        }
    }

    /** Tiện cho Console Client và kiểm thử: chưa có tài khoản thì tự đăng ký rồi đăng nhập. */
    public String loginOrRegister(String name, String password) throws IOException {
        register(name, password); // đã tồn tại thì bỏ qua lỗi, thử đăng nhập
        return login(name, password);
    }

    public synchronized void send(Message m) throws IOException {
        FramedIO.send(out, m, aesKey);
    }

    public Message receive() throws IOException {
        return FramedIO.receive(in, aesKey);
    }

    public String getUsername() { return username; }

    public String getHost() { return host; }

    public void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }
}
