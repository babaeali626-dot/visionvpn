# قطعات اتصال به Core

`tor_socks_outbound.json` قطعه Outbound از نوع SOCKS5 است؛ پورت آن در اجرا باید از `TorService.getSocksPort()` گرفته شود، نه اینکه 9050 به‌صورت ثابت فرض شود.

`strict_route_fragment.json` تنها الگوی مرجع Routing است. پیش از ادغام در Hiddify Core باید با نسخه و Schema دقیق Core همان Release اعتبارسنجی شود. این بسته به Core دسترسی نداشته و این قطعه را در Core فعال نکرده است.

در حالت Full Tor، UDP باید Reject شود و `route.final` باید روی `vision-tor` قرار گیرد؛ هیچ `direct` fallback نباید باقی بماند. DNS نیز باید از سروری با `detour: vision-tor` عبور کند و DNS پیش‌فرض Direct حذف/غیرفعال شود. ساختار کامل DNS به Schema نسخه Core وابسته است و عمداً به‌عنوان پیکربندی تأییدشده عرضه نشده است.
