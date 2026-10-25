package com.nhom15.chat.server;

import com.nhom15.chat.common.FramedIO;
import com.nhom15.chat.common.Message;
import com.google.gson.JsonObject;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public class FileRelayServer {
    public static final int PORT = 5001;
    public static final long MAX_SIZE = 500L * 1024 * 1024; // 500 MB
    private static final int BUF = 8192;

    public static class Transfer {
        public final String id = UUID.randomUUID().toString().substring(0, 12);
        public final String sender, receiver, fileName;
        public final long size;
        /** Số byte thật trên đường truyền (đã gồm phần mã hóa từng khối). */
        public final long wireSize;
        public volatile boolean accepted = false;
        final CompletableFuture<Socket> receiverSocket = new CompletableFuture<>();

        Transfer(String sender, String receiver, String fileName, long size) {
            this.sender = sender; this.receiver = receiver;
            this.fileName = fileName; this.size = size;
            long blocks = (size + BUF - 1) / BUF;
            this.wireSize = size + blocks * (4 + 12 + 16);
        }
    }

    private static final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
    private static Map<String, ClientHandler> online;
    private static final ExecutorService pool = Executors.newFixedThreadPool(20);

    public static Transfer register(String sender, String receiver, String fileName, long size) {
        Transfer t = new Transfer(sender, receiver, fileName, size);
        transfers.put(t.id, t);
        return t;
    }

    public static Transfer get(String id) { return id == null ? null : transfers.get(id); }

    public static void remove(String id) { transfers.remove(id); }

    public static void start(Map<String, ClientHandler> onlineMap) {
        online = onlineMap;
        Executors.newSingleThreadExecutor().submit(FileRelayServer::acceptLoop);
    }

    private static void acceptLoop() {
        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("[FILE] Lang nghe cong " + PORT);
            while (true) {
                Socket s = server.accept();
                pool.submit(() -> handleConnection(s));
            }
        } catch (IOException e) {
            System.out.println("[FILE] Loi: " + e.getMessage());
        }
    }

    private static void handleConnection(Socket s) {
        boolean keep = false;
        try {
            s.setSoTimeout(30000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            Message h = FramedIO.receive(in);          // khung tieu de: action=SEND/RECV, from, content=transferId
            Transfer t = get(h.content);
            if (t == null || !t.accepted) return;
            if ("RECV".equals(h.action) && t.receiver.equals(h.from)) {
                t.receiverSocket.complete(s);          // giu socket, luong SEND se dong
                keep = true;
            } else if ("SEND".equals(h.action) && t.sender.equals(h.from)) {
                relay(t, in);
            }
        } catch (Exception e) {
            System.out.println("[FILE] Loi ket noi: " + e.getMessage());
        } finally {
            if (!keep) closeQuietly(s);
        }
    }

    private static void relay(Transfer t, DataInputStream in) {
        String status = "OK";
        Socket r = null;
        try {
            r = t.receiverSocket.get(30, TimeUnit.SECONDS);
            OutputStream out = new BufferedOutputStream(r.getOutputStream());
            byte[] buf = new byte[BUF];
            long remaining = t.wireSize;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new EOFException("Ben gui ngat ket noi");
                out.write(buf, 0, n);
                remaining -= n;
            }
            out.flush();
            System.out.println("[FILE] " + t.sender + " -> " + t.receiver + ": " + t.fileName + " (" + t.size + " byte, " + t.wireSize + " byte da ma hoa) xong");
        } catch (Exception e) {
            status = "FAIL: " + e.getMessage();
            System.out.println("[FILE] That bai: " + e.getMessage());
        } finally {
            closeQuietly(r);
            remove(t.id);
            notifyDone(t.sender, t, status);
            notifyDone(t.receiver, t, status);
        }
    }

    private static void notifyDone(String user, Transfer t, String status) {
        ClientHandler h = online == null ? null : online.get(user);
        if (h == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("transferId", t.id);
        o.addProperty("fileName", t.fileName);
        o.addProperty("status", status);
        try { h.send(new Message("FILE_DONE", "SERVER", user, o.toString())); } catch (Exception ignored) { }
    }

    private static void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (IOException ignored) { }
    }
}
