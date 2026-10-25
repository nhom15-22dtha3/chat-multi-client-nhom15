package com.nhom15.chat.consoleclient;

import com.nhom15.chat.client.ChatClient;
import com.nhom15.chat.common.Message;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;

/**
 * Client dong lenh dung de test Server ma khong can giao dien.
 * Chay: ConsoleClient <host> <port> <ten>
 */
public class ConsoleClient {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        String name = args.length > 2 ? args[2] : "console";
        String password = args.length > 3 ? args[3] : "123456"; // chỉ dùng để kiểm thử

        ChatClient client = new ChatClient();
        client.connect(host, port);
        String err = client.loginOrRegister(name, password);
        if (err != null) {
            System.out.println("Dang nhap that bai: " + err);
            client.close();
            return;
        }
        System.out.println("Da dang nhap voi ten " + name);
        printHelp();

        // Luong rieng doc tin tu Server va in ra man hinh
        Thread reader = new Thread(() -> {
            try {
                while (true) {
                    Message m = client.receive();
                    System.out.println("<< " + m.toJson());
                }
            } catch (IOException e) {
                System.out.println("[Mat ket noi voi Server]");
            }
        });
        reader.setDaemon(true);
        reader.start();

        Scanner sc = new Scanner(System.in, "UTF-8");
        while (sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;
            try {
                if (line.equals("/quit")) {
                    client.send(new Message("LOGOUT", name, "", ""));
                    break;
                } else if (line.startsWith("/all ")) {
                    client.send(new Message("MSG_GROUP", name, "", line.substring(5)));
                } else if (line.startsWith("/to ")) {
                    String[] p = line.split(" ", 3);
                    if (p.length < 3) { System.out.println("Cu phap: /to <ten> <noi dung>"); continue; }
                    client.send(new Message("MSG_PRIVATE", name, p[1], p[2]));
                } else if (line.startsWith("/raw ")) {
                    client.send(Message.fromJson(line.substring(5)));
                } else if (line.equals("/oversize")) {
                    testOversize(host, port);
                } else {
                    printHelp();
                }
            } catch (IOException e) {
                System.out.println("Loi gui: " + e.getMessage());
                break;
            } catch (RuntimeException e) {
                System.out.println("Loi lenh: " + e.getMessage());
            }
        }
        client.close();
        System.out.println("Da thoat.");
    }

    /** Gui do dai khung 2.000.000 (> 1.000.000) de kiem tra Server tu choi. */
    private static void testOversize(String host, int port) {
        try (Socket s = new Socket(host, port)) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeInt(2_000_000);
            out.flush();
            s.setSoTimeout(3000);
            int r = s.getInputStream().read();
            System.out.println(r == -1
                    ? "KET QUA: DAT - Server dong ket noi khi gap goi qua lon"
                    : "KET QUA: Server tra du lieu, kiem tra lai");
        } catch (IOException e) {
            System.out.println("KET QUA: DAT - Server ngat ket noi (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static void printHelp() {
        System.out.println("Lenh: /all <noi dung> | /to <ten> <noi dung> | /raw <JSON> | /oversize | /quit");
    }
}
