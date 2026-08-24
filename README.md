# MG Home Assistant

Sürüm: **0.2.5**

MG4 infotainment’dan (veya Demo’da Sim varyantından) veriyi Home Assistant’a REST ile gönderir.

Gönderilenler: SOC, menzil, km, dış sıcaklık, lastik, şarj (AC/batarya V·A + güç), son güncelleme, konum.

HA’ya eklenti kurulmaz. Varlıklar `group.mg4` altında toplanır (Cihazlar listesinde cihaz olarak görünmez; REST bunu yapamaz).

## 1) Home Assistant

Her gönderimde uygulama token ile önce varlıkları yazar:

- `POST /api/states/sensor.mg4_*`
- `POST /api/states/binary_sensor.mg4_charging`
- `POST /api/states/device_tracker.mg4` (konum varsa)

Hemen ardından `POST /api/states/group.mg4` ile **`group.mg4`** oluşturur / günceller
(üyeleri `entity_id` attribute’unda). Böylece HA’da `group:` YAML / `group.set` gerekmez.

Dashboard’da bir Entities kartına `group.mg4` ekle. Ayarlar → Varlıklar’da da görünür.

Eski custom component (`mg4_bridge`) kurduysan kaldırabilirsin; artık gerekmez.

## 2) Token’ı arabaya alma (telefona APK yok)

Telefona uygulama kurmana gerek yok. Araba küçük bir web sayfası açar.

1. Araba ve telefon **aynı WiFi**’de olsun (garaj / ev).
2. Arabada **Siteden al**’a bas — ekranda `http://192.168.x.x:18765/` gibi bir adres çıkar.
3. Telefonda tarayıcıya o adresi yaz.
4. HA URL + long-lived token’ı yapıştır → **Arabaya kaydet**.
5. Araç “Siteden alındı” der.

Token: HA’da kullanıcı adı (sol alt) → **Güvenlik** → **Long-lived access tokens** → Create token.

## 3) Telefonda / Sim (sadece test)

Build Variant: **simDebug**. Demo / HA test için. Token aktarımı için şart değil.

## 4) Arabaya

Build Variant: **carDebug**, `platform.p12` ile imzala (DriveHub Dort ile aynı).

## Varlıklar (`group.mg4`)

| Varlık | Anlam |
|---|---|
| Batarya | SOC % |
| Menzil | km |
| Kilometre | odometre |
| Dış sıcaklık | °C |
| Lastik FL/FR/RL/RR | bar |
| Şarj durumu | `unplugged` / `charging_ac` / … |
| AC voltaj / akım | V / A |
| Batarya voltaj / akım | V / A |
| Şarj gücü | kW (AC veya batarya V×A) |
| Şarjda | evet/hayır |
| Son güncelleme | zaman damgası |
| Konum | harita |
