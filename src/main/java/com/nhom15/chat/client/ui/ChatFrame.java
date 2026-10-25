package com.nhom15.chat.client.ui;

import com.nhom15.chat.client.ChatClient;
import com.nhom15.chat.client.FileTransfer;
import com.nhom15.chat.client.SocketListenerThread;
import com.nhom15.chat.common.CryptoUtil;
import com.nhom15.chat.common.Message;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.swing.*;

public class ChatFrame extends JFrame implements SocketListenerThread.Listener {
    private static final String ALL = "Tất cả (chat nhóm)";

    private final ChatClient client;
    private final JTextArea txtChat = new JTextArea();
    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final JList<String> lstUsers = new JList<>(userModel);
    private final JTextField txtInput = new JTextField();
    private final JButton btnSend = new JButton("Gửi");
    private final JButton btnFile = new JButton("Gửi file");
    private final JProgressBar progress = new JProgressBar(0, 100);
    // file đang chờ người nhận trả lời: key = tên người nhận
    private final Map<String, File> pendingSend = new HashMap<>();
    private final Map<String, SecretKey> pendingKeys = new HashMap<>();

    public ChatFrame(ChatClient client) {
        this.client = client;
        setTitle("Chat - " + client.getUsername());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(720, 480);
        setLocationRelativeTo(null);

        txtChat.setEditable(false);
        txtChat.setLineWrap(true);
        txtChat.setWrapStyleWord(true);
        userModel.addElement(ALL);
        lstUsers.setSelectedIndex(0);
        lstUsers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane right = new JScrollPane(lstUsers);
        right.setPreferredSize(new Dimension(180, 0));
        right.setBorder(BorderFactory.createTitledBorder("Đang online"));

        JPanel bottom = new JPanel(new BorderLayout(5, 0));
        bottom.add(txtInput, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new GridLayout(1, 2, 5, 0));
        buttons.add(btnFile);
        buttons.add(btnSend);
        bottom.add(buttons, BorderLayout.EAST);
        progress.setStringPainted(true);
        progress.setVisible(false);
        JPanel south = new JPanel(new BorderLayout(0, 3));
        south.add(progress, BorderLayout.NORTH);
        south.add(bottom, BorderLayout.CENTER);

        setLayout(new BorderLayout(5, 5));
        add(new JScrollPane(txtChat), BorderLayout.CENTER);
        add(right, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        btnSend.addActionListener(e -> sendMessage());
        btnFile.addActionListener(e -> offerFile());
        txtInput.addActionListener(e -> sendMessage());
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { logoutAndExit(); }
        });

