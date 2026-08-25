# MG Home Assistant

Sürüm: **0.3**

MG4 infotainment’dan (veya Demo’da Sim varyantından) araç verisini Home Assistant’a gönderir.

Araç **herhangi bir WiFi’den** çalışır: public HA URL + long-lived token yeter. LAN’da HA’nın arabayı poll etmesi yok.

## 1) Home Assistant — önerilen yol (cihaz + hatırlama)

### HACS ile (en kolay)

1. HACS kurulu olsun.
2. HACS → **⋮** → **Custom repositories** → repo ekle:
   - URL: `https://github.com/merthankaraman/mg-home-assistant`
   - Category: **Integration**
3. HACS → Integrations → **MG Home Assistant Bridge** → Download.
4. HA’yı yeniden başlat.
5. **Ayarlar → Cihazlar ve hizmetler → Entegrasyon ekle → MG Home Assistant Bridge**.

- **Cihaz adı:** örn. `MG4`
- **Varlık öneki:** uygulamadaki önek ile aynı (varsayılan `mg4`)

### Elle kopyalama

Repo’daki `custom_components/mg4_bridge` klasörünü HA’ya koy:

```
config/custom_components/mg4_bridge/
```

(Samba, Studio Code Server veya File editor ile.) Sonra restart + entegrasyon ekle (yukarıdaki adım 5).

Ayarlar → Cihazlar’da tek **MG4** cihazı görünür; restart sonrası değerler Restore ile kalır. Uygulama `mg4_bridge.push` yazar; component yoksa REST + `group.<öneki>` fallback’e düşer.

## 2) Token’ı arabaya alma (telefona APK yok)

1. Araba ve telefon **aynı WiFi**’de olsun (sadece token aktarımı için).
2. Arabada **Siteden al** → ekranda `http://192.168.x.x:18765/` çıkar.
3. Telefonda tarayıcıya yaz → HA URL + token → **Arabaya kaydet**.

Token: HA kullanıcı menüsü → **Güvenlik** → **Long-lived access tokens**.

HA URL dışarıdan erişilebilir olmalı (Nabu Casa, reverse proxy, vb.) — araba ev dışı WiFi’den de gönderebilsin.

## 3) Telefonda / Sim (test)

Build Variant: **simDebug**.

## 4) Arabaya

Build Variant: **carRelease** (veya carDebug), `tools/sign_and_install_release.bat` ile platform imzala.

## 5) OTA (GitHub Release)

Uygulama `merthankaraman/mg-home-assistant` Releases API’sinden güncelleme çeker.

1. `sign_and_install_release.bat` → `tools/releases/MG4_HA_<sürüm>.apk` + `.sha256`
2. `publish_github_release.bat` → GitHub Release’e yükler
3. Arabada **Güncelleme kontrol et** → indir → SHA-256 doğrula → **İndirilenler**’den kur

Release’te hem `.apk` hem `MG4_HA_x.y.apk.sha256` olmalı; aksi halde indirme engellenir.

## Varlıklar (prefix `mg4` örneği)

| Anahtar | Anlam |
|---|---|
| battery | SOC % |
| range | menzil km |
| mileage | odometre |
| exterior_temperature | °C |
| tire_pressure_* | kPa |
| charging_status | `unplugged` / `AC` / `DC` / … |
| charging | şarjda mı |
| ac_* / battery_* | V / A / kW |
| station_dc_* | yalnız DC şarjdayken |
| last_update | zaman |
| location | GPS |

## Fallback (component yoksa)

Uygulama `POST /api/states/...` ile varlık yazar ve `group.<öneki>` oluşturur. Bu yolda Cihazlar listesinde gerçek cihaz olmaz; component kurulunca cihaz + kalıcılık gelir.
