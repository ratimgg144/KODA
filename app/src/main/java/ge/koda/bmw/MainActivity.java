package ge.koda.bmw;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import ge.koda.bmw.net.AndroidEnetNetwork;
import ge.koda.bmw.net.EnetClient;
import ge.koda.bmw.protocol.EnetProtocol;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Clean Georgian BMW ENET prototype. Read-only: no coding or ECU writes. */
public final class MainActivity extends Activity {
    private static final int BG = 0xFF0F1012;
    private static final int PANEL = 0xFF1A1C20;
    private static final int LINE = 0xFF303238;
    private static final int WHITE = 0xFFF5F5F6;
    private static final int MUTED = 0xFF93969E;
    private static final int SOFT = 0xFFBFC2C9;
    private static final int GOOD = 0xFF92C8A7;
    private static final int OFF = 0xFF8E9199;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final StringBuilder debug = new StringBuilder();
    private boolean busy = false;
    private boolean verified = false;
    private boolean showAdvanced = false;
    private boolean wiredMode = true;
    private EnetProtocol.Vehicle vehicle;
    private EnetClient client;

    private TextView statusTitle, statusBody, vinValue, protocolValue, networkValue, logView;
    private TextView vehicleSubtitle, vehicleRowSubtitle, adapterModeValue;
    private LinearLayout vehiclePanel, advancedPanel, advancedRow;
    private View statusDot;
    private Button connectButton;
    private EditText ipInput;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildScreen();
        setStatus("არ არის დაკავშირებული", "შეაერთეთ ENET კაბელი და დააჭირეთ დაკავშირებას.", false);
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = vertical();
        root.setPadding(dp(24), dp(28), dp(24), dp(40));
        scroll.addView(root);
        setContentView(scroll);

        // Quiet brand header. No illustrations, version pills, marketing headlines, or mock car art.
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = label("KODA", 19, WHITE, true);
        brand.setLetterSpacing(-0.03f);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView headerTrailing = label("BMW  /  ENET", 11, MUTED, false);
        headerTrailing.setLetterSpacing(.04f);
        header.addView(headerTrailing);
        root.addView(header);

        gap(root, 48);
        TextView overline = label("სატესტო ავტომობილი", 12, MUTED, false);
        root.addView(overline);
        gap(root, 8);
        TextView carTitle = label("BMW 3 Series", 29, WHITE, true);
        carTitle.setLetterSpacing(-0.035f);
        root.addView(carTitle);
        gap(root, 5);
        vehicleSubtitle = label("G20  ·  2021", 14, SOFT, false);
        root.addView(vehicleSubtitle);

        gap(root, 34);
        LinearLayout status = horizontal();
        status.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        statusDot.setBackground(round(OFF, 100, 0));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.rightMargin = dp(10);
        status.addView(statusDot, dotParams);
        statusTitle = label("", 14, WHITE, true);
        status.addView(statusTitle);
        root.addView(status);
        gap(root, 9);
        statusBody = label("", 13, MUTED, false);
        statusBody.setLineSpacing(dp(3), 1f);
        root.addView(statusBody);
        gap(root, 24);
        connectButton = actionButton("დაკავშირება");
        connectButton.setOnClickListener(v -> {
            if (vehicle != null && !verified) verify();
            else connect();
        });
        root.addView(connectButton, new LinearLayout.LayoutParams(-1, dp(54)));

        gap(root, 44);
        root.addView(label("ფუნქციები", 17, WHITE, true));
        gap(root, 15);
        LinearLayout actions = vertical();
        actions.setBackground(round(PANEL, 16, 0));
        actions.setPadding(dp(17), dp(5), dp(17), dp(5));
        actions.addView(actionRow("კოდირება", "პარამეტრების შეცვლა", "მალე", () -> showDialog(
            "კოდირება ჯერ არ არის ხელმისაწვდომი",
            "ეს ვერსია მხოლოდ მანქანასთან კავშირს ამოწმებს. კოდირება დაემატება უსაფრთხო სარეზერვო ასლებისა და თავსებადობის შემოწმების შემდეგ.")));
        separator(actions);
        actions.addView(actionRow("ავტომობილის მონაცემები", "VIN და კავშირის ინფორმაცია", "›", () -> {
            if (vehicle == null) {
                showDialog("ავტომობილის მონაცემები", "ჯერ დაუკავშირდით ავტომობილს.");
            } else {
                boolean visible = vehiclePanel.getVisibility() == View.VISIBLE;
                vehiclePanel.setVisibility(visible ? View.GONE : View.VISIBLE);
            }
        }));
        separator(actions);
        advancedRow = actionRow("დამატებითი პარამეტრები", "IP მისამართი და კავშირის ჟურნალი", "›", () -> {
            showAdvanced = !showAdvanced;
            advancedPanel.setVisibility(showAdvanced ? View.VISIBLE : View.GONE);
        });
        actions.addView(advancedRow);
        root.addView(actions);

