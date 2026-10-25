package com.nhom15.chat.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nhom15.chat.client.ChatClient;
import com.nhom15.chat.client.FileTransfer;
import com.nhom15.chat.common.CryptoUtil;
import com.nhom15.chat.common.Message;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.*;
import javax.crypto.SecretKey;

/**
 * Test thủ công: A gửi file 5 MB cho B qua cổng 5001 (mã hóa AES-GCM từng khối),
 * B đối chiếu SHA-256. Có thêm ca sai SHA-256 phải bị từ chối. Cần ChatServer đang chạy.
 */
public class FileRelayManualTest {
    static Message waitFor(ChatClient c, String action) throws Exception {
        while (true) {
            Message m = c.receive();
            if (action.equals(m.action)) return m;
        }
    }

    static boolean run(ChatClient a, ChatClient b, File src, boolean wrongHash) throws Exception {
        SecretKey key = CryptoUtil.newAesKey();
        JsonObject offer = new JsonObject();
        offer.addProperty("fileName", src.getName());
        offer.addProperty("size", src.length());
        offer.addProperty("key", Base64.getEncoder().encodeToString(key.getEncoded()));
        offer.addProperty("sha256", wrongHash ? "00".repeat(32) : FileTransfer.sha256(src));
        a.send(new Message("FILE_OFFER", "A", "B", offer.toString()));

        Message got = waitFor(b, "FILE_OFFER");
        JsonObject o = JsonParser.parseString(got.content).getAsJsonObject();
        String id = o.get("transferId").getAsString();
        b.send(new Message("FILE_ACCEPT", "B", "A", id));
        waitFor(a, "FILE_ACCEPT");

        File dest = File.createTempFile("recv", ".bin");
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<Boolean> recv = ex.submit(() -> {
            try {
                FileTransfer.receive("localhost", "B", id, dest, src.length(), key,
                        o.get("sha256").getAsString(), (d, t) -> { });
                return true;
            } catch (Exception e) {
                System.out.println("B: " + e.getMessage());
                return false;
            }
        });
        FileTransfer.send("localhost", "A", id, src, key, (d, t) -> { });
        System.out.println("A: FILE_DONE = " + waitFor(a, "FILE_DONE").content);
        boolean ok = recv.get(30, TimeUnit.SECONDS);
        ex.shutdown();
        return ok && Files.mismatch(src.toPath(), dest.toPath()) == -1;
    }

    public static void main(String[] args) throws Exception {
        byte[] data = new byte[5_000_000];
        new Random(1).nextBytes(data);
        File src = File.createTempFile("src", ".bin");
        Files.write(src.toPath(), data);

        ChatClient a = new ChatClient(), b = new ChatClient();
        a.connect("localhost", 5000);
        b.connect("localhost", 5000);
        a.loginOrRegister("A", "123456");
        b.loginOrRegister("B", "123456");

        System.out.println(run(a, b, src, false) ? "KET QUA 1: DAT - file nhan giong file goc" : "KET QUA 1: SAI");
        System.out.println(!run(a, b, src, true) ? "KET QUA 2: DAT - sai SHA-256 bi tu choi" : "KET QUA 2: SAI");
        a.close();
        b.close();
        System.exit(0);
    }
}
