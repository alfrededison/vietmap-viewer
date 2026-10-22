# VM Viewer

App Android xem lại và tải video từ camera hành trình **Vietmap C61 Pro** qua WiFi — thay thế cho app Vietmap REC (chậm, giật, tải video rất lâu).

## Vì sao viết app này

Vietmap REC 3.9.3 sau khi decompile cho thấy:

| Vấn đề của app gốc | VM Viewer xử lý |
|---|---|
| Tải lỗi giữa chừng → xoá file, tải lại từ byte 0 (`ProgressDownloader`) | Tiếp tục bằng HTTP `Range`, retry có backoff |
| Glide bắn hàng loạt request thumbnail `cmd=4001` cùng lúc vào web server yếu của camera | Coil với OkHttp dispatcher giới hạn **1 request** + disk cache |
| ijkplayer cấu hình mặc định, buffer ít | Media3/ExoPlayer, buffer trước 3s, giữ tới 45s |
| Android hay đẩy traffic sang 4G vì WiFi camera không có Internet | `requestNetwork` + `bindProcessToNetwork` vào WiFi camera |

Đo thực tế: WiFi camera ~**3 MB/s (25 Mbps)**, video 2K ~**20 Mbps** → file 1 phút (~140 MB) tải ~45 giây.

## Tính năng

- Tự nhận WiFi camera, chuyển camera sang chế độ xem lại
- Danh sách video chia tab **Video** / **Sự kiện** (thư mục `RO`, khoá chống ghi đè), nhóm theo ngày
- Phát trực tiếp qua WiFi, tua được
- Tải về `Movies/VMViewer`, có tiến độ và tốc độ, nhận biết file đã tải trước đó
- Xoá video thường trên camera (video sự kiện không xoá được)
- **An toàn:** rời app → camera tự về chế độ ghi hình (nếu đang tải thì đợi tải xong)

## Yêu cầu

- Android 10+ (minSdk 29)
- Camera Vietmap chip Novatek (đã thử trên C61 Pro; các dòng Vietmap Novatek khác có thể dùng được)

## Build

```bash
# JDK 17+ (có thể dùng JBR của Android Studio)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # bản thu gọn ~3.7 MB, đang ký bằng debug key
./gradlew installDebug       # cài lên máy đang cắm USB
```

Cần `local.properties` trỏ tới Android SDK (Android Studio tự tạo):

```
sdk.dir=/Users/<you>/Library/Android/sdk
```

## Sử dụng

1. Bật camera, trên điện thoại kết nối WiFi `VM_C61Pro…`
2. Mở VM Viewer → app tự kết nối và hiện danh sách video
3. Chạm video để xem, bấm ⬇ để tải, 🗑 để xoá
4. Xong việc bấm **Ghi hình** (hoặc chỉ cần thoát app) để camera ghi tiếp

## Thử API bằng curl

```bash
curl "http://192.168.1.254/?custom=1&cmd=2001&par=0"   # dừng ghi (-22 nếu đang không ghi: bình thường)
curl "http://192.168.1.254/?custom=1&cmd=3001&par=2"   # chế độ xem lại
curl "http://192.168.1.254/?custom=1&cmd=3015"         # danh sách file (XML)
curl -o x.mp4 "http://192.168.1.254/VIETMAP/Movie/<NAME>.MP4"
curl "http://192.168.1.254/?custom=1&cmd=3001&par=1"   # về chế độ quay
curl "http://192.168.1.254/?custom=1&cmd=2001&par=1"   # ghi tiếp
```

Chi tiết giao thức và kiến trúc: xem [CLAUDE.md](CLAUDE.md).

## Lưu ý

- Dự án cá nhân, không liên quan Vietmap. Giao thức có được bằng cách phân tích app chính thức để dùng với thiết bị của chính mình.
- Nếu Android kill app khi đang tải ở nền, camera có thể kẹt ở chế độ xem lại → mở app bấm **Ghi hình** hoặc tắt/bật camera.
