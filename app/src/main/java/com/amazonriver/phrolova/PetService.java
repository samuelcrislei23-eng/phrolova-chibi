package com.amazonriver.phrolova;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.ImageDecoder;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/** A Phrolova pequena: anda, pula no teclado, senta na barra, se pendura no botão de chamada do WhatsApp. */
public class PetService extends Service {
    static volatile boolean running;
    static volatile String mood = "neutral";

    private enum M { IDLE, WALK, HOP, DRAG, FALL, HANG }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final ArrayList<float[]> path = new ArrayList<>();
    private WindowManager wm;
    private WindowManager.LayoutParams lp, blp;
    private ImageView pet;
    private TextView bubble;
    private boolean bubbleOn;
    private SoundPool sp;
    private final int[] snd = new int[3];
    private float d, x, y, vx, vy, hopSx, hopSy, hopTx, hopTy, hopH, walkTx, phase, amp = 1f;
    private int sw, sh, PW, PH, face = 1, bounces, ledge; // ledge: 0 chão, 1 teclado, 2 campo de mensagem, 3 botão de chamada
    private M m = M.FALL;
    private float[] cur;
    private long last, lastScan, hopT0, hopMs, idleUntil, pokeT0, spinT0, landT0, shakeUntil, sleepUntil, lastTap;
    // toque
    private float dX, dY, sX, sY, lRX, lRY;
    private long dT, lT;
    private boolean moved, longFired;

