# MG Home Assistant

MG4 araç ekranındaki uygulamadır. Araç açıkken ve Wi‑Fi varken batarya, menzil, şarj, lastik, konum gibi bilgileri **Home Assistant**’a gönderir.

Araç uyanık kalmak zorunda değil; sen kullanırken / şarjdayken veri yeter. HA’nın dışarıdan erişilebilir bir adresi ve long-lived token’ı olmalı.

---

## Ne lazım?

1. Home Assistant (dışarıdan açılabilen URL)
2. Bu uygulama (arabaya kurulu)
3. İsteğe bağlı ama önerilen: HA entegrasyonu  
   → [merthankaraman/mg4-ha-bridge](https://github.com/merthankaraman/mg4-ha-bridge)

---

## Kurulum (kısa)

### 1. Home Assistant

1. HACS → Custom repositories → yukarıdaki repo → **Integration**.
2. **MG Home Assistant Bridge** indir → HA’yı yeniden başlat.
3. Entegrasyon ekle → cihaz adı + **varlık öneki** (arabadaki önek ile aynı).

Kurulum detayı: [mg4-ha-bridge README](https://github.com/merthankaraman/mg4-ha-bridge#readme)

### 2. Arabaya uygulama

- `car` sürümünü yükle (imzalı release).
- Uygulamayı aç → HA URL + token kaydet.
- Token’ı ekranda yazmak istemezsen: aynı Wi‑Fi’de **Siteden al** ile telefondan gönder.
- **Açılışta başlat** ve gerekirse **Açılışta WiFi aç** açık olsun → Kaydet.

Servis kendi başlar; arayüzü sürekli açık tutmana gerek yok.

### 3. Kontrol

- Arabada durum satırında gönderim görünür.
- HA’da sensörler güncellenir (şarj %, menzil, lastik, konum…).

---

## Güncelleme (OTA)

Arabada **Güncelleme kontrol et** → indir → kur.  
Yeni sürüm GitHub Releases’e yüklenmiş olmalı.

---

## Notlar

- Varsayılan olarak yalnız **Wi‑Fi** varken gönderir.
- Gönderim aralığını uygulamadan ayarlarsın (örn. 1 dakika).
