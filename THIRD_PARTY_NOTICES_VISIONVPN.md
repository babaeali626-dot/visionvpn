# مستندات مجوزها و اجزای شخص ثالث (Third-Party Notices) — Vision VPN

Vision VPN بر پایه سورس رسمی Hiddify v5.9.15 ساخته شده و از اجزای متن‌باز زیر با حفظ کامل مجوزهای آن‌ها استفاده می‌کند. هیچ کدی از پروژه SlipNet کپی نشده است.

| مؤلفه | مخزن رسمی (Upstream Source) | مجوز (License) | روش ساخت و ادغام در Vision VPN |
|---|---|---|---|
| **Hiddify App** (v5.9.15) | `https://github.com/hiddify/hiddify-app` | GPL-3.0-or-later | پایه اصلی پروژه Flutter و Android |
| **Hiddify Core** (v5.9.15) | `https://github.com/hiddify/hiddify-core` | GPL-3.0-or-later | سورس Go در `hiddify-core/` + دریافت رسمی `hiddify-core.aar` v5.9.15 در CI (`make android-libs`) |
| **sing-box** | `https://github.com/SagerNet/sing-box` | GPL-3.0-or-later (با استثنای رسمی) | موتور مسیریابی و TUN در داخل `hiddify-core` |
| **Guardian Project `tor-android`** (`0.4.9.17`) | `https://github.com/guardianproject/tor-android` (بر پایه Tor Project) | BSD-3-Clause (Tor) / Apache-2.0 | وابستگی Gradle در `android/app/build.gradle`؛ اجرای سرویس واقعی `org.torproject.jni.TorService` |
| **Guardian Project `jtorctl`** (`0.4.5.7`) | `https://github.com/guardianproject/jtorctl` | BSD-3-Clause | وابستگی Gradle برای ارتباط با ControlPort و دریافت `status/bootstrap-phase` |
| **Tor Project `lyrebird`** (`lyrebird-0.6.1`, پشتیبانی `obfs4`) | `https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird` | BSD-2-Clause / GPL-3.0-or-later | ساخت مستقیم از سورس رسمی توسط `scripts/build_tor_transports.sh` برای `arm64-v8a`، `armeabi-v7a` و `x86_64` به نام `liblyrebird.so` |
| **Tor Project `snowflake`** (`v2.11.0`, کلاینت Snowflake) | `https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake` | BSD-3-Clause | ساخت مستقیم از سورس رسمی توسط `scripts/build_tor_transports.sh` برای `arm64-v8a`، `armeabi-v7a` و `x86_64` به نام `libsnowflake.so` |

## نحوه رعایت شرایط مجوزها
- فایل مجوز اصلی پروژه (`LICENSE.md`) حفظ شده است.
- تمام اجزای Pluggable Transport (`lyrebird` و `snowflake`) به‌صورت شفاف و تکرارپذیر از مخازن رسمی Tor Project در مرحله CI ساخته می‌شوند و هیچ باینری ناشناس یا پیش‌ساخته غیرقابل ممیزی در مخزن قرار داده نشده است.
- این پروژه باید همراه با کد منبع کامل و تحت سازگاری با مجوز GPL-3.0-or-later توزیع شود.
