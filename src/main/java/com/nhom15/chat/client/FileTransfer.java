package com.nhom15.chat.client;

import com.nhom15.chat.common.CryptoUtil;
import com.nhom15.chat.common.FramedIO;
import com.nhom15.chat.common.Message;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.SecretKey;

/**
 * Gửi/nhận byte[] của file qua cổng 5001 (Server chuyển tiếp từng khối).
 * Mỗi khối 8 KB được mã hóa AES-256-GCM bằng khóa riêng của lần truyền (IV mới mỗi khối)
 * và gửi dưới dạng [4 byte độ dài][IV 12 + bản mã + thẻ 16]. Bên nhận giải mã từng khối
 * và đối chiếu SHA-256 của toàn bộ file với giá trị người gửi tính trước.
 */
public final class FileTransfer {
    public static final int FILE_PORT = 5001;
    public static final int BLOCK = 8192;
    /** 4 byte độ dài + 12 byte IV + 16 byte thẻ GCM trên mỗi khối. */
    public static final int OVERHEAD = 4 + 12 + 16;

    public interface Progress {
        void onProgress(long done, long total);
    }

    private FileTransfer() {}

    /** Số byte thực sự đi trên đường truyền khi mã hóa theo từng khối. */
    public static long wireSize(long size) {
        long blocks = (size + BLOCK - 1) / BLOCK;
        return size + blocks * OVERHEAD;
    }

    private static Socket open(String host) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, FILE_PORT), 5000);
        return s;
    }

    public static String sha256(File f) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BLOCK];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Người gửi: gửi khung SEND rồi đẩy từng khối đã mã hóa. */
    public static void send(String host, String user, String transferId, File file, SecretKey key, Progress p) throws IOException {
        try (Socket s = open(host)) {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            FramedIO.send(out, new Message("SEND", user, "", transferId));
            long total = file.length();
            long done = 0;
            byte[] buf = new byte[BLOCK];
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                int n;
                while ((n = readBlock(in, buf)) > 0) {
                    byte[] plain = new byte[n];
                    System.arraycopy(buf, 0, plain, 0, n);
                    byte[] enc = CryptoUtil.aesEncrypt(key, plain);
                    out.writeInt(enc.length);
                    out.write(enc);
                    done += n;
                    p.onProgress(done, total);
                }
            } catch (GeneralSecurityException e) {
                throw new IOException("Lỗi mã hóa: " + e.getMessage(), e);
            }
            out.flush();
        }
    }

    /** Người nhận: gửi khung RECV, đọc đủ các khối, giải mã, ghi file và kiểm tra SHA-256. */
    public static void receive(String host, String user, String transferId, File dest, long size,
                               SecretKey key, String expectedSha256, Progress p) throws IOException {
        try (Socket s = open(host)) {
            s.setSoTimeout(60000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            FramedIO.send(out, new Message("RECV", user, "", transferId));
            long done = 0;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (OutputStream fo = new BufferedOutputStream(new FileOutputStream(dest))) {
                while (done < size) {
                    int len = in.readInt();
                    if (len < 28 || len > BLOCK + 28) throw new IOException("Khối dữ liệu không hợp lệ");
                    byte[] enc = new byte[len];
                    in.readFully(enc);
                    byte[] plain = CryptoUtil.aesDecrypt(key, enc);
                    fo.write(plain);
                    md.update(plain);
                    done += plain.length;
                    p.onProgress(done, size);
                }
            }
            if (!hex(md.digest()).equalsIgnoreCase(expectedSha256)) {
                dest.delete();
                throw new IOException("SHA-256 không khớp, file bị lỗi hoặc bị sửa nên đã xóa");
            }
        } catch (GeneralSecurityException e) {
            dest.delete();
            throw new IOException("Giải mã thất bại: " + e.getMessage(), e);
        } catch (IOException e) {
            dest.delete(); // mất mạng hoặc bị ngắt giữa chừng: không để lại file dở dang
            throw e;
        }
    }

    private static int readBlock(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }
}
