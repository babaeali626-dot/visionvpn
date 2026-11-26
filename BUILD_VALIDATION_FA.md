# گزارش اعتبارسنجی بسته Vision VPN

تاریخ: ۲۶ نوامبر ۲۰۲۶

## بررسی‌هایی که در Workspace اجرا شدند

| بررسی | نتیجه | توضیح |
|---|---|---|
| Python syntax | موفق | `py_compile` برای اسکریپت‌های بسته |
| Dependency/license notice check | موفق | `scripts/check_tor_notices.py` |
| JSON syntax | موفق | تمام فایل‌های `.json` موجود در Overlay |
| GitHub Actions YAML syntax | موفق | Parse همه Workflowها با PyYAML BaseLoader |
| Flutter format/analyze | اجرا نشد | Flutter/Dart در Workspace نصب نبود |
| Flutter unit tests | اجرا نشد | Flutter/Dart در Workspace نصب نبود |
| Android/Kotlin Gradle build | اجرا نشد | Gradle/Android SDK در Workspace در دسترس نبود |
| Hiddify baseline build | اجرا نشد | سورس و Submoduleها محلی دریافت نشدند؛ دسترسی شبکه Workspace به GitHub ناموفق بود |
| Tor bootstrap / SOCKS runtime | اجرا نشد | نیازمند Build و دستگاه/Emulator واقعی |
| Full Tor routing / DNS leak / UDP deny | اجرا نشد | Adapter در Core Hiddify ادغام نشده است |
| ABI verification | اجرا نشد | AAR شامل ABIهای اعلامی در upstream است، اما APK نهایی ساخته/بازبینی نشده |

## مواردی که قبل از انتشار باید بسته شوند

1. ادغام `vision_tor_plugin` در `pubspec.yaml` اصلی و افزودن `VisionTorDashboard` به Navigation.
2. ادغام Outbound در تنظیم‌ساز واقعی Hiddify Core بدون حذف Outboundهای قبلی.
3. تنظیم DNS با detour از Tor و اعمال route نهایی Tor همراه با رد UDP؛ بدون Direct fallback.
4. Build Kotlin روی Gradle و Android SDK همان Tag.
5. اجرای تست‌های Tor Direct و Bridge روی دستگاه واقعی.
6. اجرای Regression تست‌های Hiddify و تست Packet Capture برای DNS/Direct leak.
7. گرفتن خروجی Dependency/SBOM و بررسی تمام Notices نسخه‌های resolveشده.
8. جایگزینی آیکون و نام برنامه در منابع واقعی Hiddify و بررسی مجوزهای تکمیلی فورک.

این گزارش تأیید می‌کند فایل‌های قابل بررسی بسته parse شده‌اند؛ موفقیت Build برنامه یا عملکرد شبکه Tor را تأیید نمی‌کند.
