# AllTrans

[English](README_en.md)

AllTrans là một module Xposed/LSPosed giúp dịch giao diện ứng dụng Android ngay khi ứng dụng đang chạy. Bạn không cần chỉnh sửa APK, không cần sao chép văn bản sang ứng dụng khác — văn bản trên màn hình sẽ được thay bằng bản dịch theo ngôn ngữ bạn chọn.

## Tổng quan

- **Tên gói:** `chanhnh.alltrans`
- **Phiên bản:** `3.0.0` (`versionCode` 300)
- **Android tối thiểu:** Android 10 (API 29)
- **Compile/target SDK:** 36
- **Nền tảng:** LSPosed 2.1+ hoặc môi trường Xposed tương thích libxposed API
- **Giao diện:** Material 3, hỗ trợ sáng/tối
- **Ngôn ngữ giao diện:** English, Tiếng Việt, 简体中文, 繁體中文 (Đài Loan)
- **Dịch vụ dịch:** Google Translate, Microsoft Translate, VietPhrase

Cài đặt mặc định:

- Dịch vụ dịch: `Google Translate`
- Ngôn ngữ nguồn: `Tự nhận diện`
- Ngôn ngữ đích: `Tiếng Việt`

## Tính năng

- Dịch văn bản trong ứng dụng theo thời gian thực, không cần sửa APK
- Dịch văn bản thường, gợi ý trong ô nhập (`setHint`), thông báo và nội dung `WebView`
- Dịch mở rộng cho View tùy chỉnh và nội dung được vẽ ngoài `TextView`
- Cài đặt toàn cục và ghi đè riêng cho từng ứng dụng
- Chọn ngôn ngữ nguồn/đích linh hoạt; hỗ trợ tự nhận diện ngôn ngữ
- Bộ nhớ đệm bản dịch giúp giảm yêu cầu lặp lại và tăng tốc hiển thị
- Tự động đánh dấu xoá bộ nhớ đệm khi đổi dịch vụ dịch, đổi ngôn ngữ hoặc thay đổi engine dịch
- `VietPhrase` dịch offline từ tiếng Trung sang tiếng Việt bằng từ điển cục bộ
- Bộ quy tắc `VietPhrase` xử lý tên riêng, đại từ, số/ngày, nhãn chương và định dạng câu
- Chế độ gỡ lỗi ghi log ra `logcat` để thuận tiện báo lỗi

## Yêu cầu

| Thành phần | Yêu cầu |
| --- | --- |
| Thiết bị | Android 10 trở lên (API 29+) |
| Module | LSPosed 2.1+ hoặc môi trường Xposed tương thích libxposed API |
| Quyền hệ thống | Thiết bị đã cài LSPosed (thường cần root/Magisk, KernelSU hoặc APatch) |
| Internet | Cần cho Google Translate và Microsoft Translate; không cần cho VietPhrase |
| Build | JDK 17, Android SDK 36 |

## Cài đặt

1. Build hoặc tải APK của AllTrans.
2. Cài APK trên thiết bị.
3. Mở LSPosed, bật module **AllTrans**.
4. Chọn phạm vi ứng dụng cần dịch trong mục **Scope**.
5. Khởi động lại thiết bị hoặc buộc dừng các ứng dụng đích.
6. Mở AllTrans và cấu hình dịch vụ dịch, ngôn ngữ nguồn/đích.

> Nếu ứng dụng đích không truy vấn được provider trực tiếp, có thể chọn thêm **System Framework** trong phạm vi module để dùng cơ chế proxy.

## Sử dụng

### Cài đặt toàn cục

1. Mở tab **Toàn cục**.
2. Chọn **Dịch vụ dịch**.
3. Chọn **Ngôn ngữ nguồn** và **Ngôn ngữ đích**.
4. Bật **Công tắc chính**.
5. Mở tab **Ứng dụng**, bật dịch cho các ứng dụng mong muốn.
6. Buộc dừng và mở lại ứng dụng đích.

### Cài đặt riêng cho từng ứng dụng

1. Mở ứng dụng cần cấu hình trong tab **Ứng dụng**.
2. Bật **Dịch ứng dụng này**.
3. Bật **Ghi đè cài đặt toàn cục** nếu muốn dùng cấu hình riêng.
4. Chọn dịch vụ dịch, ngôn ngữ và các tuỳ chọn nâng cao.
5. Buộc dừng ứng dụng đích để áp dụng.

### Các cài đặt chính

