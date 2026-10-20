"""Render the lab's evidence checklist. Pending rows never become accepted from test counts."""
from pathlib import Path
from html import escape
import json
import base64

root = Path(__file__).resolve().parents[3]
folder = root / 'lab/beta'
capture = folder / 'build/reports/ui-parity/lab/approved-card-registry.png'
capture_html = ('<figure><img style="max-width:360px;width:100%;border-radius:16px" src="data:image/png;base64,' + base64.b64encode(capture.read_bytes()).decode() + '"><figcaption>المختبر على المحاكي: تسمية البطاقة، بقاء آخر أربعة أرقام، وحذف الكرت المحيط وزر الرجوع المكرر. المطابقة الكاملة ما زالت قيد العمل.</figcaption></figure>') if capture.exists() else ''
data = json.loads((folder / 'UI_PARITY_ACCEPTANCE.json').read_text())
labels = {'pending': 'لم يبدأ النقل', 'in_progress': 'قيد النقل والمقارنة', 'accepted': 'مقبول بدليل'}
rows = ''
for row in data['pages']:
    state = 'accepted' if row['accepted'] else row['status']
    evidence = ' — '.join(escape(str(row.get(key) or 'لم يُرفق بعد')) for key in ['lab_visual_reference', 'same_data_comparison'])
    rows += f'<tr><td><strong>{escape(row["label_ar"])}</strong><small>{escape(row["production_source"])}</small></td><td><span class="state {state}">{labels[state]}</span></td><td>{evidence}</td><td>{"تم" if row["old_renderer_removed"] else "لم يُغلق بالكامل"}</td></tr>'
notes = ''.join(f'<li>{escape(item)}</li>' for item in data.get('implementation_notes', []))
removed = ''.join(f'<li dir="ltr">{escape(item)}</li>' for item in data['removed_implementations'])
html = '''<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>مصروف المختبر — معايير التسليم ودليل المطابقة</title><style>
*{box-sizing:border-box}body{margin:0;background:#f4f7f6;color:#193c38;font:17px/1.9 Arial,sans-serif}main{max-width:1180px;padding:30px 22px;margin:auto}header{background:#e4f2ef;border:1px solid #dae5e2;border-radius:22px;padding:28px}h1{font-size:30px;margin:8px 0}h2{font-size:23px;margin-top:32px}p{max-width:920px}a{color:#0d756e}table{border-collapse:collapse;width:100%;background:#fff}td,th{border:1px solid #dae5e2;padding:13px;text-align:right;vertical-align:top}th{background:#e4f2ef}small{display:block;font-size:12px;direction:ltr;text-align:right;overflow-wrap:anywhere;color:#526d68}li{margin:10px 0}.notice{background:white;border-right:4px solid #b45309;padding:16px}.state{display:inline-block;white-space:nowrap;background:#fff4e2;color:#8a460c;padding:4px 10px;border-radius:20px;font-size:14px}.accepted{background:#e4f2ef;color:#0d756e}.links{display:flex;flex-wrap:wrap;gap:16px}.links a{background:#fff;padding:10px 16px;border:1px solid #dae5e2;border-radius:12px}@media(max-width:700px){main{padding:15px}h1{font-size:24px}td,th{padding:7px;font-size:13px}}
</style><main><header><small>النطاق: المختبر فقط · الدليل approved-2026-10-10</small><h1>كيف تعرف أن النقل اكتمل؟</h1><p>التسليم هو تجربة مشابهة للفعلي بكود عرض منقول منه، وبيانات من المحرك الجديد، وتنفيذ واحد لكل صفحة داخل المختبر. أرقام الاختبارات وحدها لا تغيّر حالة أي صفحة إلى «مقبول».</p></header>
<p class="notice">العمل ما زال قيد التنفيذ. الصفحات التالية لم تُعتمد كلها بصريًا ووظيفيًا، ولا يُعد هذا التقرير إعلان اكتمال النقل.</p>
<h2>معايير القبول</h2><ol><li>نفس الخطوات والأزرار وترتيب الإجراءات، من اختيار البنك والفترة واعتماد الأصول إلى استيراد الرسائل والرئيسية والمراجعة.</li><li>مقارنة صور الفعلي والمختبر على نفس البيانات والحجم واللغة والمظهر واليوم المحدد. تُراجع الفروق، لا يُكتفى بوجود صورتين.</li><li>جدول يوضح مبالغ وحركات نفس المدخلات في الفعلي والمحرك الجديد، مع سبب صريح لأي فرق؛ لا أرقام مخمّنة ولا إضافة مكررة.</li><li>تجربة الاستيراد ثم تعديل بطاقة وإغلاق التطبيق وفتحه، مع بقاء الحركات والقرارات والأرقام صحيحة.</li><li>حذف العرض القديم والدوال والمسارات والنصوص التي أصبحت غير مستخدمة، وتحويل الاختبارات للمسار الفعلي الجديد.</li><li>إغلاق الأعطال التي أبلغت عنها بدليل إعادة إنتاج وإصلاح، ونجاح CI على آخر تعديل.</li></ol>
<h2>مرجع التصميم والأدلة المتاحة</h2><p>تمت مراجعة <a href="../../docs/DESIGN_SYSTEM.md">دليل التصميم المعتمد</a>، ونُقلت مكونات من كود الفعلي مع تسجيل المصدر في <a href="APPROVED_UI_SOURCES.json">سجل المصادر</a>. تتوفر 256 لقطة أصلية لعدد 32 صفحة باللغتين والمظهرين. هذه مراجع مصورة وليست وحدها إثبات تطابق المختبر.</p><div class="links"><a href="build/reports/ui-parity/production/home-ar-light.png">الرئيسية في الفعلي</a><a href="build/reports/ui-parity/production/myCards-ar-light.png">إدارة البطاقات في الفعلي</a><a href="build/reports/ui-parity/production/cardDetail-ar-light.png">تفاصيل البطاقة في الفعلي</a><a href="build/reports/ui-parity/production/review-ar-light.png">المراجعة في الفعلي</a><a href="build/reports/ui-parity/production/onboarding-ar-light.png">الإعداد في الفعلي</a></div>
<h2>المعاينة الحالية</h2>CAPTURE<h2>التغييرات الأخيرة</h2><ul>NOTES</ul><h2>حالة الرحلات</h2><table><thead><tr><th>الرحلة ومصدرها</th><th>الحالة</th><th>دليل المختبر ومقارنة البيانات</th><th>إغلاق التنفيذ السابق</th></tr></thead><tbody>ROWS</tbody></table>
<h2>ما أُزيل بدل إبقاء نسختين</h2><ul>REMOVED</ul><p>الدخول إلى المختبر يبقى من إعدادات المطورين في حزمة مصروف. الشارة والعودة للفعلي والتصفير الصريح تخص المختبر. لا تُعدّل قاعدة بيانات الفعلي ولا تفسيره ولا واجهته، وتبقى قواعد LessCode والمحرك الجديد وML كما هي.</p></main></html>'''
(folder / 'UI_PARITY_ACCEPTANCE_AR.html').write_text(html.replace('CAPTURE', capture_html).replace('NOTES', notes).replace('ROWS', rows).replace('REMOVED', removed))
