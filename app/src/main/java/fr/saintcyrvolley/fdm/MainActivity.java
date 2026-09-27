package fr.saintcyrvolley.fdm;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.View;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Appli FDM Saint-Cyr Volley : l'interface est la page web embarquée dans assets/,
 * servie hors ligne par WebViewAssetLoader. Ce fichier ajoute ce qu'une page web
 * ne sait pas faire seule dans une WebView : appareil photo / choix de fichier,
 * enregistrement dans Téléchargements, partage par mail avec pièce jointe.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/index.html";
    private static final int REQ_FILE = 42;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private File cameraFile;
    private TextRecognizer recognizer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    toast("Aucune appli pour ouvrir ce lien");
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return openChooser(callback, params);
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START);
    }

    /* ---------- appareil photo / fichiers ---------- */

    private boolean openChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        cameraUri = null;
        cameraFile = null;

        Intent camera = null;
        try {
            File dir = new File(getCacheDir(), "shared");
            dir.mkdirs();
            File photo = File.createTempFile("licence_", ".jpg", dir);
            cameraFile = photo;
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", photo);
            camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            camera.setClipData(ClipData.newRawUri("photo", cameraUri));
        } catch (Exception e) {
            camera = null;
            cameraFile = null;
        }

        boolean imagesOnly = true;
        for (String t : params.getAcceptTypes()) {
            if (t != null && !t.isEmpty() && !t.startsWith("image/")) imagesOnly = false;
        }

        try {
            if (params.isCaptureEnabled() && camera != null) {
                startActivityForResult(camera, REQ_FILE);
                return true;
            }
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            if (imagesOnly) {
                pick.setType("image/*");
            } else {
                pick.setType("*/*");
                pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
            }
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                    params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
            Intent chooser = Intent.createChooser(pick, "Choisir la licence");
            if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
            startActivityForResult(chooser, REQ_FILE);
            return true;
        } catch (ActivityNotFoundException e) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
            toast("Impossible d'ouvrir l'appareil photo");
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            List<Uri> uris = new ArrayList<>();
            if (data != null && data.getData() != null) {
                uris.add(data.getData());
            } else if (data != null && data.getClipData() != null) {
                ClipData cd = data.getClipData();
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null && !u.equals(cameraUri)) uris.add(u);
                }
            }
            if (uris.isEmpty() && cameraFile != null && cameraFile.length() > 0) {
                uris.add(cameraUri); // photo prise avec l'appareil
            }
            if (!uris.isEmpty()) result = uris.toArray(new Uri[0]);
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    /* ---------- pont JavaScript : enregistrer / partager ---------- */

    private class Bridge {
        @JavascriptInterface
        public boolean hasNativeOcr() {
            return true;
        }

        /** Lecture de texte ML Kit (sur l'appareil, hors ligne). Réponse via window.__ocrDone(id, {text|error}). */
        @JavascriptInterface
        public void ocr(final String id, String b64, int rotation) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) { sendOcr(id, null, "image"); return; }
                InputImage img = InputImage.fromBitmap(bmp, rotation);
                if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                recognizer.process(img)
                        .addOnSuccessListener(t -> sendOcr(id, linesInReadingOrder(t), null))
                        .addOnFailureListener(e -> sendOcr(id, null, String.valueOf(e.getMessage())));
            } catch (Throwable e) {
                sendOcr(id, null, String.valueOf(e.getMessage()));
            }
        }

        @JavascriptInterface
        public boolean isDarkMode() {
            return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        }

        @JavascriptInterface
        public void setBars(final boolean dark) {
            runOnUiThread(() -> applyBars(dark));
        }

        @JavascriptInterface
        public String saveFile(String name, String mime, String b64) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FDM");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "error";
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(bytes);
                    }
                    return "Téléchargements/FDM/" + name;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, name);
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(bytes);
                    }
                    return f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "error";
            }
        }

        @JavascriptInterface
        public String shareFile(String name, String mime, String b64, String to, String subject, String body) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "shared");
                dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                final Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_EMAIL, new String[]{to});
                send.putExtra(Intent.EXTRA_SUBJECT, subject);
                send.putExtra(Intent.EXTRA_TEXT, body);
                send.setClipData(ClipData.newRawUri(name, uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "Envoyer la feuille de match")));
                return "ok";
            } catch (Exception e) {
                return "error";
            }
        }
    }

    /** Remet les lignes lues dans l'ordre de lecture (haut → bas, gauche → droite). */
    private static String linesInReadingOrder(Text t) {
        List<Text.Line> lines = new ArrayList<>();
        for (Text.TextBlock b : t.getTextBlocks()) lines.addAll(b.getLines());
        List<float[]> boxes = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Text.Line l : lines) {
            android.graphics.Rect r = l.getBoundingBox();
            if (r == null) { boxes.add(new float[]{0, 0, 1}); }
            else boxes.add(new float[]{r.centerY(), r.left, Math.max(1, r.height())});
            texts.add(l.getText());
        }
        Integer[] idx = new Integer[lines.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(boxes.get(a)[0], boxes.get(b)[0]));
        StringBuilder out = new StringBuilder();
        List<Integer> row = new ArrayList<>();
        float rowY = -1e9f, rowH = 1;
        for (int i : idx) {
            float[] bx = boxes.get(i);
            if (!row.isEmpty() && Math.abs(bx[0] - rowY) > Math.min(bx[2], rowH) * 0.6f) {
                flushRow(row, boxes, texts, out);
                row.clear();
            }
            if (row.isEmpty()) { rowY = bx[0]; rowH = bx[2]; }
            row.add(i);
        }
        flushRow(row, boxes, texts, out);
        return out.toString();
    }

    private static void flushRow(List<Integer> row, List<float[]> boxes, List<String> texts, StringBuilder out) {
        if (row.isEmpty()) return;
        Collections.sort(row, (a, b) -> Float.compare(boxes.get(a)[1], boxes.get(b)[1]));
        for (int k = 0; k < row.size(); k++) {
            if (k > 0) out.append(' ');
            out.append(texts.get(row.get(k)));
        }
        out.append('\n');
    }

    private void sendOcr(String id, String text, String error) {
        try {
            JSONObject o = new JSONObject();
            if (text != null) o.put("text", text); else o.put("error", error == null ? "ocr" : error);
            final String js = "window.__ocrDone(" + JSONObject.quote(id) + "," + o.toString() + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        } catch (Exception ignored) { }
    }

    private void applyBars(boolean dark) {
        int bar = dark ? Color.parseColor("#0D1122") : Color.parseColor("#1E2B7A");
        int nav = dark ? Color.parseColor("#161C34") : Color.WHITE;
        getWindow().setStatusBarColor(bar);
        getWindow().setNavigationBarColor(nav);
        web.setBackgroundColor(dark ? Color.parseColor("#0D1122") : Color.parseColor("#EEF1F8"));
        View d = getWindow().getDecorView();
        int f = d.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            if (dark) f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        d.setSystemUiVisibility(f);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // thème « Auto » : prévenir la page quand le téléphone passe en clair/sombre
        if (web != null) web.evaluateJavascript("window.applyTheme && applyTheme()", null);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        // Retour : fermer une fenêtre ouverte dans la page si besoin, sinon quitter
        web.evaluateJavascript("window.fdmBack ? window.fdmBack() : false", value -> {
            if (!"true".equals(value)) {
                if (web.canGoBack()) web.goBack();
                else MainActivity.super.onBackPressed();
            }
        });
    }
}
