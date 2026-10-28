package com.amazonriver.phrolova;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/** Só lê POSIÇÕES: janela do teclado e botões do WhatsApp. Nunca lê texto de mensagens. */
public class PetAccess extends AccessibilityService {
    static PetAccess inst;
    static volatile int kbTop = -1, kbBottom = -1, kbLeft, kbRight;
    static volatile int callX = -1, callY = -1, fieldTop = -1, fieldL, fieldR;
    static volatile boolean inWhatsApp;
    private static final String[] CALL = {"Chamada de voz", "Voice call", "Llamada de voz", "Chamada de vídeo", "Video call"};

    @Override protected void onServiceConnected() { inst = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(Intent i) { inst = null; reset(); return super.onUnbind(i); }

    private static void reset() { kbTop = -1; kbBottom = -1; callX = -1; callY = -1; fieldTop = -1; inWhatsApp = false; }

    void scan() {
        int kt = -1, kb = -1, kl = 0, kr = 0;
        List<AccessibilityWindowInfo> ws = getWindows();
        if (ws != null) for (AccessibilityWindowInfo w : ws) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                Rect r = new Rect(); w.getBoundsInScreen(r);
                if (r.height() > 150) { kt = r.top; kb = r.bottom; kl = r.left; kr = r.right; }
            }
        }
        kbTop = kt; kbBottom = kb; kbLeft = kl; kbRight = kr;
        boolean wa = false; int cx = -1, cy = -1, ft = -1, fl = 0, fr = 0;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null && root.getPackageName().toString().startsWith("com.whatsapp")) {
            wa = true;
            int limit = (int) (getResources().getDisplayMetrics().heightPixels * 0.2f);
            for (String k : CALL) {
                List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(k);
                if (l != null) for (AccessibilityNodeInfo n : l) {
                    Rect r = new Rect(); n.getBoundsInScreen(r);
                    if (r.width() > 0 && r.top >= 0 && r.top < limit) { cx = r.centerX(); cy = r.bottom; break; }
                }
                if (cx > 0) break;
            }
            List<AccessibilityNodeInfo> f = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
            if (f != null && !f.isEmpty()) {
                Rect r = new Rect(); f.get(0).getBoundsInScreen(r);
                ft = r.top; fl = r.left; fr = r.right;
            }
        }
        inWhatsApp = wa; callX = cx; callY = cy; fieldTop = ft; fieldL = fl; fieldR = fr;
    }
}