        new SocketListenerThread(client, this).start();
    }

    private void sendMessage() {
        String text = txtInput.getText().trim();
        if (text.isEmpty()) return;
        String target = lstUsers.getSelectedValue();
        try {
            if (target == null || ALL.equals(target)) {
                client.send(new Message("MSG_GROUP", client.getUsername(), "", text));
            } else {
                client.send(new Message("MSG_PRIVATE", client.getUsername(), target, text));
            }
            txtInput.setText("");
        } catch (IOException ex) {
            append("[Lỗi] Không gửi được: " + ex.getMessage());
        }
    }

    @Override
    public void onMessage(Message m) {
        String me = client.getUsername();
        switch (m.action) {
            case "MSG_GROUP":
                append(m.from + ": " + m.content);
                break;
            case "MSG_PRIVATE":
                if (me.equals(m.from)) append("Tôi → " + m.to + " (riêng): " + m.content);
                else append(m.from + " (riêng): " + m.content);
                break;
            case "USER_LIST":
                updateUsers(m.content);
                break;
            case "FILE_OFFER":
                onFileOffer(m);
                break;
            case "FILE_ACCEPT":
                onFileAccept(m);
                break;
            case "FILE_REJECT":
                pendingSend.remove(m.from);
                pendingKeys.remove(m.from);
                append("[File] " + m.from + " đã từ chối nhận file.");
                break;
            case "FILE_DONE":
                onFileDone(m);
                break;
            case "ERROR":
                append("[Lỗi] " + m.content);
                break;
            default:
                break;
        }
    }

    /** Người gửi: chọn file và gửi lời mời FILE_OFFER cho người nhận đang chọn. */
    private void offerFile() {
        String target = lstUsers.getSelectedValue();
        if (target == null || ALL.equals(target)) {
            JOptionPane.showMessageDialog(this, "Hãy chọn một người trong danh sách online để gửi file.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.isFile() || f.length() <= 0) {
            JOptionPane.showMessageDialog(this, "File rỗng hoặc không hợp lệ.");
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("fileName", f.getName());
        o.addProperty("size", f.length());
        try {
            SecretKey fileKey = CryptoUtil.newAesKey();
            o.addProperty("key", Base64.getEncoder().encodeToString(fileKey.getEncoded()));
            o.addProperty("sha256", FileTransfer.sha256(f));
            pendingSend.put(target, f);
            pendingKeys.put(target, fileKey);
            client.send(new Message("FILE_OFFER", client.getUsername(), target, o.toString()));
            append("[File] Đã gửi lời mời " + f.getName() + " (" + f.length() + " byte) tới " + target + ", chờ trả lời...");
        } catch (IOException ex) {
            pendingSend.remove(target);
            pendingKeys.remove(target);
            append("[Lỗi] Không gửi được lời mời: " + ex.getMessage());
        }
    }

    /** Người nhận: hỏi đồng ý hay không, nếu đồng ý thì chọn nơi lưu và tải về. */
    private void onFileOffer(Message m) {
        JsonObject o = JsonParser.parseString(m.content).getAsJsonObject();
        String fileName = o.get("fileName").getAsString();
        long size = o.get("size").getAsLong();
        String id = o.get("transferId").getAsString();
        final SecretKey fileKey = CryptoUtil.aesKeyFromBytes(Base64.getDecoder().decode(o.get("key").getAsString()));
        final String sha = o.get("sha256").getAsString();
        int ans = JOptionPane.showConfirmDialog(this,
                m.from + " muốn gửi file \"" + fileName + "\" (" + size + " byte). Nhận không?",
                "Nhận file", JOptionPane.YES_NO_OPTION);
        try {
            if (ans != JOptionPane.YES_OPTION) {
                client.send(new Message("FILE_REJECT", client.getUsername(), m.from, id));
                append("[File] Đã từ chối file của " + m.from);
                return;
            }
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File(fileName));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                client.send(new Message("FILE_REJECT", client.getUsername(), m.from, id));
                return;
            }
            final File dest = fc.getSelectedFile();
            client.send(new Message("FILE_ACCEPT", client.getUsername(), m.from, id));
            append("[File] Đang nhận " + fileName + " từ " + m.from + "...");
            runTransfer(() -> FileTransfer.receive(client.getHost(), client.getUsername(), id, dest, size, fileKey, sha, this::showProgress));
        } catch (IOException ex) {
            append("[Lỗi] " + ex.getMessage());
        }
    }

    /** Người gửi: nhận FILE_ACCEPT (content = transferId) thì bắt đầu đẩy byte[]. */
    private void onFileAccept(Message m) {
        File f = pendingSend.remove(m.from);
        SecretKey fileKey = pendingKeys.remove(m.from);
        if (f == null || fileKey == null) return;
        String id = m.content;
        append("[File] " + m.from + " đã đồng ý, đang gửi " + f.getName() + "...");
        runTransfer(() -> FileTransfer.send(client.getHost(), client.getUsername(), id, f, fileKey, this::showProgress));
    }

    private void onFileDone(Message m) {
        JsonObject o = JsonParser.parseString(m.content).getAsJsonObject();
        append("[File] " + o.get("fileName").getAsString() + ": " + o.get("status").getAsString());
        progress.setVisible(false);
    }

    private interface IOTask { void run() throws IOException; }

    /** Chạy truyền file ở luồng riêng để giao diện không bị đơ. */
    private void runTransfer(IOTask task) {
        progress.setValue(0);
        progress.setVisible(true);
        Thread t = new Thread(() -> {
            try {
                task.run();
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    append("[Lỗi] Truyền file thất bại: " + ex.getMessage());
                    progress.setVisible(false);
                });
            }
        }, "FileTransfer");
        t.setDaemon(true);
        t.start();
    }

    private volatile int lastPercent = -1;

    private void showProgress(long done, long total) {
        int pct = (int) (done * 100 / total);
        if (pct == lastPercent) return;
        lastPercent = pct;
        SwingUtilities.invokeLater(() -> progress.setValue(pct));
    }

    private void updateUsers(String content) {
        String selected = lstUsers.getSelectedValue();
        userModel.clear();
        userModel.addElement(ALL);
        if (content != null && !content.isEmpty()) {
            for (String u : content.split(",")) {
                if (!u.equals(client.getUsername())) userModel.addElement(u);
            }
        }
        int idx = userModel.indexOf(selected);
        lstUsers.setSelectedIndex(idx >= 0 ? idx : 0);
    }

    private void append(String line) {
        txtChat.append(line + "\n");
        txtChat.setCaretPosition(txtChat.getDocument().getLength());
    }

    @Override
    public void onDisconnected() {
        append("[Hệ thống] Mất kết nối tới Server.");
        txtInput.setEnabled(false);
        btnSend.setEnabled(false);
        btnFile.setEnabled(false);
    }

    private void logoutAndExit() {
        try { client.send(new Message("LOGOUT", client.getUsername(), "", "")); } catch (IOException ignored) { }
        client.close();
        dispose();
        System.exit(0);
    }
}
