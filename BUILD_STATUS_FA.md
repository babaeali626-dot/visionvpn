# گزارش وضعیت بسته Vision VPN

تاریخ تهیه: 2026-11-26 (بازبینی تکمیلی v0.2)
نسخه مبنا: Hiddify v5.9.15
Commit مبنا درج‌شده در ZIP اصلی: `365a2f567aa83558eae990219f3185a32842484f`

## تغییرات اعمال‌شده در سورس
- نام نمایشی Android به Vision VPN تغییر کرد؛ Application ID حفظ شده است تا شناسه نصب و سازگاری نسخه مبنا بی‌دلیل عوض نشود.
- آیکون جدید Vision VPN به منابع launcher و assets اضافه شد.
- وابستگی رسمی Guardian Project tor-android 0.4.9.17 و jtorctl 0.4.5.7 افزوده شد.
- TorEngineManager برای bind/start/stop، وضعیت‌ها، bootstrap polling و دریافت SOCKS listener افزوده شد.
- بازرسی API رسمی tor-android نشان داد رویدادهای TorService از LocalBroadcastManager ارسال می‌شوند؛ دریافت رویدادها اصلاح و سرویس داخلی Tor به‌صورت private در Manifest ثبت شد.
- SOCKS به loopback محدود شده است.
- Bridgeهای vanilla با اعتبارسنجی ورودی و ذخیره رمزنگاری‌شده AES-GCM با Android Keystore افزوده شدند.
- صفحه Flutter برای وضعیت Tor و Bridgeها به Settings اضافه شد.
- TorOutboundAdapter برای تولید ساختار SOCKS5 سازگار با sing-box و تست‌های واحد آن افزوده شد.
- Workflow اختصاصی Android CI برای push و pull request روی شاخه‌های test و vision-vpn افزوده شد.

## مواردی که عمداً تکمیل‌شده اعلام نمی‌شوند
- `hiddify-core` در ZIP اولیه یک Submodule خالی است. Makefile اصلی برای Android فایل AAR نسخه متناظر را از Release دانلود می‌کند؛ اما سورس Go لازم برای تغییر عمیق routing در این ZIP موجود نیست.
- TorOutboundAdapter هنوز به مولد کانفیگ زنده Core متصل نشده است؛ بنابراین ترافیک Hiddify به‌صورت واقعی از Tor عبور داده نمی‌شود.
- پروفایل مستقل Tor در مدل پروفایل‌های Hiddify ثبت نشده است.
- Full Tor kill switch، اجبار DNS، مسدودسازی UDP و جلوگیری از fallback به Direct در Core پیاده‌سازی نشده‌اند.
- باینری‌های Snowflake، obfs4 و Meek در بسته قرار ندارند و فعال نیستند. فقط Tor Direct و Bridgeهای vanilla در لایه Tor قابل تنظیم هستند.
- Build واقعی Flutter/Gradle و تست روی دستگاه Android در این محیط اجرا نشده است؛ ابزارهای Flutter و Android SDK نصب نیستند.

## بررسی‌های انجام‌شده
- API و نحوه انتشار رویدادهای TorService با سورس رسمی Guardian Project تطبیق داده شد؛ دریافت broadcast اصلاح شد.
- ZIP ورودی با `unzip -t` بدون خطا خوانده شد.
- تغییرات در فایل‌های واقعی سورس اعمال شدند.
- XML/JSON/YAML و ساختار ZIP در مرحله اعتبارسنجی نهایی بررسی می‌شوند.

این بسته یک سورس توسعه‌ای قابل بررسی است، نه APK نهایی تأییدشده برای انتشار.
