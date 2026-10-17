# قواعد مشروع "رفيق"

1. عند عمل Commit لأي تغييرات، يجب أن تكون رسالة الـ Commit باللغة العربية الفصحى، وأن تكون واضحة وموجزة (مثلاً: "إصلاح: معالجة أخطاء بناء الإصدار v0.3" أو "ميزة: إضافة مهارة الاتصال والواتساب").
2. لا تقم أبداً بتشغيل أوامر البناء المحلية (مثل gradle build) داخل بيئة Termux.

3. After pushing code, you MUST run `scripts/watch_ci.sh`. If it fails, read the logs, fix the errors, and push again. Do not stop until the build passes.