        vehiclePanel = vertical();
        vehiclePanel.setPadding(dp(18), dp(17), dp(18), dp(17));
        vehiclePanel.setBackground(round(PANEL, 16, 0));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2);
        vp.topMargin = dp(12);
        root.addView(vehiclePanel, vp);
        vinValue = detail(vehiclePanel, "VIN", "—");
        separatorInset(vehiclePanel);
        protocolValue = detail(vehiclePanel, "პროტოკოლი", "—");
        separatorInset(vehiclePanel);
        networkValue = detail(vehiclePanel, "Gateway IP", "—");
        vehiclePanel.setVisibility(View.GONE);

        advancedPanel = vertical();
        advancedPanel.setPadding(dp(18), dp(20), dp(18), dp(18));
        advancedPanel.setBackground(round(PANEL, 16, 0));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(12);
        root.addView(advancedPanel, ap);
        adapterModeValue = label("კავშირის ტიპი: USB-C ENET კაბელი    ›", 14, WHITE, true);
        adapterModeValue.setPadding(0, dp(4), 0, dp(15));
        adapterModeValue.setOnClickListener(v -> {
            String[] choices = {"USB-C ENET კაბელი", "ENET Wi-Fi ადაპტერი"};
            new AlertDialog.Builder(this)
                .setTitle("კავშირის ტიპი")
                .setSingleChoiceItems(choices, wiredMode ? 0 : 1, (dialog, selected) -> {
                    wiredMode = (selected == 0);
                    adapterModeValue.setText("კავშირის ტიპი: " + choices[selected] + "    ›");
                    dialog.dismiss();
                }).setNegativeButton("გაუქმება", null).show();
        });
        advancedPanel.addView(adapterModeValue);
        advancedPanel.addView(label("Gateway IP  ·  არასავალდებულო", 12, SOFT, false));
        gap(advancedPanel, 10);
        ipInput = new EditText(this);
        ipInput.setSingleLine(true);
        ipInput.setTextSize(14);
        ipInput.setTextColor(WHITE);
        ipInput.setHintTextColor(MUTED);
        ipInput.setHint("ავტომატური ძებნა");
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ipInput.setPadding(dp(13), 0, dp(13), 0);
        ipInput.setBackground(round(BG, 9, LINE));
        advancedPanel.addView(ipInput, new LinearLayout.LayoutParams(-1, dp(48)));
        gap(advancedPanel, 12);
        TextView adapterHint = label("კაბელის რეჟიმში KODA Wi-Fi-ს არ გამოიყენებს. USB Ethernet ინტერფეისის სტატუსი გამოჩნდება ჟურნალში. ELM327 არ მუშაობს.", 12, MUTED, false);
        adapterHint.setLineSpacing(dp(3), 1f);
        advancedPanel.addView(adapterHint);
        gap(advancedPanel, 24);
        advancedPanel.addView(label("კავშირის ჟურნალი", 13, SOFT, true));
        gap(advancedPanel, 9);
        logView = label("კავშირის ისტორია ცარიელია.", 11, MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setLineSpacing(dp(3), 1f);
        advancedPanel.addView(logView);
        gap(advancedPanel, 15);
        TextView copy = label("ჟურნალის კოპირება", 13, WHITE, true);
        copy.setPadding(0, dp(5), 0, dp(5));
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("KODA ENET log", debug.toString()));
            Toast.makeText(this, "ჟურნალი დაკოპირებულია", Toast.LENGTH_SHORT).show();
        });
        advancedPanel.addView(copy);
        advancedPanel.setVisibility(View.GONE);

        gap(root, 35);
        TextView footer = label("KODA · 0.3     •     მხოლოდ წაკითხვა", 11, MUTED, false);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(footer);
    }

    private void connect() {
        if (busy) return;
        hideKeyboard();
        busy = true;
        verified = false;
        vehicle = null;
        client = null;
        vehiclePanel.setVisibility(View.GONE);
        connectButton.setEnabled(false);
        connectButton.setText("იძებნება…");
        setStatus("ვუკავშირდებით BMW-ს…", "მოწმდება ENET კავშირი.", false);
        synchronized (debug) { debug.setLength(0); }
        log("START · DoIP / HSFZ");
        final String ip = ipInput.getText().toString().trim();
        final boolean selectedWiredMode = wiredMode;
        executor.execute(() -> {
            log(AndroidEnetNetwork.diagnosticReport(this, selectedWiredMode));
            List<AndroidEnetNetwork.Route> routes = AndroidEnetNetwork.routes(this, selectedWiredMode);
            if (routes.isEmpty()) {
                failed(AndroidEnetNetwork.missingNetworkHint(this, selectedWiredMode));
                return;
            }
            String failure = "მანქანა ვერ მოიძებნა";
            for (AndroidEnetNetwork.Route route : routes) {
                try {
                    EnetClient current = new EnetClient(route, this::log);
                    EnetProtocol.Vehicle found = current.discover(ip);
                    client = current;
                    vehicle = found;
                    runOnUiThread(() -> {
                        vinValue.setText(found.vin);
                        protocolValue.setText(found.protocol);
                        networkValue.setText(found.address);
                        setStatus("BMW ნაპოვნია", "შემდეგი ნაბიჯი: VIN-ის შემოწმება.", true);
                        connectButton.setText("VIN-ის შემოწმება");
                        connectButton.setEnabled(true);
                        busy = false;
                    });
                    return;
                } catch (Exception e) {
                    failure = e.getMessage() == null ? "კავშირის შეცდომა" : e.getMessage();
                    log("ERROR · " + failure);
                }
            }
            failed(failure);
        });
    }

    private void verify() {
        if (busy || vehicle == null || client == null) return;
        busy = true;
        connectButton.setEnabled(false);
        connectButton.setText("მოწმდება…");
        setStatus("VIN-ის შემოწმება", "მხოლოდ წაკითხვის მოთხოვნა; არაფერს ვცვლით.", false);
        EnetProtocol.Vehicle selected = vehicle;
        EnetClient current = client;
        executor.execute(() -> {
            try {
                String checked = current.verifyVin(selected);
                log("SUCCESS · ECU VIN read");
                runOnUiThread(() -> {
                    verified = checked.equals(selected.vin);
                    if (verified) {
                        setStatus("კავშირი დადასტურებულია", "მანქანის VIN გადამოწმდა. პარამეტრები არ შეცვლილა.", true);
                    } else {
                        setStatus("VIN არ ემთხვევა", "Gateway-ისა და ECU-ის VIN განსხვავებულია.", false);
                    }
                    vinValue.setText(checked);
                    connectButton.setEnabled(true);
                    connectButton.setText(verified ? "ხელახლა დაკავშირება" : "VIN-ის ხელახლა შემოწმება");
                    busy = false;
                });
            } catch (Exception e) {
                String problem = e.getMessage() == null ? "ECU-მ არ უპასუხა" : e.getMessage();
                log("ERROR · " + problem);
                runOnUiThread(() -> {
                    setStatus("VIN ვერ გადამოწმდა", problem, false);
                    connectButton.setEnabled(true);
                    connectButton.setText("VIN-ის ხელახლა შემოწმება");
                    busy = false;
                });
            }
        });
    }

    private void failed(String message) {
        runOnUiThread(() -> {
            setStatus("კავშირი ვერ დამყარდა", message, false);
            connectButton.setEnabled(true);
            connectButton.setText("ხელახლა ცდა");
            busy = false;
        });
    }

    private void log(String line) {
        synchronized (debug) {
            debug.append(line).append('\n');
            String output = debug.toString();
            runOnUiThread(() -> { if (logView != null) logView.setText(output); });
        }
    }

    private void setStatus(String title, String body, boolean success) {
        statusTitle.setText(title);
        statusBody.setText(body);
        statusDot.setBackground(round(success ? GOOD : OFF, 100, 0));
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(ipInput.getWindowToken(), 0);
        ipInput.clearFocus();
    }

    private void showDialog(String title, String body) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(body)
            .setPositiveButton("გასაგებია", (dialog, which) -> dialog.dismiss()).show();
    }

    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }

    private int dp(float value) { return (int)(getResources().getDisplayMetrics().density * value + .5f); }
    private LinearLayout vertical() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout horizontal() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private void gap(LinearLayout parent, int pixels) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(pixels))); }
    private void separator(LinearLayout parent) { View line = new View(this); line.setBackgroundColor(LINE); parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); }
    private void separatorInset(LinearLayout parent) { gap(parent, 16); separator(parent); gap(parent, 16); }

    private TextView label(String value, int size, int color, boolean medium) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setIncludeFontPadding(true);
        if (medium) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private LinearLayout actionRow(String title, String description, String trailing, Runnable onClick) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, dp(16));
        LinearLayout copy = vertical();
        copy.addView(label(title, 15, WHITE, true));
        gap(copy, 5);
        copy.addView(label(description, 12, MUTED, false));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView end = label(trailing, trailing.equals("›") ? 25 : 12, MUTED, false);
        end.setPadding(dp(10), 0, 0, 0);
        row.addView(end);
        row.setOnClickListener(v -> onClick.run());
        row.setClickable(true);
        return row;
    }

    private TextView detail(LinearLayout parent, String key, String value) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(key, 12, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView content = label(value, 13, WHITE, true);
        content.setTextIsSelectable(true);
        content.setGravity(Gravity.END);
        row.addView(content, new LinearLayout.LayoutParams(-2, -2));
        parent.addView(row);
        return content;
    }

    private Button actionButton(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTextColor(0xFF0E0F11);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setBackground(round(WHITE, 12, 0));
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }
}
