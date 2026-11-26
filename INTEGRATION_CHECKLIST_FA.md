# چک‌لیست ادغام در Hiddify v5.9.15

این بسته Overlay قابل انتقال است، نه Fork کامل Hiddify. سورس بالادستی به‌علت نبود اتصال شبکه در Workspace دریافت نشده؛ بنابراین فایل‌های موجود Hiddify را بازنویسی یا جعل نکرده‌ایم.

## ۱. دریافت نسخه پایه روی سیستم شما

```bash
git clone --branch v5.9.15 --recurse-submodules https://github.com/hiddify/hiddify-app.git VisionVPN
cd VisionVPN
git rev-parse HEAD
git submodule status
```

خروجی `git rev-parse HEAD` و شناسه Submodule را ثبت کنید. نسخه مبنا در زمان بررسی `v5.9.15` با short SHA `365a2f5` بود.

## ۲. اعمال فایل‌ها

از داخل این بسته:

```bash
python3 scripts/apply_overlay.py /path/to/VisionVPN
```

اسکریپت فقط مسیرهای جدید را کپی می‌کند و اگر مسیر مقصدی از قبل وجود داشته باشد، متوقف می‌شود.

## ۳. تغییرات دستی ضروری و کنترل‌شده

۱. در `pubspec.yaml` ریشه، dependency زیر را به `dependencies` اضافه کنید:

```yaml
vision_tor_plugin:
  path: packages/vision_tor
```

۲. در `android/app/src/main/AndroidManifest.xml`، مقدار label برنامه را از Hiddify به `@string/app_name` تغییر دهید؛ رشته `Vision VPN` از `android/branding/values/strings.xml` به resources برنامه منتقل شود. نام و هویت برنامه مستقل است، ولی Attribution و مجوز Hiddify باید حفظ شوند.

۳. Service موجود Hiddify از نوع `VPNService` را حذف یا جایگزین نکنید. Manifest افزونه `TorService` را اضافه می‌کند؛ این Tor daemon است و VPNService دوم نیست.

۴. فایل لوگو را به assetهای Flutter و launcher iconهای Android انتقال دهید. `android/branding` نمونه منابع است؛ آن را با تمام densityهای پروژه و adaptive icon موجود تطبیق دهید و آیکون‌های اصلی برنامه را تا پیش از بررسی overwrite نکنید.

۵. صفحه `VisionTorDashboard` را به Navigation موجود Hiddify به‌عنوان صفحه/تب مستقل اضافه کنید. جای صفحه Home، Subscription، Profile، Settings و صفحات اصلی را نگیرد.

۶. `TorOutboundAdapter.socksOutbound` را پس از آماده‌شدن واقعی Tor فراخوانی کنید. Outbound باید به آرایه `outbounds` تنظیمات sing-box موجود اضافه شود؛ هیچ‌یک از outbounds قبلی حذف نشوند.

۷. قوانین Full Tor باید پس از بررسی Schema دقیق Core اعمال شوند: UDP reject، DNS با detour از Tor و `route.final=vision-tor`. `direct` نباید fallback باشد. قطعه JSON در `core/` صرفاً Fragment مرجع است، نه پیکربندی Core تأییدشده.

۸. تا زمانی که Core adapter در کد واقعی Hiddify اعمال و تست نشده، صفحه Tor فقط daemon محلی را اجرا می‌کند و به‌تنهایی کل ترافیک VPN را از Tor عبور نمی‌دهد.

## ۴. هویت و نام

نام نمایشی: `Vision VPN`
نام بسته/Bundle اصلی را بدون بررسی نسخه فعلی و انتشار به‌صورت خودسرانه عوض نکنید. تصویر ارائه‌شده کاربر به‌عنوان لوگوی محصول در `branding/` و Asset افزونه قرار دارد.

## ۵. تست و Build

```bash
make android-prepare
flutter analyze
flutter test
cd packages/vision_tor && flutter test && cd ../..
flutter build apk --debug --target lib/main.dart --split-per-abi
```

این دستورها در محیط ساخت‌کننده بسته اجرا نشده‌اند؛ پس از دریافت سورس بالادستی و SDK اجرا و خروجی‌ها ثبت شوند.
