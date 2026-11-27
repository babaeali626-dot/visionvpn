# Vision VPN (بر پایه Hiddify v5.9.15 با حالت مستقل Tor)

Vision VPN یک فورک مستقل از **Hiddify v5.9.15** است که بدون تغییر در عملکرد، پروتکل‌ها، تنظیمات و منطق اتصال عادی Hiddify، یک **حالت اتصال مستقل Tor** با پشتیبانی واقعی از **Snowflake** و **obfs4 (lyrebird)**، جلوگیری از **DNS Leak**، مسدودسازی **UDP و مسیرهای Direct** و مکانیزم واقعی **Fail-Closed** در سطح Android VPN و `sing-box` اضافه می‌کند.

## معماری و قابلیت‌های پیاده‌سازی‌شده

### ۱. حفظ کامل Hiddify عادی
- حالت پیش‌فرض برنامه **Hiddify** (`VisionConnectionMode.normal`) است.
- در حالت عادی، هیچ سرویس Tor اجرا نمی‌شود و تمام پروفایل‌ها، پروتکل‌ها، تنظیمات DNS/Routing و منطق اتصال Hiddify v5.9.15 بدون تغییر عمل می‌کنند.
- کاربر از صفحه اصلی (سوئیچر `Hiddify | Tor`) یا از مسیر `Settings → Tor` می‌تواند میان حالت عادی و حالت مستقل Tor جابه‌جا شود.

### ۲. حالت مستقل Tor (Android Service + Bootstrap + sing-box زنده)
- سرویس واقعی Tor از کتابخانه رسمی Guardian Project (`info.guardianproject:tor-android:0.4.9.17` و `jtorctl:0.4.5.7`) اجرا می‌شود.
- پیش از راه‌اندازی VPN، برنامه در `TorEngineManager.kt` و `TorModeNotifier` منتظر رسیدن Bootstrap به **۱۰۰٪** می‌ماند و سپس با ارسال Handshake واقعی SOCKS5 (`0x05 0x01 0x00` → `0x05 0x00`) روی `127.0.0.1:<socksPort>` آماده بودن پورت را تأیید می‌کند.
- پس از تأیید، کانفیگ زنده `sing-box` توسط `TorOutboundAdapter.encodeFullSingboxConfig(...)` ساخته و اعتبارسنجی شده و با `enableRawConfig: true` به هسته زنده `hiddify-core` تحویل داده می‌شود.
- همچنین معادل کامل آن در هسته Go (`hiddify-core/v2/config/tor_mode.go`) همراه با تست‌های واحد پیاده‌سازی شده است.
- در `VPNService.kt`، پکیج خود برنامه از TUN مستثنی می‌شود تا سوکت‌های دیمون Tor و باینری‌های Transport بدون حلقه مسیریابی به شبکه برسند و هم‌زمان ترافیک تمام اپلیکیشن‌های دیگر دستگاه وارد TUN شود.

### ۳. جلوگیری از DNS Leak و مسدودسازی UDP / Direct
- در کانفیگ زنده حالت Tor:
  - تمام درخواست‌های DNS (`protocol: dns` و `port: 53`) با `hijack-dns` رهگیری و منحصراً از طریق سرور `dns-tor` (TCP به `1.1.1.1` با `detour: tor`) ارسال می‌شوند.
  - هیچ سرور `local`، `dns-direct` یا fallback مستقیم وجود ندارد.
  - تمام ترافیک غیر-DNS روی UDP با قانون `network: udp -> action: reject` مسدود می‌شود.
  - هیچ Outbound از نوع `direct` یا `bypass` و هیچ سرویس `ntp` در کانفیگ حالت Tor وجود ندارد.
  - قانون پایانی `action: reject` تضمین می‌کند هیچ ترافیک غیرمنتظره‌ای مستقیم خارج نشود.

### ۴. Fail-Closed واقعی در سطح VPN و مسیریابی
- `TorEngineManager.kt` هر ۳ ثانیه با Handshake واقعی SOCKS5 سلامت Tor را پایش می‌کند.
- در صورت توقف، کرش یا قطع شدن SOCKS5 تور:
  1. `TorEngineManager` وضعیت `FAIL_CLOSED` را فعال و به `BoxService.kt` اطلاع می‌دهد.
  2. `BoxService.kt` پیش از توقف `sing-box`، متد `VPNService.enterFailClosedBlackhole()` را اجرا می‌کند تا یک رابط TUN مسدودکننده (`0.0.0.0/0` و `::/0`) بدون خروجی ایجاد شود و هیچ بسته‌ای به اینترنت مستقیم نشت نکند.
  3. سپس `Mobile.stop()` اجرا شده و وضعیت **Fail-Closed (Traffic Blocked)** در اعلان اندروید و رابط کاربری نمایش داده می‌شود.
  4. بازیابی اتصال (`Recover Safely`) فقط پس از راه‌اندازی مجدد Tor، تکمیل Bootstrap ۱۰۰٪ و تأیید مجدد Handshake پورت SOCKS5 انجام می‌شود.

### ۵. پشتیبانی واقعی Snowflake و obfs4 (lyrebird)
- اسکریپت `scripts/build_tor_transports.sh` باینری‌های رسمی Tor Project یعنی **lyrebird** (پشتیبانی‌کننده `obfs4`) و **snowflake client** را برای معماری‌های `arm64-v8a`، `armeabi-v7a` و `x86_64` ساخته و در مسیر `android/app/src/main/jniLibs/<abi>/` با نام‌های `liblyrebird.so` و `libsnowflake.so` قرار می‌دهد.
- در `TorTransportConfig.kt` تنظیمات واقعی `ClientTransportPlugin snowflake exec ...` و `ClientTransportPlugin obfs4 exec ...` ساخته می‌شوند و وضعیت وجود باینری‌ها و خطاهای Transport در رابط کاربری نمایش داده می‌شود.
- انتخاب `Automatic`، `Snowflake` یا `obfs4` در رابط کاربری مستقیماً روی `torrc` و نوع Transport فعال تأثیر می‌گذارد.

## ساخت محلی (پیش‌نیازها)
- Java 17 (Temurin/Zulu)
- Go 1.24.0
- Flutter 3.38.5
- Android SDK (API 36) و NDK `28.2.13676358`

```bash
chmod +x scripts/build_tor_transports.sh
bash scripts/build_tor_transports.sh
CHANNEL=prod make android-libs
flutter pub get
dart run build_runner build --delete-conflicting-outputs
dart run slang
flutter analyze --no-pub
flutter test --no-pub
flutter build apk --release --split-per-abi --no-pub
```

## وضعیت شفاف اعتبارسنجی
- در محیط تولید این فایل ZIP ابزارهای `flutter`، `dart`، `go`، `gradle` و دسترسی اینترنت فعال نبودند؛ بنابراین تمام کدها و تست‌ها به‌صورت ایستای کامل (ساختار، نحو، سازگاری API و قراردادهای امنیتی) بازبینی شده‌اند اما **اجرای واقعی کامپایل، تست‌های خودکار و تولید APK** باید از طریق GitHub Actions (طبق فایل `GITHUB_UPLOAD_FA.md`) و **تست‌های شبکه/نشت** روی دستگاه واقعی اندروید (طبق `docs/TOR_DEVICE_ACCEPTANCE_TESTS_FA.md`) تأیید شوند.
