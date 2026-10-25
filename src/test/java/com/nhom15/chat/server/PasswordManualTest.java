package com.nhom15.chat.server;

import com.nhom15.chat.client.ChatClient;

/** Test thủ công đăng ký và đăng nhập có mật khẩu. Cần ChatServer đang chạy (xóa users.json trước khi chạy). */
public class PasswordManualTest {
    static int fails = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "DAT   " : "SAI   ") + what);
        if (!ok) fails++;
    }

    static ChatClient conn() throws Exception {
        ChatClient c = new ChatClient();
        c.connect("localhost", 5000);
        return c;
    }

    public static void main(String[] args) throws Exception {
        ChatClient a = conn();
        check("Đăng ký 'Hoàng' với mật khẩu hợp lệ", a.register("Hoàng", "matkhau1") == null);
        ChatClient b = conn();
        for (String n : new String[]{"Hoang", "hoàng", "HOANG", "HOÀNG"}) {
            check("Đăng ký '" + n + "' bị từ chối (trùng 'Hoàng')", b.register(n, "matkhau2") != null);
        }
        check("Mật khẩu quá ngắn bị từ chối", b.register("Binh", "ab") != null);
        check("Tên chứa dấu phẩy bị từ chối", b.register("a,b", "matkhau1") != null);
        check("Đăng nhập sai mật khẩu bị từ chối", b.login("Hoàng", "saimk") != null);
        check("Đăng nhập tên chưa đăng ký bị từ chối", b.login("Khongco", "matkhau1") != null);
        check("Đăng nhập 'HOANG' + đúng mật khẩu thành công", b.login("HOANG", "matkhau1") == null);
        check("Tên hiển thị là 'Hoàng' (đúng lúc đăng ký)", "Hoàng".equals(b.getUsername()));
        ChatClient c = conn();
        check("Đăng nhập lần hai cùng tài khoản bị từ chối", c.login("Hoàng", "matkhau1") != null);
        a.close(); b.close(); c.close();
        System.out.println(fails == 0 ? "KET QUA: DAT" : "KET QUA: SAI " + fails + " muc");
        System.exit(fails == 0 ? 0 : 1);
    }
}
