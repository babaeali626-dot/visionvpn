# دستورالعمل تست دستگاه واقعی اندروید (Acceptance Tests) — Vision VPN Tor Mode

تست‌های واحد Go (`hiddify-core/v2/config/tor_mode_test.go`) و Dart (`test/features/tor/tor_outbound_adapter_test.dart` و `tor_service_channel_test.dart`) تمام قواعد پیکربندی، جلوگیری از DNS Leak، مسدودسازی UDP/Direct و ماشین وضعیت Fail-Closed را به‌صورت خودکار در CI بررسی می‌کنند.

موارد زیر نیازمند **دستگاه واقعی اندروید (Android 10+)**، نصب APK ساخته‌شده از GitHub Actions و بررسی ترافیک زنده شبکه هستند:

## پیش‌نیازها
- گوشی اندرویدی با USB Debugging فعال (`adb devices`)
- نصب `VisionVPN-Android-arm64-v8a.apk` (یا ABI متناسب با دستگاه)
- دسترسی به `adb logcat -s A/TorEngineManager A/VPNService A/BoxService`

---

## سناریو ۱: حفظ عملکرد عادی Hiddify
1. برنامه را باز کنید و مطمئن شوید سوئیچر صفحه اصلی روی **Hiddify** است.
2. یک پروفایل عادی (VLESS/VMess/Trojan/WireGuard) اضافه و متصل شوید.
3. **معیار قبولی:** اتصال عادی برقرار شود، `logcat` هیچ فعالیتی از `TorEngineManager` نشان ندهد و تمام تنظیمات و پروتکل‌های Hiddify مانند نسخه اصلی کار کنند.

## سناریو ۲ تا ۵: شروع Tor، تکمیل Bootstrap، اتصال sing-box به SOCKS5 و عبور TCP از Tor
1. سوئیچر را روی **Tor** بگذارید و وارد صفحه **Tor** شوید (یا دکمه اتصال را بزنید).
2. در `logcat` ترتیب زیر را بررسی کنید:
   - شروع `TorService` و پیشرفت `Bootstrap 0% → 100%`
   - تأیید Handshake پورت SOCKS5 روی `127.0.0.1:<port>`
   - راه‌اندازی `VPNService` با سشن `Vision VPN · Tor`
3. مرورگر گوشی را باز کرده و به `https://check.torproject.org/` بروید.
4. **معیار قبولی:** صفحه رسمی Tor Project پیام **"Congratulations. This browser is configured to use Tor."** را نمایش دهد.

## سناریو ۶: جلوگیری از DNS Leak
1. در حالی که حالت Tor متصل است، سایت `https://dnsleaktest.com/` (Extended Test) یا `https://browserleaks.com/dns` را باز کنید.
2. **معیار قبولی:** هیچ سرور DNS متعلق به ISP یا شبکه محلی (Wi-Fi/SIM) ظاهر نشود؛ تمام درخواست‌ها از مسیر Tor (`dns-tor` روی TCP) خارج شوند.
3. در صورت قطع شبکه upstream تور، بررسی کنید هیچ fallback به DNS سیستم رخ ندهد و نام دامنه resolve نشود.

## سناریو ۷ و ۸: مسدود شدن UDP و جلوگیری از Direct Fallback
1. در حالت Tor متصل، یک تست WebRTC (`https://browserleaks.com/webrtc`) و یک درخواست QUIC/UDP (مثلاً `curl --http3` یا ابزار شبکه اندروید روی UDP) اجرا کنید.
2. **معیار قبولی:** هیچ IP واقعی محلی یا عمومی در WebRTC نشت نکند و تمام بسته‌های UDP غیر-DNS مسدود (`reject`) شوند.
3. بررسی کنید فایل `visionvpn_tor_active.json` هیچ Outbound از نوع `direct` یا `bypass` نداشته باشد.

## سناریو ۹ و ۱۰: قطع ناگهانی Tor، فعال شدن Fail-Closed واقعی و بازیابی امن
1. در حالی که Tor VPN متصل است، سرویس Tor را متوقف یا کرش کنید (یا از متد تست `triggerFailClosed` استفاده کنید).
2. **معیار قبولی Fail-Closed:**
   - در کمتر از ۶ ثانیه (حداکثر ۲ پروب Watchdog)، اعلان اندروید به **`Vision VPN · Tor Fail-Closed (Blocked)`** تغییر کند.
   - صفحه برنامه وضعیت **`FAIL-CLOSED (TRAFFIC BLOCKED)`** را نشان دهد.
   - باز کردن هر سایت در مرورگر (مثلاً `https://example.com` یا IP مستقیم `http://1.1.1.1`) باید کاملاً مسدود شود و **هیچ ترافیکی از اینترنت مستقیم عبور نکند**.
3. دکمه **Recover Safely** را بزنید.
4. **معیار قبولی بازیابی:** برنامه ابتدا Tor را مجدداً به Bootstrap 100% برساند، SOCKS5 جدید را تأیید کند و تنها پس از آن تونل VPN را مجدداً برقرار کند (`check.torproject.org` مجدداً سبز باشد).

## سناریو ۱۱: عملکرد واقعی Snowflake
1. در صفحه Tor، گزینه Transport را روی **Snowflake** بگذارید و مطمئن شوید برچسب **`Snowflake: Ready`** نمایش داده می‌شود (نشان‌دهنده وجود `libsnowflake.so`).
2. دکمه **Connect Tor VPN** را بزنید.
3. **معیار قبولی:** `activeTransport` برابر `snowflake` باشد، Bootstrap به 100% برسد و `check.torproject.org` اتصال Tor را تأیید کند.

## سناریو ۱۲: عملکرد واقعی obfs4 (lyrebird)
1. در صفحه Tor، گزینه Transport را روی **obfs4** بگذارید و مطمئن شوید برچسب **`obfs4 (lyrebird): Ready`** نمایش داده می‌شود (نشان‌دهنده وجود `liblyrebird.so`).
2. در صورت نیاز یک خط Bridge معتبر `obfs4` از BridgeDB وارد و ذخیره کنید (یا از Bridgeهای پیش‌فرض استفاده کنید) و **Connect Tor VPN** را بزنید.
3. **معیار قبولی:** `activeTransport` برابر `obfs4` باشد، Bootstrap به 100% برسد و `check.torproject.org` اتصال Tor را تأیید کند.
