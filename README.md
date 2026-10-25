# Hệ thống Chat Đa Client (Desktop) - Nhóm 15, Lớp 22DTHA3

Ứng dụng chat Client-Server viết bằng Java Socket TCP (không dùng framework mạng), giao thức JSON tùy biến
có tiền tố độ dài 4 byte, truyền file bằng byte[] qua cổng riêng, mã hóa kênh chat bằng RSA-2048 và AES-256-GCM.

Môn học: Lập trình mạng máy tính. Giảng viên: ThS. Nguyễn Lê Văn.

## Thành viên và bảng đóng góp

| MSSV | Họ và Tên | Công việc đã thực hiện | Issue | Pull Request |
|---|---|---|---|---|
| 2280601029 | Huỳnh Thanh Hoàng | Server đa luồng (ExecutorService), đăng nhập và danh sách online, chat 1-1 và chat nhóm, máy chủ chuyển tiếp file cổng 5001, kiểm thử nhiều client, file lớn và demo qua Tailscale | #2, #3, #6, #7, #11 | #14, #15, #16, #19, #23 |
| 2280601574 | Trần Đình Khôi | Giao diện Swing: form đăng nhập, ChatClient, luồng nhận tin, ChatFrame, gửi/nhận file với thanh tiến trình, báo cáo và README | #4, #5, #8, #12 | #17, #18, #21, #24 |
| 2280600515 | Lê Toàn Dương | Đóng gói tin có tiền tố độ dài (FramedIO), Console Test Client, trao đổi khóa RSA và mã hóa AES-GCM | #1, #9, #10 | #13, #20, #22 |

## Kiến trúc

```
Client (Swing)                        Server
  LoginFrame, ChatFrame      cổng 5000: JSON, mã hóa AES-GCM        ChatServer (pool 50 luồng)
  ChatClient, FileTransfer  --------------------------------------> ClientHandler (mỗi client một luồng)
                             cổng 5001: byte[] của file             FileRelayServer (pool 20 luồng)
```

- Cổng **5000** (điều khiển): đăng nhập, danh sách online, chat, lời mời gửi file. Mọi gói là JSON, có tiền tố 4 byte độ dài.
- Cổng **5001** (file): Server nhận byte từ người gửi và chuyển tiếp từng khối 8 KB (đã mã hóa AES-GCM, dạng `[4 byte độ dài][IV + bản mã + thẻ]`) cho người nhận, không lưu cả file trong bộ nhớ. Người nhận giải mã từng khối và so SHA-256 của file với giá trị người gửi đã tính.
- Server lưu danh sách người online trong ConcurrentHashMap, dùng putIfAbsent để hai người trùng tên không cùng đăng nhập thành công.

## Giao thức

Mỗi gói: `[4 byte độ dài][JSON]`. Độ dài tối đa 1.000.000 byte, quá giới hạn thì Server ngắt kết nối.
JSON có các trường `action`, `from`, `to`, `content`, `time`. Trường `from` do Server tự gán, client không giả mạo được.

| action | Hướng | Ý nghĩa |
|---|---|---|
| PUBKEY_REQ, PUBKEY, SESSION_KEY, SECURE_OK | Client và Server | Trao đổi khóa, chạy trước khi đăng nhập |
| LOGIN, LOGIN_OK, LOGIN_FAIL | Client và Server | Đăng nhập (tên 1-20 ký tự, không trùng, không có dấu phẩy) |
| USER_LIST | Server tới Client | Danh sách người online, cách nhau bằng dấu phẩy |
| MSG_PRIVATE, MSG_GROUP | Client và Server | Chat 1-1 và chat nhóm |
| FILE_OFFER | Client tới Server tới Client | Lời mời gửi file `{fileName, size}`, Server thêm `transferId` |
| FILE_ACCEPT, FILE_REJECT | Người nhận tới Server tới người gửi | Đồng ý hoặc từ chối, content là `transferId` |
| FILE_DONE | Server tới cả hai | Kết quả truyền file `{transferId, fileName, status}` |
| ERROR, LOGOUT | Hai chiều | Báo lỗi, thoát |

Truyền file trên cổng 5001: mỗi bên mở một kết nối, gửi khung đầu tiên `SEND` (người gửi) hoặc `RECV` (người nhận) với
content là `transferId`. Server chờ đủ hai bên (tối đa 30 giây), rồi chuyển tiếp đúng `size` byte. Kích thước file tối đa 500 MB.

