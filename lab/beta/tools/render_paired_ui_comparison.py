"""Compare actual native captures using the frozen production FidelityFixtures data.

This evidence is deliberately separate from extraction/ledger parity and journey acceptance.
"""
from pathlib import Path
from PIL import Image, ImageChops
from io import BytesIO
import hashlib
import base64
import json

folder = Path(__file__).resolve().parents[1]
images = folder / 'build/reports/ui-parity'
pages = {'cards': 'ملخص البطاقات', 'cardDetail': 'تفاصيل البطاقة الفرعية', 'loans': 'ملخص القروض', 'loanDetail': 'تفاصيل القرض', 'preferences': 'المظهر واللغة', 'home': 'الرئيسية', 'accounts': 'ملخص الحسابات', 'accountDetail': 'تفاصيل الحساب', 'merchants': 'التجار', 'daily': 'المصاريف اليومية'}
entries = []
image_cache = {}
for page in pages:
    for lang in ['ar', 'en']:
        for theme in ['light', 'dark']:
            for position in ['', '-bottom']:
                name = f'{page}-{lang}-{theme}{position}.png'
                production = images / 'production' / name
                lab = images / 'lab-approved-fixture' / name
                if not production.exists() or not lab.exists():
                    raise SystemExit(f'Missing real capture: {name}')
                a, b = Image.open(production).convert('RGB'), Image.open(lab).convert('RGB')
                if a.size != b.size:
                    raise SystemExit(f'Viewport mismatch: {name}')
                # Exclude the global header only, because the lab has an intentional Beta badge.
                crop = (0, 195, a.width, a.height)
                difference = ImageChops.difference(a.crop(crop), b.crop(crop))
                changed = sum(pixel != (0, 0, 0) for pixel in difference.getdata())
                entry = dict(page=page, language=lang, theme=theme, position='bottom' if position else 'top',
                             width=a.width, height=a.height, compared_region=list(crop),
                             changed_pixels=changed, total_pixels=a.width*(a.height-195),
                             difference_bounds=difference.getbbox(),
                             max_channel_difference=max(high for low,high in difference.getextrema()))
                for key, file in [('production', production), ('lab', lab)]:
                    actual = Image.open(file).convert('RGB')
                    digest = hashlib.sha256(actual.tobytes()).hexdigest()
                    if digest not in image_cache:
                        buffer = BytesIO()
                        actual.save(buffer, format='WEBP', lossless=True)
                        image_cache[digest] = 'data:image/webp;base64,' + base64.b64encode(buffer.getvalue()).decode()
                    entry[key] = digest
                entries.append(entry)
manifest = dict(production_commit='0d86c5d3', scope='Ten frozen-data native screen pairs only; no whole-app acceptance.',
                entries=[{k: v for k, v in e.items() if k not in ['production', 'lab']} for e in entries])