    private final Runnable tick = new Runnable() { public void run() { step(); h.postDelayed(this, 16); } };
    private final Runnable hideB = new Runnable() { public void run() { hideBubble(); } };
    private final Runnable longR = new Runnable() { public void run() { longFired = true; vib(30); timeReaction(); } };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (i != null && "STOP".equals(i.getAction())) stopSelf();
        return START_NOT_STICKY;
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        d = getResources().getDisplayMetrics().density;
        startForeground(1, notif());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        measure();
        PW = (int) (84 * d); PH = (int) (95 * d);
        pet = new ImageView(this);
        pet.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try {
            Drawable dr = ImageDecoder.decodeDrawable(ImageDecoder.createSource(getAssets(), "pet.webp"));
            pet.setImageDrawable(dr);
            if (dr instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable a = (AnimatedImageDrawable) dr;
                a.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                a.start();
            }
        } catch (Exception e) { }
        lp = new WindowManager.LayoutParams(PW, PH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        x = sw / 2f - PW / 2f; y = -PH;
        lp.x = (int) x; lp.y = (int) y;
        wm.addView(pet, lp);
        pet.setOnTouchListener(this::touch);

        bubble = new TextView(this);
        bubble.setTextColor(Color.BLACK); bubble.setTextSize(13); bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER); bubble.setMaxWidth((int) (220 * d));
        bubble.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE); g.setStroke(Math.max(1, (int) d), Color.BLACK); g.setCornerRadius(2 * d);
        bubble.setBackground(g);
        blp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        blp.gravity = Gravity.TOP | Gravity.START;

        sp = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        snd[0] = sp.load(this, R.raw.nono_a, 1);
        snd[1] = sp.load(this, R.raw.nono_b, 1);
        snd[2] = sp.load(this, R.raw.nono_c, 1);

        last = SystemClock.uptimeMillis();
        h.post(tick);
        h.postDelayed(() -> say(nono(), 2500), 1200);
    }

    @Override public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        try { wm.removeView(pet); } catch (Exception e) { }
        hideBubble();
        if (sp != null) sp.release();
        super.onDestroy();
    }

    private Notification notif() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("pet", "Phrolova", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getService(this, 0, new Intent(this, PetService.class).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "pet").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Phrolova está na tela").setContentText("Toque aqui para guardá-la")
            .setContentIntent(pi).setOngoing(true).build();
    }

    private void measure() {
        Rect b;
        if (android.os.Build.VERSION.SDK_INT >= 30) b = wm.getMaximumWindowMetrics().getBounds();
        else { DisplayMetrics r = new DisplayMetrics(); wm.getDefaultDisplay().getRealMetrics(r); b = new Rect(0, 0, r.widthPixels, r.heightPixels); }
        sw = b.width(); sh = b.height();
    }

    private boolean kbOn() { return PetAccess.kbTop > 0 && PetAccess.kbBottom > PetAccess.kbTop; }

    private float groundY() { return kbOn() ? PetAccess.kbTop - PH + 8 * d : sh - 48 * d - PH; }

    private boolean ledgeOk() {
        switch (ledge) {
            case 1: return kbOn();
            case 2: return PetAccess.inWhatsApp && PetAccess.fieldTop > 0;
            case 3: return PetAccess.inWhatsApp && PetAccess.callY > 0;
            default: return true;
        }
    }

    private String nono() {
        return "angry".equals(mood) ? "！NONO！！" : "happy".equals(mood) ? "！nono～♪！" : "！nono！";
    }

    private void voice(float mul) {
        float r = ("happy".equals(mood) ? 1.14f : "angry".equals(mood) ? .86f : 1f) * mul;
        sp.play(snd[rnd.nextInt(3)], 1f, 1f, 1, 0, r);
    }

    private void say(String s, int ms) {
        if (!bubbleOn) { try { wm.addView(bubble, blp); bubbleOn = true; } catch (Exception e) { return; } }
        bubble.setText(s);
        h.removeCallbacks(hideB); h.postDelayed(hideB, ms);
        voice(SystemClock.uptimeMillis() < sleepUntil ? .82f : 1f);
    }

    private void hideBubble() {
        if (bubbleOn) { try { wm.removeView(bubble); } catch (Exception e) { } bubbleOn = false; }
    }

    private void vib(int ms) {
        try { ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)); } catch (Exception e) { }
    }

    private void startHop(float tx, float ty) {
        ledge = 0; hopSx = x; hopSy = y; hopTx = tx; hopTy = ty; hopT0 = SystemClock.uptimeMillis();
        float dist = (float) Math.hypot(tx - x, ty - y);
        hopMs = Math.min(950, 380 + (long) (dist / d * 1.1f));
        hopH = 34 * d + dist * .12f;
        if (Math.abs(tx - x) > 6 * d) face = tx < x ? -1 : 1;
        m = M.HOP;
    }

    private void nextPath() { cur = path.remove(0); startHop(cur[0], cur[1]); }

    private void onHopEnd(long now) {
        x = hopTx; y = hopTy; landT0 = now;
        if (cur != null) {
            ledge = (int) cur[4];
            if (cur[2] == 1) { m = M.HANG; amp = 2f; idleUntil = now + (long) cur[3]; }
            else { m = M.IDLE; idleUntil = now + (long) cur[3]; }
            cur = null;
        } else { m = M.IDLE; idleUntil = now + 800 + rnd.nextInt(1500); }
    }

    private float clampX(float v) { return Math.max(0, Math.min(sw - PW, v)); }

    private void chooseNext(long now) {
        if (ledge != 0) { path.add(new float[]{clampX(x + (rnd.nextFloat() - .5f) * 200 * d), groundY(), 0, 1200, 0}); nextPath(); return; }
        float r = rnd.nextFloat();
        boolean wa = PetAccess.inWhatsApp;
        if (wa && PetAccess.callY > 0 && r < .35f) {
            path.add(new float[]{clampX(PetAccess.callX - PW / 2f), PetAccess.callY - 8 * d, 1, 6000 + rnd.nextInt(4000), 3});
            nextPath();
        } else if (wa && PetAccess.fieldTop > 0 && r < .6f) {
            float fx = PetAccess.fieldL + rnd.nextFloat() * Math.max(1, PetAccess.fieldR - PetAccess.fieldL - PW);
            path.add(new float[]{clampX(fx), PetAccess.fieldTop - PH + 10 * d, 0, 5000, 2});
            nextPath();
        } else if (kbOn() && r < .8f) {
            int kh = PetAccess.kbBottom - PetAccess.kbTop;
            for (int i = 0; i < 3; i++) {
                float kx = PetAccess.kbLeft + rnd.nextFloat() * Math.max(1, PetAccess.kbRight - PetAccess.kbLeft - PW);
                float ky = PetAccess.kbTop + kh * (.2f + .5f * rnd.nextFloat());
                path.add(new float[]{kx, ky - PH + 8 * d, 0, 450, 1});
            }
            float cx = (PetAccess.kbLeft + PetAccess.kbRight) / 2f - PW / 2f;
            path.add(new float[]{cx, PetAccess.kbBottom - kh * .14f - PH + 8 * d, 0, 5000, 1}); // barra de espaço
            nextPath();
        } else if (r < ("happy".equals(mood) ? .4f : .2f)) {
            hopTx = x; hopTy = y; startHop(x, y); hopH = 30 * d;
        } else if (r < .3f) {
            say(nono(), 1800); idleUntil = now + 2000;
        } else { walkTx = clampX(rnd.nextFloat() * (sw - PW)); m = M.WALK; }
    }

    private void step() {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min((now - last) / 1000f, .05f); last = now; phase += dt;
        if (now - lastScan > 600) {
            lastScan = now; measure();
            if (PetAccess.inst != null) { try { PetAccess.inst.scan(); } catch (Exception e) { } }
        }
        float gy = groundY();
        if (ledge != 0 && m != M.DRAG && !ledgeOk()) { ledge = 0; path.clear(); cur = null; m = M.FALL; vy = 0; bounces = 0; }
        float rot = 0, sxs = 1, sys = 1, ty = 0, txo = 0;
        switch (m) {
            case DRAG: rot = 8 * (float) Math.sin(phase * 14); break;
            case FALL:
                vy += 1500 * d * dt; x += vx * dt; y += vy * dt; vx *= .995f;
                if (x < 0) { x = 0; vx = Math.abs(vx) * .5f; }
                if (x > sw - PW) { x = sw - PW; vx = -Math.abs(vx) * .5f; }
                if (y >= gy && vy > 0) {
                    y = gy;
                    if (vy > 600 * d && bounces < 1) { vy = -vy * .3f; bounces++; }
                    else { vy = 0; vx = 0; landT0 = now; m = M.IDLE; idleUntil = now + 1200 + rnd.nextInt(2000); }
                }
                break;
            case HOP: {
                float t = (now - hopT0) / (float) hopMs;
                if (t >= 1) onHopEnd(now);
                else { x = hopSx + (hopTx - hopSx) * t; y = hopSy + (hopTy - hopSy) * t - hopH * 4 * t * (1 - t); sxs = .93f; sys = 1.08f; }
                break;
            }
            case WALK: {
                float dir = Math.signum(walkTx - x);
                x += dir * 70 * d * dt; face = dir < 0 ? -1 : 1;
                ty = -Math.abs((float) Math.sin(phase * 10)) * 5 * d; rot = 5 * (float) Math.sin(phase * 10);
                y = gy;
                if (Math.abs(walkTx - x) < 4 * d) { m = M.IDLE; idleUntil = now + 1500 + rnd.nextInt(3000); }
                break;
            }
            case HANG:
                amp = Math.max(1f, amp - dt * .8f); rot = 12 * amp * (float) Math.sin(phase * 2.2);
                if (now > idleUntil) { ledge = 0; m = M.FALL; vy = 0; bounces = 0; }
                break;
            default: // IDLE
                sys = 1 + .015f * (float) Math.sin(phase * 2.2);
                if (ledge != 0) sys *= .93f;
                else if (y > gy + 6 * d) startHop(x, gy);
                else if (y < gy - 6 * d) { m = M.FALL; vy = 0; bounces = 0; }
                else y = gy;
                if (now < sleepUntil) { rot = 6 + 3 * (float) Math.sin(phase * 1.2); sys *= .93f; }
                else if (now < shakeUntil) txo = (float) Math.sin(phase * 60) * 3 * d;
                else if (now > idleUntil && m == M.IDLE) { if (!path.isEmpty()) nextPath(); else chooseNext(now); }
        }
        float pk = (now - pokeT0) / 250f;
        if (pk < 1) { float s = (float) Math.sin(pk * Math.PI); sxs *= 1 + .15f * s; sys *= 1 - .12f * s; }
        float sp2 = (now - spinT0) / 700f;
        if (sp2 < 1) { rot += 360 * sp2; ty -= (float) Math.sin(sp2 * Math.PI) * 18 * d; }
        float lt = (now - landT0) / 220f;
        if (lt < 1) { sxs *= 1 + .18f * (1 - lt); sys *= 1 - .18f * (1 - lt); }

        pet.setPivotX(PW / 2f); pet.setPivotY(m == M.HANG ? 0 : PH);
        pet.setScaleX(face * sxs); pet.setScaleY(sys); pet.setRotation(rot);
        pet.setTranslationX(txo); pet.setTranslationY(ty);
        int ix = Math.round(x), iy = Math.round(y);
        if (ix != lp.x || iy != lp.y) { lp.x = ix; lp.y = iy; try { wm.updateViewLayout(pet, lp); } catch (Exception e) { } }
        if (bubbleOn) {
            int bw = bubble.getWidth(), bh = bubble.getHeight();
            int bx = (int) Math.max(4 * d, Math.min(sw - bw - 4 * d, x + PW / 2f - bw / 2f));
            int by = (int) (y - bh - 2 * d);
            if (by < 0) by = (int) (y + PH + 2 * d);
            if (bx != blp.x || by != blp.y) { blp.x = bx; blp.y = by; try { wm.updateViewLayout(bubble, blp); } catch (Exception e) { } }
        }
    }

    private boolean touch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dX = lRX = e.getRawX(); dY = lRY = e.getRawY(); sX = x; sY = y; dT = lT = SystemClock.uptimeMillis();
                moved = false; longFired = false; vx = 0; vy = 0;
                h.postDelayed(longR, 550);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float mx = e.getRawX() - dX, my = e.getRawY() - dY;
                if (!moved && Math.hypot(mx, my) > 8 * d) {
                    moved = true; h.removeCallbacks(longR); m = M.DRAG; ledge = 0; path.clear(); cur = null; vib(10);
                }
                if (moved) {
                    x = sX + mx; y = sY + my;
                    long n = SystemClock.uptimeMillis(); float dtv = (n - lT) / 1000f;
                    if (dtv > .001f) {
                        vx = .6f * vx + .4f * ((e.getRawX() - lRX) / dtv);
                        vy = .6f * vy + .4f * ((e.getRawY() - lRY) / dtv);
                        lRX = e.getRawX(); lRY = e.getRawY(); lT = n;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longR);
                if (moved) {
                    float lim = 2500 * d / 2.75f;
                    vx = Math.max(-lim, Math.min(lim, vx)); vy = Math.max(-lim, Math.min(lim, vy));
                    x = clampX(x); bounces = 0;
                    if (y > groundY() + 10 * d) { vx = 0; vy = 0; startHop(x, groundY()); }
                    else m = M.FALL;
                } else if (!longFired) tap();
                return true;
        }
        return true;
    }

    private void tap() {
        long now = SystemClock.uptimeMillis();
        if (now - lastTap < 350) { spinT0 = now; say(nono(), 1800); }
        else { pokeT0 = now; vib(12); say(nono(), 1800); }
        if (m == M.HANG) amp = 2.4f;
        lastTap = now;
    }

    private void timeReaction() {
        Calendar c = Calendar.getInstance();
        int hr = c.get(Calendar.HOUR_OF_DAY);
        final String hm = String.format(Locale.getDefault(), "%02d:%02d", hr, c.get(Calendar.MINUTE));
        long now = SystemClock.uptimeMillis();
        if (m == M.HANG) { say("São " + hm + "\n" + nono(), 3500); return; }
        if (hr >= 6 && hr < 12) {
            say("São " + hm + "! Bom dia!\n" + nono(), 3500);
            for (int i = 0; i < 3; i++) path.add(new float[]{x, y, 0, 120, ledge});
            m = M.IDLE; idleUntil = 0;
        } else if (hr >= 12 && hr < 18) {
            say("*grrrr* São " + hm + "\n" + nono(), 3500);
            shakeUntil = now + 2500; m = M.IDLE; idleUntil = shakeUntil;
        } else {
            sleepUntil = now + 6500; m = M.IDLE; idleUntil = sleepUntil;
            h.postDelayed(() -> say("Zzz… são " + hm + "…\n" + nono(), 4500), 700);
        }
    }
}
