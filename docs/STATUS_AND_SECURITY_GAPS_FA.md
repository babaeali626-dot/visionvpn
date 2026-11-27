# گزارش شفاف وضعیت پیاده‌سازی و اعتبارسنجی Vision VPN

تاریخ به‌روزرسانی: 2026-11-27  
نسخه مبنا: Hiddify v5.9.15

## خلاصه وضعیت پیاده‌سازی کدها

| الزام پروژه | وضعیت پیاده‌سازی در سورس | وضعیت اجرا و تست |
|---|---|---|
| حفظ کامل Hiddify عادی | پیاده‌سازی شده (`VisionConnectionMode.normal` پیش‌فرض، مسیرهای عادی دست‌نخورده) | نیازمند اجرای `flutter test` در GitHub Actions |
| سرویس واقعی Android Tor + انتظار Bootstrap + تأیید SOCKS5 | پیاده‌سازی شده (`TorEngineManager.kt` + `TorModeNotifier`) | نیازمند اجرا در CI و تست روی دستگاه اندروید |
| اتصال واقعی به `sing-box` زنده (`enableRawConfig: true` + `tor_mode.go`) | پیاده‌سازی شده (`TorOutboundAdapter`, `HiddifyCoreService`, `hiddify-core/v2/config/tor_mode.go`) | تست‌های واحد Go و Dart نوشته شده؛ اجرا در CI |
| جلوگیری از DNS Leak (فقط `dns-tor` روی TCP، بدون `local`/`direct`) | پیاده‌سازی شده در کانفیگ زنده و `tor_mode.go` | تست خودکار نوشته شده + تست دستگاه در `TOR_DEVICE_ACCEPTANCE_TESTS_FA.md` |
| مسدودسازی UDP و حذف تمام مسیرهای Direct و NTP | پیاده‌سازی شده (`network: udp -> reject`، صفر Outbound مستقیم) | تست خودکار نوشته شده + تست دستگاه مستند شده |
| Fail-Closed واقعی در سطح VPN (`enterFailClosedBlackhole` + Watchdog) | پیاده‌سازی شده در `TorEngineManager.kt`، `BoxService.kt` و `VPNService.kt` | تست کانال نوشته شده + سناریو ۹ و ۱۰ دستگاه |
| پشتیبانی واقعی Snowflake و obfs4 (`lyrebird`) | پیاده‌سازی شده (`TorTransportConfig.kt` + `scripts/build_tor_transports.sh`) | باینری‌ها در GitHub Actions ساخته و داخل APK تأیید می‌شوند |
| رابط کاربری (حفظ Hiddify + سوئیچر مستقل Tor + انتخاب Transportها) | پیاده‌سازی شده (`home_page.dart`, `connection_button.dart`, `tor_page.dart`) | بررسی ایستای کد انجام شد؛ بیلد APK در GitHub Actions |
| GitHub Actions و ساخت APK به تفکیک ABI | پیاده‌سازی شده (`.github/workflows/visionvpn-android-ci.yml`) | ساختار YAML بررسی شد؛ اجرا پس از آپلود در GitHub |

## محدودیت شفاف محیط تولید این بسته
در محیطی که این فایل ZIP در آن آماده شده است، ابزارهای کامپایل (`flutter`، `dart`، `go`، `gradle`، `Android SDK/NDK`) و اتصال به اینترنت وجود نداشتند. بنابراین:
- **هیچ ادعایی مبنی بر اجرای واقعی `flutter build apk`، `flutter test` یا `go test` در این محیط مطرح نمی‌شود.**
- تمام تغییرات روی سورس واقعی پروژه اعمال شده و از نظر ساختاری، سینتکسی (YAML، Bash، توازن بلوک‌ها و تطابق کلیدها/کانال‌ها) بررسی شده‌اند.
- تأیید نهایی کامپایل، تست‌های خودکار، بسته‌بندی باینری‌های `libsnowflake.so` و `liblyrebird.so` و تولید APKها با اجرای ورک‌فلو **VisionVPN Android CI & Release APK** در GitHub Actions انجام می‌شود.
