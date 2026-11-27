# Vision VPN — نسخه آزمایشی مبتنی بر Hiddify 5.9.15

این بسته سورس کامل Hiddify نسخه 5.9.15 به‌همراه تغییرات برندینگ و یکپارچه‌سازی اولیه موتور Tor برای Android است. تمام قابلیت‌های قبلی Hiddify در سورس مبنا حفظ شده‌اند و تغییرات در حد امکان افزایشی هستند.

## تغییرات
- نام نمایشی برنامه: Vision VPN (شناسه package حفظ شده تا نصب روی نسخه قبلی Hiddify با تغییر شناسه تداخل نکند).
- آیکون جدید در منابع Android.
- صفحه Tor در Settings.
- اتصال Flutter به لایه Native با MethodChannel و EventChannel.
- اجرای Tor از کتابخانه Guardian Project و SOCKS loopback.
- ورودی Bridgeهای vanilla با اعتبارسنجی سخت‌گیرانه.

## محدودیت‌های مهم
این نسخه هنوز یک محصول نهایی Full-Tor نیست. Tor daemon اجرا می‌شود، اما outbound هسته Hiddify و قوانین Routing/Kill Switch به آن متصل نشده‌اند. تا زمانی که سورس واقعی `hiddify-core` از Submodule دریافت و تغییرات Go در آن اعمال و تست نشود، عبور ترافیک Hiddify از Tor تأییدشده نیست. Snowflake/obfs4/Meek نیز به علت نبود PT binary در این بسته فعال نیستند.

## Build
1. سورس را استخراج کنید.
2. Submodule هسته را دریافت کنید: `git submodule update --init --recursive` (در ZIP اولیه، پوشه submodule خالی است).
3. Flutter SDK مطابق `pubspec.yaml` یعنی Flutter 3.38.5 و Android SDK/NDK تعریف‌شده در Gradle نصب باشد.
4. اجرا: `flutter pub get` سپس `flutter build apk --debug --split-per-abi`.
5. برای بررسی Native Tor، از دستگاه Android API 24 یا بالاتر استفاده کنید.

برای نسخه Release امضای اختصاصی و بررسی کامل مجوزها/آزمون‌های دستگاه ضروری است.
