package com.zerotrust.steamingapp.adblock;

import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Filtro di rete leggero per bloccare tracker, popunder e script pubblicitari
 * prima che vengano scaricati dalla WebView.
 */
public class AdBlocker {

    private static final Set<String> BLOCKED_DOMAINS = new HashSet<>(Arrays.asList(
            "onclickmega.com",
            "adsterra.com",
            "popads.net",
            "propellerads.com",
            "exoclick.com",
            "syndication.exoclick.com",
            "trafficjunky.com",
            "hilltopads.net",
            "monetag.com",
            "adnxs.com",
            "doubleclick.net",
            "google-analytics.com",
            "googlesyndication.com",
            "juicyads.com",
            "etahub.com",
            "gemini-stream.com",
            "trafficfactory.biz",
            "highcpmgate.com",
            "whomeetso.com",
            "cpmrevenuegate.com",
            "alwingulla.com",
            "yandex.ru",
            "histats.com"
    ));

    private static final String[] BLOCKED_PATTERNS = new String[] {
            "/banner",
            "/popunder",
            "ads.js",
            "ad_tag",
            "analytics.js",
            "advertisement",
            "adservice"
    };

    /**
     * Verifica se un URL o un host appartiene a una rete pubblicitaria o tracker.
     */
    public static boolean isAd(String url, String host) {
        if (url == null) return false;
        String lowerUrl = url.toLowerCase();
        String lowerHost = host != null ? host.toLowerCase() : "";

        // Non bloccare mai i domini legittimi del servizio
        if (lowerHost.contains("vixcloud") ||
            lowerHost.contains("streamingunity") ||
            lowerHost.contains("streamingcommunity") ||
            lowerHost.contains("cloudflare") ||
            lowerHost.contains("themoviedb") ||
            lowerHost.contains("tmdb") ||
            lowerHost.contains("googleapis") ||
            lowerHost.contains("gstatic")) {
            return false;
        }

        // Controllo corrispondenza dominio esatto o sottodominio
        for (String blocked : BLOCKED_DOMAINS) {
            if (lowerHost.equals(blocked) || lowerHost.endsWith("." + blocked)) {
                return true;
            }
        }

        // Controllo parole chiave sospette nel percorso
        for (String pattern : BLOCKED_PATTERNS) {
            if (lowerUrl.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Restituisce una risorsa vuota con status 200 per silenziare la richiesta senza generare errori di rete.
     */
    public static WebResourceResponse createEmptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }
}
