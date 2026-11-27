# ساخت Android و استفاده از GitHub Actions

## پیش‌نیازهای محلی

- Linux x86_64 (پیشنهاد: Ubuntu 24.04)
- JDK 17
- Go 1.24.0 (از `hiddify-core/go.mod` خوانده می‌شود)
- Node.js 20 و npm
- Flutter 3.38.5 و Dart همراه آن
- Android SDK Platform 36، Build Tools 36.0.0
- Android NDK `28.2.13676358` و CMake `3.22.1`

## Build محلی از سورس

از ریشهٔ پروژه:

```bash
flutter doctor -v
flutter pub get
dart run build_runner build --delete-conflicting-outputs
dart run slang

# حتماً Core همین checkout را بساز؛ make android-prepare از Release upstream AAR دانلود می‌کند.
make build-android-libs

# کنترل وجود فایل Core
ls -lh android/app/libs/hiddify-core.aar

flutter analyze --no-pub
flutter test --no-pub
flutter build apk --release --split-per-abi --no-pub
```

خروجی APK در این پوشه قرار می‌گیرد:

```text
build/app/outputs/flutter-apk/
```

ABIهای تنظیم‌شده در پروژه شامل `arm64-v8a`، `armeabi-v7a` و `x86_64` هستند. این build، در صورت نبود keystore اختصاصی release، از debug signing config برای build نوع release استفاده می‌کند؛ برای توزیع عمومی باید signing key مدیریت‌شده در GitHub Secrets و تنظیم Gradle جداگانه تعریف شود.

## GitHub: ایجاد مخزن و اجرای workflow

**شرط مجوز upstream را بخوانید.** فایل `LICENSE.md` پروژهٔ Hiddify شرط انتشار به‌صورت fork از مخزن رسمی Hiddify را ذکر می‌کند. صرف ساختن یک repository مستقل و آپلود ZIP ممکن است با این شرط سازگار نباشد. پیش از انتشار عمومی، متن کامل شرایط را بررسی کنید.

1. در GitHub به صفحهٔ مخزن رسمی Hiddify app بروید و با **Fork** یک fork بسازید.
2. روی رایانه یک clone از fork خود بگیرید:

   ```bash
   git clone https://github.com/YOUR_ACCOUNT/YOUR_FORK.git
   cd YOUR_FORK
   ```

3. فایل‌های این بسته را به همان checkout منتقل کنید و فایل‌های اصلاح‌شده را جایگزین کنید. به‌ویژه این موارد باید وجود داشته باشند:
   - `.github/workflows/visionvpn-android-ci.yml`
   - پوشهٔ `hiddify-core/` کامل
   - `README_VISIONVPN_FA.md` و فایل‌های `docs/`
4. تغییرات را commit و push کنید:

   ```bash
   git add -A
   git commit -m "Prepare VisionVPN Android source build"
   git push origin YOUR_BRANCH
   ```

5. در GitHub، تب **Actions** را باز کنید و workflow **VisionVPN Android Source Build** را انتخاب کنید. می‌توانید از **Run workflow** نیز استفاده کنید.
6. اجرای سبز را باز کنید. در پایین صفحه، بخش **Artifacts** را خواهید دید. فایل `visionvpn-android-apks-<run-number>` را دانلود و extract کنید.
7. اگر job قرمز شد، APK به‌عنوان خروجی موفق منتشر نمی‌شود. خطای نخستین step قرمز را باز کنید؛ معمولاً خطای dependency، Go/NDK، تست یا Gradle علت اصلی است.

## رفتار Workflow

Workflow:

1. سورس checkout را دریافت می‌کند.
2. Java 17، Go نسخهٔ فایل Core، Node 20، Flutter 3.38.5 و Android SDK/NDK پین‌شده را نصب می‌کند.
3. Flutter packages و فایل‌های generated را آماده می‌کند.
4. تست‌های هدفمند Go مربوط به config/hcore را اجرا می‌کند.
5. با `make build-android-libs`، AAR را از **Core داخل همین checkout** می‌سازد.
6. Flutter analyze و تمام تست‌های Flutter را اجرا می‌کند.
7. APKهای release split-per-ABI را می‌سازد و خالی نبودن خروجی‌ها را بررسی می‌کند.
8. فقط در صورت موفقیت مراحل قبلی Artifact را آپلود می‌کند.

## نکته درباره Artifact

وجود Artifact یعنی زنجیرهٔ ساخت در runner موفق بوده است؛ **به معنی تأیید امنیت Tor VPN نیست**. Core/APK build و تست‌های واحد نمی‌توانند جای تست packet-level روی دستگاه واقعی را بگیرند. برای Tor end-to-end از برنامهٔ [تست دستگاه](TOR_DEVICE_ACCEPTANCE_TESTS_FA.md) استفاده کنید.
