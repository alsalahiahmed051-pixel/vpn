# Python VPN — مشروع VPN حقيقي بالـ Python

VPN فعلي على مستوى الـ IP (Layer-3) باستخدام **TUN interface** و**AES-256-GCM** للتشفير.
الفكرة شبيهة بـ WireGuard من ناحية التصميم (UDP + AEAD + TUN)، لكن الكود مبسّط ومقروء بالكامل.

A real Layer-3 VPN written in Python: TUN-based packet tunneling with
AES-256-GCM authenticated encryption over UDP. Educational but functional.

---

## كيف يعمل / How it works

```
┌────────────┐   ip packet    ┌────────────┐   UDP+AES-GCM   ┌────────────┐   ip packet   ┌──────────┐
│ كل التطبيقات│ ─────────────► │   TUN      │ ──────────────► │   TUN      │ ────────────► │ الإنترنت │
│   client   │                │  client.py │                 │  server.py │  (NAT/MASQ)   │          │
└────────────┘ ◄───────────── └────────────┘ ◄────────────── └────────────┘ ◄──────────── └──────────┘
```

1. يُنشئ كلٌّ من العميل والسيرفر واجهة TUN افتراضية (`vpn0`).
2. أي حزمة IP تخرج من العميل عبر `vpn0` تُغلَّف بـ AES-256-GCM وتُرسل عبر UDP إلى السيرفر.
3. السيرفر يفك التشفير، يكتب الحزمة على TUN عنده، ويعمل NAT/MASQUERADE فيخرج التراففيك للإنترنت.
4. الردود تأخذ المسار العكسي.

---

## المتطلبات / Requirements

- **نظام Linux** (Ubuntu/Debian/Arch...) — TUN ميزة kernel، فلا يعمل على Windows/macOS بدون تعديل
- Python 3.9+
- صلاحيات root (`sudo`) — مطلوبة لإنشاء TUN وتعديل routes
- منفذ UDP مفتوح على السيرفر (افتراضي 51820)

---

## التثبيت / Installation

```bash
git clone <repo> vpn-project && cd vpn-project
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

---

## التشغيل / Usage

### 0) أسرع طريقة للتجربة (بدون VPS ولا جهاز ثاني) / Quickest local test

سكربت يستخدم Linux network namespaces ليشغّل السيرفر والعميل في "شبكتين" معزولتين على نفس جهازك ويتحقق من نجاح النفق:

```bash
sudo bash scripts/local-test.sh
```

النتيجة المتوقعة: `✅ SUCCESS — encrypted tunnel is working end-to-end.`

ما يفعله السكربت بالتفصيل:
1. ينشئ namespace باسم `vpn-srv-ns` و آخر `vpn-cli-ns`
2. يربطهما بـ veth pair (يحاكي "الإنترنت" بينهما) — السيرفر `192.0.2.1` والعميل `192.0.2.2`
3. يولّد كلمة سر عشوائية ويكتب كونفجين مؤقتين
4. يشغّل `server.py` داخل ns السيرفر و `client.py` داخل ns العميل
5. يعمل `ping 10.8.0.1` من ns العميل (يمرّ كل packet مشفّراً عبر UDP/51820)
6. ينظّف كل شيء عند الانتهاء

### 1) جهّز السيرفر / Server

```bash
# انسخ الكونفج وعدّل كلمة السر
cp config/server.example.yaml config/server.yaml
# ولّد كلمة سر قوية:
openssl rand -hex 32
# الصقها في password داخل الـ yaml

# فعّل الـ NAT و IP forwarding (مرة واحدة)
sudo bash scripts/setup-server.sh

# شغّل السيرفر
sudo python3 server.py --config config/server.yaml
```

### 2) جهّز العميل / Client

```bash
cp config/client.example.yaml config/client.yaml
# عدّل: server_host, password (نفس كلمة سر السيرفر), tun_ip (فريد لكل عميل)

sudo python3 client.py --config config/client.yaml
```

### 3) اختبر الاتصال / Test it

من العميل:
```bash
ping 10.8.0.1                # يجب أن يستجيب السيرفر
ip route get 8.8.8.8         # يبين المسار
curl -sS https://api.ipify.org   # إن فعّلت route_all_traffic سيظهر IP السيرفر
```

---

## بنية المشروع / Project layout

```
vpn-project/
├── server.py              # سيرفر VPN
├── client.py              # عميل VPN
├── vpn/
│   ├── crypto.py          # AES-256-GCM + scrypt KDF
│   ├── tun.py             # غلاف TUN على Linux
│   ├── protocol.py        # تنسيق الحزم (version | type | payload)
│   └── config.py          # تحميل YAML
├── config/
│   ├── server.example.yaml
│   └── client.example.yaml
├── scripts/
│   ├── setup-server.sh    # NAT + ip_forward
│   └── teardown.sh        # تنظيف
└── requirements.txt
```

---

## التصميم الأمني / Security design

| الجانب | الاختيار |
|---|---|
| التشفير | AES-256-GCM (AEAD) |
| اشتقاق المفتاح | scrypt (n=2¹⁴, r=8, p=1) |
| الـ Nonce | 4-byte random prefix + 8-byte counter (لا تكرار) |
| المصادقة | المفتاح المشترك (PSK) + auth tag في كل حزمة |
| النقل | UDP (low overhead, لا head-of-line blocking) |

### قيود معروفة / Known limitations (هذه نسخة تعليمية)

- ⚠️ **لا يوجد forward secrecy** — تسريب كلمة السر يكشف كل الحركة السابقة. للحل الصحيح: ECDH handshake (X25519) لكل جلسة.
- ⚠️ **لا حماية صريحة من الـ replay** — رغم أن الـ nonce العدّاد يعطي حماية جزئية، نسخة الإنتاج تحتاج replay window.
- ⚠️ **مصادقة العميل بسيطة** — أي شخص معه كلمة السر يدخل. أضِف شهادات أو مفاتيح عمومية لكل عميل.
- ⚠️ **عميل واحد لكل tunnel-IP** — لا يوجد توزيع IP تلقائي.

هذه القيود مقصودة لجعل الكود قابلاً للقراءة. بنية الكود (crypto/tun/protocol/server/client) جاهزة للتوسعة بإضافة handshake الصحيح وreplay window دون إعادة كتابة شاملة.

---

## استكشاف الأخطاء / Troubleshooting

- **`Failed to create TUN interface … (need root?)`** — شغّل بـ `sudo`.
- **`ip: command not found`** — ثبّت `iproute2`: `sudo apt install iproute2`.
- **العميل يتصل لكن لا إنترنت** — تأكد من `setup-server.sh` (NAT + IP forwarding) وأن firewall السيرفر يسمح بـ UDP/51820.
- **بطء شديد** — جرّب MTU=1280، وتأكد من عدم وجود double-encapsulation (مثلاً VPN داخل VPN).
- **`Decryption failed`** — كلمة السر مختلفة بين الطرفين، أو نسخة بروتوكول مختلفة.

---

## ⚠️ الاستخدام القانوني / Legal use

استخدم هذا المشروع فقط على الشبكات والخوادم التي تملكها أو تملك إذنًا صريحًا لاستخدامها.
استخدام VPN يخضع لقوانين بلدك وقوانين الجهة التي تتصل بشبكتها.

Use only on networks/servers you own or have explicit permission to use.
VPN usage is subject to local laws and the policies of any network you connect to.

---

## ترخيص / License

MIT
