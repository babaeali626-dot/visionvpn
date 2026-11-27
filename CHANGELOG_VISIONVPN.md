# فهرست تغییرات Vision VPN نسبت به Hiddify v5.9.15 اصلی

این سند تمام فایل‌های اضافه یا ویرایش‌شده نسبت به سورس رسمی Hiddify v5.9.15 را فهرست می‌کند.

## ۱. لایه Android Native (`android/app/src/main/kotlin/com/hiddify/hiddify/`)
- **`tor/TorTransportConfig.kt`** (جدید): مدیریت باینری‌های واقعی Pluggable Transport (`libsnowflake.so` و `liblyrebird.so`) در `nativeLibraryDir`، اعتبارسنجی خطوط Bridge (`obfs4`، `snowflake`، vanilla) و تولید `torrc` با `ClientTransportPlugin`.
- **`tor/TorEngineManager.kt`** (تکمیل‌شده): مدیریت کامل چرخه حیات `org.torproject.jni.TorService`، پشتیبانی واقعی از حالت‌های `automatic` (با fallback خودکار به Snowflake)، `snowflake` و `obfs4`، تأیید واقعی Handshake پورت SOCKS5 روی `127.0.0.1`، پایش سلامت هر ۳ ثانیه (Watchdog) و انتشار رویدادهای `FAIL_CLOSED` و بازیابی امن.
- **`tor/TorFlutterPlugin.kt`** (تکمیل‌شده): کانال‌های `visionvpn.tor/method` و `visionvpn.tor/events` با متدهای `start`، `stop`، `recover`، `setTransportMode`، `setTorModeEnabled`، `capabilities`، `triggerFailClosed` و مدیریت امن Bridgeها.
- **`tor/BridgeVault.kt`**: ذخیره‌سازی رمزنگاری‌شده Bridgeها با AES-256-GCM و Android Keystore.
- **`constant/SettingsKey.kt` و `Settings.kt`**: افزودن کلیدهای جداگانه حالت Tor (`visionvpn_tor_mode_enabled`، `visionvpn_tor_transport_mode`، `visionvpn_tor_socks_port`، `visionvpn_tor_fail_closed_active`) بدون تغییر در تنظیمات عادی Hiddify.
- **`bg/VPNService.kt`**: نام‌گذاری سشن در حالت Tor، مستثنی کردن قطعی پکیج برنامه در حالت Tor برای جلوگیری از حلقه سوکت‌های Tor/PT، و پیاده‌سازی واقعی `enterFailClosedBlackhole()` و `exitFailClosedBlackhole()`.
- **`bg/BoxService.kt`**: اتصال `TorEngineManager.FailClosedListener` در حالت Tor برای مسدودسازی فوری ترافیک (Blackhole TUN + توقف `Mobile.stop()`) هنگام قطع Tor و جلوگیری از شروع VPN پیش از تکمیل Bootstrap.
- **`android/app/build.gradle` و `AndroidManifest.xml`**: افزودن `info.guardianproject:tor-android:0.4.9.17`، `jtorctl:0.4.5.7`، ثبت سرویس خصوصی `TorService` و برندینگ `Vision VPN`.

## ۲. هسته Go (`hiddify-core/v2/config/`)
- **`tor_mode.go`** (جدید): تعریف `TorModeOptions` و `BuildTorConfig(opt)` با حذف کامل Outboundهای Direct/Bypass، حذف NTP، محدودسازی DNS به مسیر Tor، مسدودسازی UDP و تنظیم `Final = OutboundBlockTag`.
- **`hiddify_option.go` و `config.go`**: افزودن فیلد `Tor TorModeOptions` و شاخه مجزای `if opt.Tor.Enabled { return BuildTorConfig(opt) }` بدون هیچ تغییری در مسیر عادی Hiddify.
- **`tor_mode_test.go`** (جدید): تست‌های واحد Go برای بررسی حفظ حالت عادی و رعایت تمام قواعد امنیتی حالت Tor.

## ۳. لایه Flutter / Dart (`lib/`)
- **`features/tor/model/tor_mode_state.dart`** (جدید): مدل‌های `VisionConnectionMode`، `TorTransportMode`، `TorVpnPhase`، `TorSnapshot` و `TorModeState`.
- **`features/tor/data/tor_outbound_adapter.dart`** (تکمیل‌شده): تولید و اعتبارسنجی کانفیگ کامل زنده `sing-box` 1.12 برای حالت Tor.
- **`features/tor/data/tor_service.dart`** (تکمیل‌شده): پل ارتباطی با اندروید + متد `waitUntilReady()` برای انتظار واقعی تا Bootstrap 100% و SOCKS5.
- **`features/tor/notifier/tor_mode_notifier.dart`** (جدید): هماهنگ‌کننده کامل چرخه اتصال مستقل Tor VPN، سوئیچ امن میان Hiddify و Tor، Fail-Closed و بازیابی امن.
- **`features/tor/widget/tor_page.dart`** (تکمیل‌شده): رابط کاربری کامل انتخاب حالت، انتخاب Transport (`Automatic` / `Snowflake` / `obfs4`)، نمایش Bootstrap، وضعیت Transport و خطاها، وضعیت Fail-Closed و مدیریت Bridgeها.
- **`hiddifycore/hiddify_core_service.dart`**: افزودن پارامتر اختیاری `enableRawConfig = false` به `start(...)`.
- **`features/home/widget/home_page.dart` و `connection_button.dart`**: افزودن سوئیچر فشرده `Hiddify | Tor` در صفحه اصلی و اتصال دکمه اصلی در حالت Tor بدون تغییر در رفتار حالت عادی Hiddify.

## ۴. تست‌ها، اسکریپت ساخت Transport و CI/CD
- **`scripts/build_tor_transports.sh`** (جدید): ساخت تکرارپذیر `liblyrebird.so` و `libsnowflake.so` برای `arm64-v8a`، `armeabi-v7a` و `x86_64`.
- **`.github/workflows/visionvpn-android-ci.yml`** (تکمیل‌شده): ورک‌فلوی کامل ساخت Transportها، دریافت Core v5.9.15، اجرای تست‌های Go و Flutter، ساخت APKها و بررسی وجود باینری‌های Transport داخل APK.
- **`test/features/tor/tor_outbound_adapter_test.dart`** و **`tor_service_channel_test.dart`**: تست‌های خودکار امنیتی و چرخه عمر.
