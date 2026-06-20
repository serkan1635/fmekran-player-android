# fmekran Android Player v2 — Xibo Mimarisi Analiz Edilerek Düzeltildi

## Xibo for Android İncelemesinden Çıkan Bulgular

Xibo APK'sını decompile edip incelediğimde şu mimariyi tespit ettim:
- Cihaz içinde yerel HTTP sunucu (nanohttpd, `localhost:8090`)
- CMS ile XMDS/SOAP protokolü üzerinden konuşma
- "Connect to your CMS" ekranında activation code veya manuel CMS adresi girme
- JavaScript köprüsü (`Android.loaded()`, `Android.manualConnect()`) ile native ↔ web iletişimi
- Bağlantı durumunun HER ZAMAN ekranda görünür olması (MAC, IP, CMS adresi, hata mesajları)

## Bizim Asıl Sorunumuzun Kök Nedeni

Xibo'nun "her zaman görünür durum" prensibini uygulayınca, sorunun APK'da değil
**sunucu tarafında** olduğu ortaya çıktı:

1. **`/player/index.html` dosyası kalıcı değildi.** Frontend'in `Dockerfile`'ı bu
   dosyaları hiç kopyalamıyordu — geçmişte her seferinde `docker cp` ile container'a
   elle yamanmıştı. Her `docker compose up --build frontend` çalıştığında bu yama
   siliniyor, `/player/index.html` kayboluyor, Nginx bunu React route'u sanıp
   kullanıcıyı login sayfasına (`/index.html`) düşürüyordu.

2. **Nginx'te `/player/` için özel bir location bloğu yoktu.** `location /`
   bloğundaki `try_files $uri $uri/ /index.html` her eksik dosyayı sessizce
   login'e yönlendiriyordu — hata vermeden.

3. **Nginx, `backend` Docker DNS adını bazen çözemiyordu**, bu yüzden IP adresi
   (`172.18.0.4` gibi) ile elle yamanıyordu — ama container yeniden başlayınca IP
   değişiyor ve yama tekrar bozuluyordu.

## Yapılan Kalıcı Düzeltmeler

### 1. `frontend/public/player/` (Vite native çözüm)
Player dosyaları artık Vite'in `public/` klasöründe. Vite, bu klasördeki HER ŞEYİ
otomatik olarak `dist/` köküne kopyalar. Yani her `npm run build` çalıştığında
`/player/index.html` garantili olarak mevcut olacak — elle `docker cp` gerekmez.

### 2. `frontend/nginx.conf`
- `location /player/ { try_files $uri $uri/ =404; }` eklendi — eksik dosya
  durumunda artık SESSIZCE login'e düşmüyor, açıkça 404 dönüyor (teşhis edilebilir).
- `resolver 127.0.0.11 valid=10s;` eklendi — Docker'ın dahili DNS sunucusu, bu sayede
  Nginx, `backend` adını restart sonrası IP değişse bile her 10 saniyede yeniden
  çözüyor. Artık elle IP yamasına gerek yok.

### 3. `PlayerActivity.java` (Xibo'dan ilham alınan teşhis ekranı)
- Her sayfa yüklemesinde gerçek URL ekranda gösteriliyor (`debugText`).
- WebView beklenmedik şekilde `/login`, `/dashboard` veya `/` adresine düşerse,
  bunu SESSİZCE değil AÇIKÇA ekranda gösteriyor: "Sunucu /player yerine X
  adresine yönlendirdi."
- İnternet yoksa bunu da ayrı bir mesajla belirtiyor.
- Bu sayede bir sorun olduğunda, ekrana bakan kişi (siz veya müşteri) Claude'a
  ekran görüntüsü gönderdiğinde sorunun TAM olarak nerede olduğu anlaşılabiliyor.

## Yükleme Talimatları

### A) Sunucu Güncellemesi (önce bu yapılmalı)
```bash
# VM'de
cd ~/fmekran-full
# Bu paketteki frontend/ klasörünü ~/fmekran-full/frontend/ üzerine kopyalayın
docker compose up -d --build frontend
```
Bu sefer Nginx'i elle IP ile yamamaya gerek YOK — resolver otomatik halleder.
Sadece build'in bitmesini bekleyin (45-60 saniye).

### B) Android APK (GitHub Actions ile)
1. Eski `fmekran-player-android` reposundaki TÜM dosyaları silin
2. Bu paketteki dosyaları yükleyin (`.github/workflows/build.yml` dahil — gizli
   klasör olduğu için ayrıca "Create new file" ile eklemeniz gerekebilir)
3. Actions sekmesinden build'i izleyin
4. Artifacts'tan APK'yı indirin

## Test Etme

APK'yı kurup açtığınızda artık şunları göreceksiniz:
- "fmekran" logosu
- Durum metni ("Bağlanıyor...", "Bağlandı", veya hata)
- Küçük gri teşhis metni — tam olarak hangi URL'nin yüklendiğini gösterir

Eğer hâlâ login ekranına düşerse, ekran görüntüsündeki gri teşhis metni size
TAM olarak hangi URL'nin sorunlu olduğunu söyleyecek.