(folder/'UI_PAIRED_COMPARISON.json').write_text(json.dumps(manifest, indent=2)+'\n')
html = '''<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>مقارنة مصروف والمختبر بالبيانات نفسها</title>
<style>*{box-sizing:border-box}body{margin:0;background:#f2f6f5;color:#193b37;font:17px/1.8 Arial,sans-serif}main{max-width:1250px;margin:auto;padding:24px}header,section{background:#fff;border:1px solid #d8e4e0;border-radius:16px;padding:22px;margin-bottom:20px}h1{margin:0;font-size:28px}p{max-width:1000px}.notice{border-right:4px solid #a96917;padding:12px;background:#fff7e8}.controls{display:flex;gap:14px;flex-wrap:wrap;align-items:center}label{display:grid;gap:4px}select,button{font:inherit;padding:8px;border:1px solid #bacfc8;border-radius:8px;background:#fff;color:inherit}button{cursor:pointer}.pair{display:grid;grid-template-columns:1fr 1fr;gap:18px;direction:ltr}.pair figure{margin:0;min-width:0}.pair img{width:100%;height:auto;display:block;border:1px solid #d8e4e0}.pair figcaption{text-align:center;font-weight:bold;margin-bottom:8px}.metric{font-weight:bold}.facts{display:flex;gap:18px;flex-wrap:wrap}.facts span{background:#e9f3f0;padding:8px 14px;border-radius:8px}#overlay{display:none;position:relative;max-width:540px;margin:15px auto;direction:ltr}#overlay img{display:block;width:100%}#overlay .lab{position:absolute;inset:0;opacity:.5}input{accent-color:#0f766e}small{color:#536d67}.exact{color:#087552}.different{color:#925414}@media(max-width:650px){main{padding:12px}header,section{padding:14px}h1{font-size:23px}.pair{gap:8px}}</style>
<main><header><small>الدليل approved-2026-10-10 · الفعلي 0d86c5d3 · التعديل في المختبر فقط</small><h1>مقارنة فعلية بالبيانات نفسها</h1><p>الصور ملتقطة من واجهتي Android داخل المحاكي بالمقاس نفسه، وليست رسومات تصميم. المختبر يعرض هذه القيم عبر مسار لوحة التحكم والتنقل الحقيقي. المرجع هو بيانات FidelityFixtures المجمدة في الفعلي.</p><p class="notice">هذه مقارنة لأعلى وأسفل عشر صفحات فقط؛ لا تغطي وسط الصفحات الطويلة. لا تعني اكتمال بقية الواجهة، ولا تثبت استخراج الرسائل أو صحة دفتر العمليات؛ تلك لها أدلة مستقلة. شارة Beta فرق مقصود في رأس المختبر. قياس البكسلات أدناه يستثني الرأس فقط (أول 195 بكسل)، ويشمل بقية الصورة.</p><div class="facts"><span>مستحق التسهيل: 2,200 SAR</span><span>صرف البطاقة الأساسية: 800 SAR</span><span>صرف الفرعية: 600 SAR</span><span>المتبقي للقرض: 84,200 SAR</span><span>القسط: 1,800 SAR</span></div></header>
<section><div class="controls"><label>الصفحة<select id="page">PAGES</select></label><label>اللغة<select id="language"><option value="ar">العربية</option><option value="en">English</option></select></label><label>المظهر<select id="theme"><option value="light">فاتح</option><option value="dark">داكن</option></select></label><label>موضع التمرير<select id="position"><option value="top">أعلى الصفحة</option><option value="bottom">نهاية الصفحة</option></select></label><button id="toggle">عرض التراكب</button></div><p id="metric" class="metric"></p><small id="bounds"></small><div class="pair" id="pair"><figure><figcaption>مصروف الفعلي</figcaption><img id="production" alt="الفعلي"></figure><figure><figcaption>مصروف المختبر</figcaption><img id="lab" alt="المختبر"></figure></div><div id="overlay"><img id="base" alt="الفعلي"><img id="over" class="lab" alt="المختبر متراكب"></div><label id="opacityLabel" style="display:none">شفافية المختبر<input id="opacity" type="range" min="0" max="100" value="50"></label></section>
<section><h2>كيف نقرأ الفروق؟</h2><p>تطابق البكسلات في لقطة محددة دليل على العرض في تلك الحالة فقط. أي فرق ظاهر يبقى مفتوحًا حتى يُفسّر أو يُصلح. نُقلت صفحات التجار والتفاصيل اليومية وتخصيص بيانات الرسم. مقارنة صفحات المراجعة وبقية الإعدادات والرحلات ما زالت قيد الإكمال. أربع لقطات سفلية لتفاصيل الحساب تختلف بفروق لونية لا تتجاوز 7 من 255 عند حواف الرسم والنص؛ تبقى محسوبة كفروق ولا تُحوّل إلى تطابق بكسلي. لا نرفع قبول رحلة كاملة اعتمادًا على تطابق لقطة واحدة.</p><p>التحويل بين الحسابات يُختبر أيضًا بإشارتي الدخول والخروج حسب هوية الحساب والبنك. هذا الفحص منفصل عن بيانات البطاقات والقرض المعروضة هنا.</p><a href="UI_PARITY_ACCEPTANCE_AR.html">حالة جميع الرحلات ومعايير التسليم</a> · <a href="UI_FINANCIAL_COMPARISON_AR.html">مقارنة الأرقام المالية</a></section></main>
<script>const evidence=__MASROOF_PAIR_ENTRIES__;const images=__MASROOF_PAIR_IMAGES__;const $=id=>document.getElementById(id);function render(){const e=evidence.find(x=>['page','language','theme','position'].every(k=>x[k]===$(k).value));$('production').src=$('base').src=images[e.production];$('lab').src=$('over').src=images[e.lab];const pct=(100*e.changed_pixels/e.total_pixels).toFixed(3);$('metric').textContent=e.changed_pixels===0?'منطقة المقارنة متطابقة بكسليًا في هذه اللقطة.':`يوجد اختلاف في ${e.changed_pixels.toLocaleString('en')} بكسل (${pct}% من منطقة المقارنة)، وأقصى فرق لوني ${e.max_channel_difference} من 255.`;$('metric').className='metric '+(e.changed_pixels===0?'exact':'different');$('bounds').textContent=`الأبعاد ${e.width} × ${e.height} بكسل؛ نطاق المقارنة يبدأ عند y=195.`;}for(const k of ['page','language','theme','position'])$(k).onchange=render;let overlay=false;$('toggle').onclick=()=>{overlay=!overlay;$('pair').style.display=overlay?'none':'grid';$('overlay').style.display=overlay?'block':'none';$('opacityLabel').style.display=overlay?'grid':'none';$('toggle').textContent=overlay?'عرض متجاور':'عرض التراكب';};$('opacity').oninput=()=>{$('over').style.opacity=$('opacity').value/100;};render();</script></html>'''
html=html.replace('PAGES',''.join(f'<option value="{key}">{label}</option>' for key,label in pages.items())).replace('__MASROOF_PAIR_ENTRIES__',json.dumps(entries)).replace('__MASROOF_PAIR_IMAGES__',json.dumps(image_cache))
(folder/'UI_PAIRED_COMPARISON_AR.html').write_text(html)
print(f'{len(entries)} real capture pairs; {sum(e["changed_pixels"]==0 for e in entries)} body regions pixel-identical')
