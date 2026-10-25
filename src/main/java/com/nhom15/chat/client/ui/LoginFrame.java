package com.nhom15.chat.client.ui;

import com.nhom15.chat.client.ChatClient;
import java.io.IOException;
import javax.swing.*;
import java.awt.*;

/** Cửa sổ đăng nhập: nhập tên người dùng, IP và cổng của server. */
public class LoginFrame extends JFrame {
    private final JTextField txtUser = new JTextField(15);
    private final JPasswordField txtPass = new JPasswordField(15);
    private final JTextField txtHost = new JTextField("localhost", 15);
    private final JTextField txtPort = new JTextField("5000", 15);
    private final JButton btnLogin = new JButton("Đăng nhập");
    private final JButton btnRegister = new JButton("Đăng ký");
    private final JLabel lblStatus = new JLabel(" ");

    public LoginFrame() {
        super("Chat - Đăng nhập");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JPanel form = new JPanel(new GridLayout(4, 2, 8, 8));
        form.add(new JLabel("Tên người dùng:"));
        form.add(txtUser);
        form.add(new JLabel("Mật khẩu:"));
        form.add(txtPass);
        form.add(new JLabel("IP Server:"));
        form.add(txtHost);
        form.add(new JLabel("Cổng:"));
        form.add(txtPort);

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
        buttons.add(btnLogin);
        buttons.add(btnRegister);
        bottom.add(buttons, BorderLayout.CENTER);
        bottom.add(lblStatus, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        root.add(form, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().setDefaultButton(btnLogin);
        pack();
        setLocationRelativeTo(null);

        btnLogin.addActionListener(e -> submit(false));
        btnRegister.addActionListener(e -> submit(true));
    }

    /** register = true: đăng ký tài khoản mới rồi đăng nhập luôn. */
    private void submit(boolean register) {
        String user = txtUser.getText().trim();
        String host = txtHost.getText().trim();
        int port;
        try {
            port = Integer.parseInt(txtPort.getText().trim());
        } catch (NumberFormatException ex) {
            lblStatus.setText("Cổng phải là số");
            return;
        }
        final String fPass = new String(txtPass.getPassword());
        if (user.isEmpty() || host.isEmpty() || fPass.isEmpty()) {
            lblStatus.setText("Vui lòng nhập đủ tên, mật khẩu và IP");
            return;
        }

        final String fUser = user;
        final String fHost = host;
        final int fPort = port;
        btnLogin.setEnabled(false);
        btnRegister.setEnabled(false);
        lblStatus.setText("Đang kết nối...");

        // Kết nối và đăng nhập chạy nền để không làm đứng giao diện
        new SwingWorker<String, Void>() {
            private final ChatClient client = new ChatClient();

            @Override
            protected String doInBackground() {
                try {
                    client.connect(fHost, fPort);
                    if (register) {
                        String err = client.register(fUser, fPass);
                        if (err != null) return err;
                    }
                    return client.login(fUser, fPass);
                } catch (IOException e) {
                    return "Không kết nối được: " + e.getMessage();
                }
            }

            @Override
            protected void done() {
                try {
                    String err = get();
                    if (err == null) {
                        new ChatFrame(client).setVisible(true);
                        dispose();
                    } else {
                        client.close();
                        lblStatus.setText(err);
                        btnLogin.setEnabled(true);
                        btnRegister.setEnabled(true);
                    }
                } catch (Exception e) {
                    client.close();
                    lblStatus.setText("Lỗi: " + e.getMessage());
                    btnLogin.setEnabled(true);
                    btnRegister.setEnabled(true);
                }
            }
        }.execute();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }
}
