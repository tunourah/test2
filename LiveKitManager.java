package com.videovoicecall.client.utils;

import java.awt.BorderLayout;
import java.awt.Component;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefInitializationException;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import me.friwi.jcefmaven.UnsupportedPlatformException;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

/**
 * LiveKit Manager (Java ↔ Vue bridge using JCEF).
 *
 * - Loads Vue UI from Vite dev server
 * - Java → Vue events (startCall, toggleMute, toggleCamera, endCall)
 * - Safe for Vite HMR & late JS loading
 *
 * Compatible with jcefmaven 141+
 */
public class LiveKitManager {

    private static final String CALL_UI_URL = "http://localhost:5080/";
    private static LiveKitManager instance;

    private CefApp cefApp;
    private CefClient client;
    private CefBrowser browser;
    private Component browserUI;

    private boolean initialized = false;
    private boolean pageLoaded = false;

    private final List<String> jsQueue = new ArrayList<>();

    private LiveKitManager() {
        initCef();
    }

    public static synchronized LiveKitManager getInstance() {
        if (instance == null) {
            instance = new LiveKitManager();
        }
        return instance;
    }

    // ============================================================
    //                  INIT JCEF (WebRTC Enabled)
    // ============================================================
    private void initCef() {
        if (initialized) return;

        try {
            CefAppBuilder builder = new CefAppBuilder();
            CefSettings settings = builder.getCefSettings();

            settings.windowless_rendering_enabled = false;
            settings.remote_debugging_port = 9223;

            builder.addJcefArgs(
                    "--use-fake-ui-for-media-stream=no",
                    "--enable-media-stream",
                    "--disable-web-security",
                    "--allow-file-access-from-files",
                    "--allow-running-insecure-content",
                    "--enable-native-gpu-memory-buffers",
                    "--ignore-gpu-blocklist",
                    "--enable-gpu",
                    "--no-sandbox",
                    "--autoplay-policy=no-user-gesture-required"
            );


            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                @Override
                public void stateHasChanged(CefApp.CefAppState state) {
                    System.out.println("[JCEF] State changed: " + state);
                }
            });

            cefApp = builder.build();
            client = cefApp.createClient();

        } catch (IOException | UnsupportedPlatformException |
                 InterruptedException | CefInitializationException e) {
            throw new RuntimeException("Failed to initialize JCEF", e);
        }

        initialized = true;
        System.out.println("✅ JCEF initialized with WebRTC support");
    }

    // ============================================================
    //                  CREATE BROWSER ONCE
    // ============================================================
    private void ensureBrowserCreated() {
        if (browser != null) return;

        browser = client.createBrowser(CALL_UI_URL, false, false);
        browserUI = browser.getUIComponent();

        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser b, CefFrame frame, int statusCode) {
                pageLoaded = true;
                System.out.println("✅ Vue UI loaded");

                injectJavaBridge();

                synchronized (jsQueue) {
                    for (String js : jsQueue) {
                        browser.executeJavaScript(js, browser.getURL(), 0);
                    }
                    jsQueue.clear();
                }
            }
        });
    }

    // ============================================================
    //             INJECT JAVA ↔ VUE BRIDGE
    // ============================================================
    private void injectJavaBridge() {
        browser.executeJavaScript(
                """
                (function () {
                  if (!window.javaBridge) window.javaBridge = {};
                  if (!window.javaBridge.listeners) window.javaBridge.listeners = {};
                  if (!window.javaBridge.pendingEvents) window.javaBridge.pendingEvents = [];
    
                  window.javaBridge.sendToVue = function (event, payload) {
                    const ev = { event, payload };
                    window.javaBridge.pendingEvents.push(ev);
    
                    const handlers = window.javaBridge.listeners[event] || [];
                    handlers.forEach(h => {
                      try { h(payload); } catch (e) { console.error(e); }
                    });
                  };
    
                  window.javaBridge.on = function (event, handler) {
                    if (!window.javaBridge.listeners[event])
                      window.javaBridge.listeners[event] = [];
    
                    window.javaBridge.listeners[event].push(handler);
    
                    window.javaBridge.pendingEvents
                      .filter(e => e.event === event)
                      .forEach(e => handler(e.payload));
                  };
    
                  if (!window.javaBridge.sendToJava) {
                    window.javaBridge.sendToJava = function (event, payload) {
                      console.log('[Vue → Java]', event, payload);
                    };
                  }
                })();
                """,
                browser.getURL(),
                0
        );
    }

    // ============================================================
    //              ATTACH TO SWING PANEL
    // ============================================================
    public void attachVideoRenderer(JPanel panel) {
        ensureBrowserCreated();

        SwingUtilities.invokeLater(() -> {
            panel.removeAll();
            panel.setLayout(new BorderLayout());
            panel.add(browserUI, BorderLayout.CENTER);
            panel.revalidate();
            panel.repaint();
        });
    }

    // ============================================================
    //                  JAVA → VUE
    // ============================================================
    public void startCall(String wsUrl, String token, String roomName, boolean video) {
        runJs(String.format(
                """
                window.javaBridge?.sendToVue('startCall', {
                  wsUrl: '%s',
                  token: '%s',
                  roomName: '%s',
                  video: %s
                });
                """,
                escape(wsUrl), escape(token), escape(roomName), video
        ));
    }

    public void setAudioMuted(boolean muted) {
        runJs("window.javaBridge?.sendToVue('toggleMute', { mute: " + muted + " });");
    }

    public void setVideoEnabled(boolean enabled) {
        runJs("window.javaBridge?.sendToVue('toggleCamera', { off: " + (!enabled) + " });");
    }

    public void disconnect() {
        runJs("window.javaBridge?.sendToVue('endCall', {});");
    }

    // ============================================================
    //                  JS EXECUTION
    // ============================================================
    private void runJs(String js) {
        ensureBrowserCreated();

        synchronized (jsQueue) {
            if (pageLoaded) {
                browser.executeJavaScript(js, browser.getURL(), 0);
            } else {
                jsQueue.add(js);
            }
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'");
    }

    public CefBrowser getBrowser() {
        return browser;
    }
}