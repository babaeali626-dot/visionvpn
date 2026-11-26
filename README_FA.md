# Vision VPN — بسته ادغام Tor برای Hiddify Android

این ZIP شامل افزونه اندرویدی مستقل برای مدیریت Tor، رابط کاربری Vision VPN، لوگوی اختصاصی، قطعات اتصال SOCKS5 به sing-box، چهار GitHub Actions workflow و مستندات است.

**محدودیت مهم:** به‌دلیل نبود دسترسی شبکه از Workspace، سورس اصلی Hiddify و Submodule آن داخل ZIP نیستند. بنابراین این فایل یک Overlay/Feature Pack قابل ادغام روی Hiddify `v5.9.15` است، نه Fork کامل و نه APK. برای ساخت مخزن کامل، ابتدا سورس رسمی را با دستورهای `INTEGRATION_CHECKLIST_FA.md` دریافت و سپس Overlay را اعمال کنید.

## محصول و لوگو

نام محصول در این Overlay برابر **Vision VPN** است. لوگوی ارسالی شما بدون تغییر محتوایی در `branding/vision_vpn_logo_1024.png` قرار دارد و نسخه استفاده در افزونه نیز داخل `packages/vision_tor/assets/` است.

## کد اتصال به موتور واقعی Tor (نیازمند Build و تست دستگاه)

- استفاده از Tor Android رسمی Guardian Project به‌عنوان موتور Native، نه کپی SlipNet.
- مدیریت چرخه اتصال و وضعیت‌ها از کانال Flutter/Android.
- بررسی Bootstrap و آماده‌شدن SOCKS Listener محلی.
- SOCKS5 فقط روی `127.0.0.1`، نه شبکه LAN یا اینترنت.
- پروفایل‌های Tor با AES-GCM و کلید Android Keystore رمزنگاری می‌شوند.
- Bridge مستقیم سفارشی قابل ذخیره و استفاده است.
- `obfs4`، `Snowflake` و `Meek` عمداً غیرفعال‌اند؛ در این بسته باینری واقعی Pluggable Transport آن‌ها وجود ندارد.
- هیچ VPNService دوم ایجاد نمی‌شود.

## وضعیت مهم ادغام

Tor daemon و SOCKS adapter کد مستقل دارند. اتصال نهایی outbound به تولید تنظیمات Hiddify Core هنوز نیازمند ادغام در سورس Core/Flutter نسخه دقیق است؛ این بخش را به‌دلیل نداشتن سورس قابل بررسی، فعال یا تست‌شده اعلام نمی‌کنیم. نمونه outbound و Full Tor route fragment در `core/` آمده است.

## فایل‌های مهم

- `packages/vision_tor/` — Flutter plugin، کد Kotlin، مدل‌ها، API، UI و Unit Tests
- `branding/` — لوگوی Vision VPN
- `android/branding/` — منابع پیشنهادی Android
- `core/` — قطعات Outbound و Routing
- `.github/workflows/` — CI، Build، Release و Code Analysis
- `INTEGRATION_CHECKLIST_FA.md` — راهنمای ادغام
- `BUILD_VALIDATION_FA.md` — گزارش دقیق بررسی‌های انجام‌شده و اجرا‌نشده
- `TOR_INTEGRATION.md` — طراحی فنی Tor
- `FEATURE_INVENTORY.csv` — فهرست قابلیت‌های Hiddify و وضعیت اثبات

## مجوز

این Overlay کد SlipNet را کپی نمی‌کند. مجوز Hiddify و الزامات Fork عمومی/انتشار با GitHub Actions/Attribution باید برای Fork نهایی رعایت شود. به `THIRD_PARTY_NOTICES.md` و `LICENSE_NOTICE.md` رجوع کنید.
