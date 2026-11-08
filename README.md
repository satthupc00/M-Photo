# M Photo

App xem ảnh, video cho Samsung Galaxy Z Fold 7 (Android), Created by Mondiro.

- Xem theo **Album** (mọi thư mục có ảnh) hoặc **Thư mục** (đi từng cấp)
- Ghim sao, ẩn thư mục (mở bằng vân tay), loại trừ thư mục hệ thống / icon / sticker
- Đánh dấu thư mục sync lên Google Photos
- Tự kiểm tra & cài bản mới từ GitHub Releases

## Cài đặt
Tải file `MPhoto-vX.Y.Z.apk` ở mục **Releases** về điện thoại và mở để cài.

## Phát hành bản mới
1. Tăng `versionCode` / `versionName` trong `app/build.gradle.kts`
2. Cập nhật `app/src/main/assets/whatsnew.json` và `release-notes.md`
3. Push tag `vX.Y.Z` → GitHub Actions tự build và tạo Release.

Khoá ký APK nằm trong GitHub Secrets `MPHOTO_KEYSTORE_B64` và `MPHOTO_KEY_PASSWORD`.