## Bảo mật

1. Server tạo cặp khóa **RSA-2048** mới mỗi lần chạy, không có khóa nào viết cứng trong mã nguồn.
2. Client xin khóa công khai, tạo khóa **AES-256** ngẫu nhiên, mã hóa bằng RSA (OAEP SHA-256) rồi gửi lên.
3. Từ đó mọi gói trên cổng 5000 được mã hóa **AES-256-GCM**, mỗi gói có IV 12 byte ngẫu nhiên và thẻ xác thực.
   Gói bị sửa dù 1 byte sẽ giải mã thất bại và Server ngắt kết nối.
4. Cổng file 5001 mã hóa từng khối 8 KB bằng AES-256-GCM với khóa riêng của mỗi lần truyền (khóa và SHA-256 nằm trong `FILE_OFFER`, Server vẫn thấy khóa nên không phải mã hóa đầu cuối).

## Cách chạy

Yêu cầu: JDK 17 trở lên, thư viện Gson 2.10.1 (Maven tải về `.m2`).

Biên dịch (Git Bash, đứng ở thư mục repo):

    GSON="$USERPROFILE/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"
    mkdir -p target/classes
    javac -encoding UTF-8 -cp "$GSON" -d target/classes $(find src/main/java -name "*.java")

Chạy theo thứ tự:

    # 1. Server (cổng 5000 và 5001)
    java -Dstdout.encoding=UTF-8 -cp "target/classes;$GSON" com.nhom15.chat.server.ChatServer

    # 2. Client giao diện, chạy nhiều lần, mỗi lần một tên khác nhau
    java -cp "target/classes;$GSON" com.nhom15.chat.client.ui.LoginFrame

Ở cửa sổ đăng nhập nhập tên, địa chỉ Server (`localhost` nếu cùng máy) và cổng `5000`.
Chọn một người trong danh sách để chat riêng, chọn "Tất cả (chat nhóm)" để chat nhóm, nút "Gửi file" để gửi file cho người đang chọn.

Chạy qua nhiều máy: dùng Tailscale, Server mở cổng 5000 và 5001 trên tường lửa, client nhập IP Tailscale của máy Server (dạng `100.x.x.x`).

## Console Test Client

Dùng để thử Server không cần giao diện:

    java -cp "target/classes;$GSON" com.nhom15.chat.consoleclient.ConsoleClient <host> <port> <ten>

Lệnh: `/all <nội dung>`, `/to <tên> <nội dung>`, `/raw <JSON>`, `/oversize` (thử gói lớn hơn 1 MB), `/quit`.

## Kết quả kiểm thử

| Kịch bản | Cách chạy | Kết quả |
|---|---|---|
| 10 client đăng nhập cùng lúc, mỗi client gửi 1 tin nhóm | `bash scripts/multi-client-test.sh localhost 10` | Cả 10 client nhận đủ 10 tin, mỗi phiên có kênh AES-256-GCM riêng. Đạt |
| Gửi file 200 MB giữa hai client | Giao diện chat, cổng 5001 | Hai bên cùng báo OK, kích thước file nhận bằng file gốc. Đạt |
| Gói tin vượt giới hạn 1 MB | `/oversize` trong Console Client | Server ngắt kết nối, không bị sập. Đạt |
| Mã hóa: giải mã đúng, IV khác nhau, sửa 1 byte bị phát hiện, sai khóa thất bại | `CryptoManualTest` | KET QUA: DAT |
| Ba máy qua mạng Tailscale | Server ở máy nhóm trưởng, hai máy còn lại nhập IP 100.120.1.21 | Chat nhóm, chat riêng và gửi file 139 MB (145.716.664 byte) đều chạy tốt, mỗi kết nối dùng kênh AES-256-GCM riêng. Đạt |

## Quy trình làm việc trên Git

- Mỗi công việc là một Issue, có người thực hiện, nhãn và cột mốc.
- Nhánh `main` được bảo vệ. Làm việc trên nhánh `feature/<MSSV>-issue-<số>-<tên>`.
- Commit theo Conventional Commits (`feat`, `fix`, `refactor`, `docs`, `test`), mỗi người commit từ máy và tài khoản của mình.
- Mỗi Pull Request có mô tả, ảnh chụp chạy thành công và ít nhất một thành viên khác Approve trước khi gộp.
