---
name: streaming-app-modernization
description: >-
  Guida architetturale, nozioni tecniche e roadmap per la modernizzazione
  dell'app StreamingCommunityAppv2 (Android/Java): bypass DNS sinkhole tramite
  Local Proxy DoH, adblocking di rete in WebView, estrazione stream VixCloud e
  integrazione con ExoPlayer nativo.
---

# StreamingCommunityApp Modernization Guide & Skill

Questa skill documenta le problematiche, l'architettura ingegneristica, le nozioni di reverse-engineering (ricavate anche dall'analisi di VibraVid) e la roadmap operativa passo-passo per modernizzare `StreamingCommunityAppv2`.

---

## 1. Nozioni Tecniche e Diagnosi dei Problemi

### 1.1 Il Blocco DNS Sinkhole (127.0.0.1)
* **Causa**: I provider italiani (TIM, Vodafone, Fastweb, WindTre) applicano direttive AGCOM / Piracy Shield avvelenando le risposte DNS sulla porta 53 UDP. Quando l'app risolve il dominio (es. `streamingunity.vip`), il DNS dell'operatore risponde con `127.0.0.1` (localhost).
* **Perché su cellulare a volte funziona**: Da Android 9+, il sistema operativo include la funzione *DNS Privato* (DoT su porta 853) che molti vendor impostano su "Automatico" o che gli utenti hanno già impostato su `one.one.one.one` o `dns.google`. Su desktop o Wi-Fi con DNS standard, fallisce.
* **Soluzione per Android**:
  - Implementare un **Micro-Proxy Locale HTTP** interno all'app (es. su `127.0.0.1:8443`), che usa `OkHttpClient` configurato con `DnsOverHttps` (Cloudflare `1.1.1.1`).
  - Agganciare la WebView a questo proxy tramite l'API ufficiale `androidx.webkit.ProxyController.getInstance().setProxyOverride(...)`.
  - In questo modo il 100% delle chiamate (GET, POST di login, fetch, iframe) viene risolto in DoH senza richiedere alcuna configurazione all'utente finale.

### 1.2 Perché i Bottoni non si premono (Ad-Blocking a metà)
* **Causa attuale**: In `MainActivity.java`, il filtro è applicato solo dentro `shouldOverrideUrlLoading`:
  ```java
  public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { ... }
  ```
  Questo blocca esclusivamente la navigazione dell'intera finestra. I file `.js` pubblicitari, i banner iframe e i tracker continuano a essere scaricati ed eseguiti. Questi script creano overlay invisibili (elementi `<div>` a tutto schermo con opacità 0) che intercettano i tap, impedendo di cliccare sui veri bottoni del sito.
* **Soluzione**: Bloccare le risorse alla sorgente usando `shouldInterceptRequest(WebView view, WebResourceRequest request)`:
  - Verificare se l'host della risorsa è presente in una blacklist di domini pubblicitari / tracker.
  - Se è un tracker/ad, restituire un `new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()))`. Lo script non viene mai scaricato e l'interfaccia rimane pulita e cliccabile.

### 1.3 Perché il Player Web lagga o si disconnette
* **Causa attuale**: La riproduzione video avviene dentro il motore WebKit/Chromium della WebView con `WebChromeClient.onShowCustomView()`. Chromium in WebView non ha la pipeline hardware ottimizzata di un player dedicato, satura la RAM sui video HLS ad alto bitrate e crasha o perde la connessione.
* **Soluzione (Architettura Ibrida)**:
  - Usare la WebView esclusivamente per navigazione catalogo, login e gestione preferiti.
  - Intercettare l'avvio del video o l'iframe del provider video (**VixCloud**).
  - Lanciare un'Activity nativa a schermo intero con **ExoPlayer (AndroidX Media3)**.
  - Vantaggi immediati: zero crash, buffering hardware nativo, skip 10s nativo, controllo sottotitoli e audio, supporto nativo telecomando Android TV (D-Pad).

### 1.4 Header Anti-Leeching (Errore 403 Forbidden)
* **Causa**: VixCloud e i CDN di streaming proteggono i flussi `.m3u8` controllando che la richiesta provenga dal sito autorizzato.
* **Soluzione**: Quando si passa l'URL ad ExoPlayer, configurare `DefaultHttpDataSource.Factory` impostando obbligatoriamente:
  - `Referer: https://vixcloud.co/` (o l'URL dell'embed)
  - `User-Agent: <stesso_user_agent_usato_nella_webview>`

### 1.5 Dominio Dinamico (Telegraph vs Futuro Gist)
* **Attualmente**: Mantenere `recuperaLinkDaTelegraph()`, ma irrobustire il parsing per gestire timeout e cadute di connessione con cache locale (SharedPreferences) dell'ultimo dominio funzionante.
* **Futuro**: Migrazione a un file JSON remoto su GitHub Gist o estrazione da `t.me/s/canale_ufficiale`.

---

## 2. Roadmap di Sviluppo Passo-Passo

### Fase 1: Aggiornamento Dipendenze Gradle
Aggiornare `app/build.gradle.kts` includendo:
1. `androidx.media3:media3-exoplayer:1.3.1` (e moduli `media3-ui`, `media3-datasource-okhttp` o `media3-datasource`).
2. `androidx.webkit:webkit:1.11.0` (per `ProxyController`).
3. `com.squareup.okhttp3:okhttp:4.12.0` e `com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0`.

### Fase 2: Implementazione Micro-Proxy DoH Locale
1. Creare una classe di servizio/thread `LocalDnsProxyServer` che si mette in ascolto su `127.0.0.1:8888`.
2. Il proxy instrada le richieste HTTP/HTTPS tramite OkHttp con DoH verso Cloudflare (`https://cloudflare-dns.com/dns-query`).
3. In `MainActivity`, prima di caricare la WebView:
   ```java
   ProxyConfig proxyConfig = new ProxyConfig.Builder()
       .addProxyRule("127.0.0.1:8888")
       .addDirect().build();
   ProxyController.getInstance().setProxyOverride(proxyConfig, ...);
   ```

### Fase 3: Ad-Blocker di Rete in WebView
1. In `WebViewClient.shouldInterceptRequest`:
   - Controllare `request.getUrl().getHost()`.
   - Se corrisponde a domini noti di ads/popunder (o regex EasyList leggera), bloccare restituendo risposta vuota.
2. In `WebChromeClient`: mantenere `onCreateWindow` con `return false` per stroncare i pop-up.

### Fase 4: Integrazione Player Nativo (ExoPlayer)
1. Creare `PlayerActivity` con layout a schermo intero contenente `androidx.media3.ui.PlayerView`.
2. Ricevere via Intent:
   - `video_url` (.m3u8 o mp4)
   - `referer`
   - `title`
3. Configurare `ExoPlayer` con `DefaultHttpDataSource.Factory` impostando gli header corretti.
4. Supporto D-Pad TV: mappare i tasti DPAD_CENTER (play/pause), DPAD_LEFT/RIGHT (seek -10s/+10s).

### Fase 5: Intercettazione Stream dalla WebView
1. Rilevare quando l'utente clicca su un film/episodio:
   - O intercettando la richiesta verso `vixcloud.co/embed/` o `.m3u8` in `shouldInterceptRequest`.
   - O tramite Javascript Interface iniettata (`@JavascriptInterface`).
2. Estrarre lo stream, mettere in pausa la WebView e avviare `PlayerActivity`.
3. Al ritorno (`onActivityResult` o `onResume`), passare il minutaggio alla WebView per sincronizzare il progresso.
