# گزارش شفاف وضعیت بسته

| مورد | وضعیت |
|---|---|
| لوگوی Vision VPN | در بسته قرار گرفت |
| نام نمایشی Vision VPN | در منابع پیشنهادی قرار گرفت؛ هنوز روی Manifest اصلی اعمال نشده |
| افزونه Native Tor Android | سورس Kotlin ایجاد شد؛ کامپایل نشده |
| SOCKS5 Loopback | در کد با 127.0.0.1 و پورت runtime طراحی شد؛ روی دستگاه تست نشده |
| پروفایل Tor | مدل Dart و ذخیره‌سازی رمزنگاری‌شده Android ایجاد شد؛ تست دستگاه نشده |
| Tor Direct | کد راه‌اندازی اضافه شد؛ اتصال واقعی آزمایش نشده |
| Custom direct Bridge | اعتبارسنجی و نوشتن Torrc اضافه شد؛ تست شبکه نشده |
| obfs4 / Snowflake / Meek | عمداً غیرفعال؛ باینری PT موجود نیست |
| Core Outbound | قطعه SOCKS تولید شده؛ به Core Hiddify وصل نشده |
| Full Tor / DNS / UDP kill | راهنمای ادغام و fragment مرجع؛ در Core اجرا/تست نشده |
| UI | Widget مجزا ایجاد شد؛ در Navigation اصلی Hiddify قرار نگرفته |
| CI/CD | Workflow templateها اضافه شد؛ روی GitHub اجرا نشده |
| APK | ساخته نشده |
| Regression / Leak tests | اجرا نشده |

علت محدودیت: Workspace در این نشست DNS برای github.com را resolve نکرد و Flutter/Dart/Android SDK/Gradle نصب نبود. به‌همین دلیل سورس Hiddify و Submodule دریافت نشدند. این ZIP یک Overlay واقعی از فایل‌های جدید است، نه مخزن Fork کامل و نه نسخه نهایی قابل نصب.