| Cài đặt | Mô tả |
| --- | --- |
| Công tắc chính | Bật/tắt toàn bộ module |
| Dịch vụ dịch | Chọn Google Translate, Microsoft Translate hoặc VietPhrase |
| Ngôn ngữ nguồn | Ngôn ngữ hiện tại của ứng dụng; có thể tự nhận diện |
| Ngôn ngữ đích | Ngôn ngữ bạn muốn dịch sang |
| Dịch văn bản thường | Dịch văn bản được đặt bằng `setText` |
| Dịch gợi ý văn bản | Dịch văn bản được đặt bằng `setHint` |
| Dịch mở rộng cho View | Bật thêm hook cho View tùy chỉnh, hàm khởi tạo layout và phương thức View của framework |
| Dịch WebView | Dịch nội dung bên trong `WebView` |
| Dịch thông báo | Dịch văn bản trong thông báo |
| Bật bộ nhớ đệm | Lưu bản dịch để tái sử dụng |
| Độ trễ | Thời gian chờ trước khi thay thế văn bản bằng bản dịch |
| Độ trễ WebView | Thời gian chờ trước khi bắt đầu dịch WebView |
| Tự cuộn bản dịch dài | Tự cuộn nếu bản dịch dài hơn vùng hiển thị |
| Chế độ gỡ lỗi | Ghi log chi tiết ra `logcat` |

## Dịch vụ dịch

| Dịch vụ | ID | Internet | Ghi chú |
| --- | --- | --- | --- |
| Google Translate | `g` | Có | Dùng danh sách mã ngôn ngữ của Google |
| Microsoft Translate | `edge` | Có | Dùng endpoint Edge và mã ngôn ngữ native của Microsoft/Edge |
| VietPhrase | `vietphrase` | Không | Chỉ hỗ trợ `zh → vi`, dùng từ điển cục bộ trong `assets/vietphrase` |

### Google Translate

Phù hợp cho nhu cầu dịch tổng quát với danh sách ngôn ngữ rộng. Cần kết nối Internet.

### Microsoft Translate

Dùng endpoint Edge và mã ngôn ngữ native của Edge. Khi đổi giữa Google Translate và Microsoft Translate, danh sách ngôn ngữ sẽ được cập nhật tương ứng. Nếu ngôn ngữ đang chọn không có trong danh sách mới, AllTrans sẽ đặt lại về ngôn ngữ mặc định thay vì gửi mã không hợp lệ.

### VietPhrase

- Chỉ dịch từ tiếng Trung (`zh`) sang tiếng Việt (`vi`)
- Dùng từ điển cục bộ, không cần Internet
- Bộ quy tắc xử lý tên riêng, đại từ, số/ngày, nhãn chương và định dạng câu
- Phù hợp khi cần dịch nhanh, ổn định và hoạt động ngoại tuyến
- Bộ quy tắc và một số dữ liệu đại từ được chuyển từ dự án `vietphrase-translator` của duongden (GPL-3.0)

## Bộ nhớ đệm

- Bộ nhớ đệm lưu bản dịch theo văn bản để tránh gọi lại dịch vụ nhiều lần
- Có thể xoá thủ công trong cài đặt toàn cục hoặc cài đặt riêng của ứng dụng
- AllTrans tự đánh dấu xoá bộ nhớ đệm khi đổi dịch vụ dịch hoặc ngôn ngữ
- Bộ nhớ đệm cũng được làm mới khi phiên bản engine dịch thay đổi
- Sau khi xoá bộ nhớ đệm, cần buộc dừng ứng dụng đích để áp dụng

## Ngôn ngữ

Giao diện AllTrans có 4 ngôn ngữ:

- English
- Tiếng Việt
- 简体中文
- 繁體中文 (Đài Loan)

Danh sách ngôn ngữ dịch phụ thuộc vào dịch vụ đang chọn. Riêng VietPhrase chỉ hỗ trợ cặp `zh → vi`.

## Quyền riêng tư

- AllTrans không có máy chủ trung gian riêng và không thu thập dữ liệu phân tích.
- Khi dùng Google Translate hoặc Microsoft Translate, văn bản cần dịch được gửi tới dịch vụ tương ứng.
- VietPhrase xử lý hoàn toàn cục bộ trên thiết bị.
- Quyền `QUERY_ALL_PACKAGES` được dùng để hiển thị danh sách ứng dụng cho bạn chọn.
- Quyền `INTERNET` được dùng cho các dịch vụ dịch trực tuyến.

## Cơ chế hoạt động

