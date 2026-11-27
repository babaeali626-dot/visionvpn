# راهنمای آپلود پروژه در GitHub و دانلود APK از GitHub Actions

## گام ۱: ایجاد مخزن (Repository) در GitHub
1. وارد حساب GitHub خود شوید و روی **New repository** کلیک کنید.
2. نام مخزن را مثلاً `VisionVPN` بگذارید (می‌تواند Public یا Private باشد) و بدون افزودن README یا `.gitignore` مخزن را بسازید.

## گام ۲: آپلود سورس پروژه از فایل ZIP
فایل `VisionVPN-Hiddify-5.9.15-Complete.zip` را روی سیستم خود استخراج (Extract) کنید و در ترمینال داخل پوشه استخراج‌شده دستورات زیر را اجرا کنید:

```bash
git init -b main
git add .
git commit -m "Vision VPN: Hiddify v5.9.15 with standalone Tor, Snowflake, obfs4, and Fail-Closed"
git remote add origin https://github.com/<USERNAME>/<REPO_NAME>.git
git push -u origin main
```

> **نکته:** در صورت استفاده از GitHub Desktop یا محیط وب، مطمئن شوید پوشه مخفی `.github/workflows/visionvpn-android-ci.yml` و اسکریپت `scripts/build_tor_transports.sh` حتماً آپلود شده باشند.

## گام ۳: اجرای Workflow در GitHub Actions
1. در صفحه مخزن GitHub وارد تب **Actions** شوید.
2. از ستون سمت چپ، ورک‌فلوی **VisionVPN Android CI & Release APK** را انتخاب کنید.
3. این ورک‌فلو به‌صورت خودکار پس از هر `push` اجرا می‌شود، یا می‌توانید با دکمه **Run workflow** آن را دستی اجرا کنید.

### مراحلی که ورک‌فلو به‌صورت خودکار انجام می‌دهد:
1. نصب Java 17، Go 1.24.0، Android SDK 36 / NDK 28.2.13676358 و Flutter 3.38.5.
2. ساخت واقعی باینری‌های **Snowflake** (`libsnowflake.so`) و **obfs4 / lyrebird** (`liblyrebird.so`) برای هر سه معماری `arm64-v8a`، `armeabi-v7a` و `x86_64`.
3. دریافت کتابخانه رسمی `hiddify-core.aar` نسخه `5.9.15`.
4. اجرای تست‌های واحد Go (`go test -v ./v2/config/...`).
5. تولید کدهای Dart (`build_runner` و `slang`)، اجرای `flutter analyze` و اجرای تست‌های `flutter test` (در صورت شکست هر تست یا خطای کامپایل، ورک‌فلو متوقف و ناموفق اعلام می‌شود).
6. ساخت فایل‌های APK به تفکیک ABI و نسخه Universal و بررسی وجود باینری‌های `libsnowflake.so` و `liblyrebird.so` داخل APK ساخته‌شده.

## گام ۴: دانلود APK از بخش Actions
1. پس از سبز شدن (Succeeded) اجرای ورک‌فلو، روی نام همان Run کلیک کنید.
2. به پایین صفحه در بخش **Artifacts** بروید.
3. روی فایل `visionvpn-android-apks-<run_number>` کلیک کنید تا دانلود شود.
4. فایل زیپ دانلودشده شامل چهار APK است:
   - `VisionVPN-Android-arm64-v8a.apk` (مناسب اکثر گوشی‌های اندرویدی جدید)
   - `VisionVPN-Android-armeabi-v7a.apk` (مناسب گوشی‌های ۳۲بیتی قدیمی‌تر)
   - `VisionVPN-Android-x86_64.apk` (مناسب شبیه‌سازها و تبلت‌های x86_64)
   - `VisionVPN-Android-universal.apk` (سازگار با همه معماری‌ها)