- Hook các điểm hiển thị văn bản như `TextView.setText`, `setHint`, `StaticLayout.Builder.build()`, hàm khởi tạo layout, phương thức View của framework và setter của View tùy chỉnh.
- Hỗ trợ dịch thông báo và nội dung `WebView`.
- Mỗi provider được gọi qua `ContentProvider` nội bộ:
  - `content://chanhnh.alltrans.GtransProvider`
  - `content://chanhnh.alltrans.EdgeProvider`
  - `content://chanhnh.alltrans.VietPhraseProvider`
- Provider được chọn sẽ được dùng cố định. Nếu provider không khả dụng hoặc trả kết quả rỗng, AllTrans giữ nguyên văn bản gốc.
- Query provider ưu tiên đi qua proxy, sau đó fallback sang query trực tiếp.

## Build

Dự án dùng Gradle Android application module, Kotlin và Java 17.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

APK đầu ra:

- `app/build/outputs/apk/debug/AllTrans-v3.0.0-debug.apk`
- `app/build/outputs/apk/release/AllTrans-v3.0.0-release.apk`

Để ký bản release, cấu hình các thuộc tính sau trong `gradle.properties`:

```properties
RELEASE_STORE_FILE=/duong/dan/den/keystore.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

## Cấu trúc dự án

| Thành phần | Vai trò |
| --- | --- |
| `Alltrans.kt` | Entry point của module, hook proxy SettingsProvider |
| `AttachBaseContextHookHandler.kt` | Đọc preference, quản lý cache và áp dụng hook |
| `GetTranslateToken.kt` | Điều phối provider, xử lý cache và fallback |
| `EdgeProvider.kt` | Microsoft Translate |
| `GtransProvider.kt` | Google Translate |
| `VietPhraseProvider.kt` | ContentProvider cho VietPhrase |
| `VietPhraseTranslator.kt` | Nạp từ điển và engine VietPhrase |
| `VietPhraseTranslationEngine.kt` | Pipeline dịch và rule engine |
| `PreferenceList.kt` | Đọc preference toàn cục/riêng ứng dụng |
| `GlobalPreferencesFragment.kt` | Giao diện cài đặt toàn cục |
| `LocalPreferenceFragment.kt` | Giao diện cài đặt theo ứng dụng |
| `app/src/main/assets/vietphrase/` | Từ điển và dữ liệu VietPhrase |

## Khắc phục sự cố

**Ứng dụng không được dịch**

- Kiểm tra module AllTrans đã bật trong LSPosed chưa.
- Kiểm tra ứng dụng đích đã nằm trong Scope chưa.
- Kiểm tra **Công tắc chính** và **Dịch ứng dụng này** đã bật chưa.
- Buộc dừng rồi mở lại ứng dụng đích.
- Thử bật **Chế độ gỡ lỗi** và xem log `logcat`.

**Google Translate hoặc Microsoft Translate không hoạt động**

- Kiểm tra kết nối Internet.
- Thử đổi giữa Google Translate và Microsoft Translate.
- Nếu dùng proxy, kiểm tra cấu hình proxy của hệ thống.

**VietPhrase không dịch**

- VietPhrase chỉ hỗ trợ `zh → vi`.
- Kiểm tra từ điển trong `assets/vietphrase` có đầy đủ không.

**Một số nội dung vẫn không dịch**

- Game hoặc ứng dụng vẽ văn bản trực tiếp bằng canvas thường không hook được.
- Thử bật **Dịch mở rộng cho View**.
- Thử tăng **Độ trễ** hoặc **Độ trễ WebView**.

**Ứng dụng đích bị lỗi sau khi dịch**

- Tắt **Tự cuộn bản dịch dài**.
- Tắt **Dịch thông báo** hoặc **Dịch WebView** nếu không cần.
- Xoá bộ nhớ đệm và buộc dừng ứng dụng đích.

## Đóng góp

- Fork repository, tạo nhánh mới và gửi pull request.
- Khi báo lỗi, kèm theo:
  - Phiên bản Android
  - Phiên bản LSPosed
  - Tên và phiên bản ứng dụng đích
  - Log `logcat` với tag `AllTrans`
  - Các bước tái hiện lỗi

## Ghi công

- Dự án gốc: [akhilkedia/AllTrans](https://github.com/akhilkedia/AllTrans)
- Bộ quy tắc và dữ liệu VietPhrase tham khảo từ [duongden/script-vietphrase-translator](https://github.com/duongden/script-vietphrase-translator)

## Giấy phép

GPL-3.0-or-later. Xem file `LICENSE.GPL` để biết chi tiết.
