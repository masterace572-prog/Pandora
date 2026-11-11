package com.pandora.builder;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.pandora.builder.ui.GlassTabBar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public final class MainActivity extends Activity {
    private static final int SelectRequest = 100;
    private static final int SaveRequest = 101;
    private static final String ArchiveType = "application/vnd.android.package-archive";
    private static final String RecentKey = "RecentApks";

    private final ExecutorService Worker = Executors.newSingleThreadExecutor();
    private TextView InputName;
    private TextView InputDetails;
    private TextView Status;
    private View ChooseButton;
    private View BuildButton;
    private ProgressBar Progress;
    private File InputApk;
    private File OutputApk;
    private View BuildPage;
    private View AboutPage;
    private GlassTabBar Tabs;

    @Override
    protected void onCreate(Bundle State) {
        super.onCreate(State);
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));
        getWindow().getDecorView().setSystemUiVisibility(0);
        setContentView(R.layout.activity_host);

        BuildPage = findViewById(R.id.BuildPage);
        AboutPage = findViewById(R.id.AboutPage);
        Tabs = findViewById(R.id.TabBar);
        Tabs.SetBackdropSource(BuildPage);
        Tabs.SetOnTabSelected(this::ShowTab);

        InputName = findViewById(R.id.InputName);
        InputDetails = findViewById(R.id.InputDetails);
        Status = findViewById(R.id.Status);
        ChooseButton = findViewById(R.id.ChooseButton);
        BuildButton = findViewById(R.id.BuildButton);
        Progress = findViewById(R.id.Progress);

        ChooseButton.setOnClickListener(View -> ShowFilePicker());
        BuildButton.setOnClickListener(View -> BuildApk());
        findViewById(R.id.GitHubButton).setOnClickListener(
                View -> Open("https://github.com/AlwaysAnticheat"));
        findViewById(R.id.TelegramButton).setOnClickListener(
                View -> Open("https://t.me/Anticheat02"));
        findViewById(R.id.SourceButton).setOnClickListener(
                View -> Open("https://github.com/AlwaysAnticheat/Pandora"));
        findViewById(R.id.LicenseButton).setOnClickListener(
                View -> Open("https://opensource.org/license/mit"));
        try {
            String Version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.VersionValue)).setText(Version);
        } catch (android.content.pm.PackageManager.NameNotFoundException Error) {
            ((TextView) findViewById(R.id.VersionValue)).setText("1.0");
        }
        BuildButton.setAlpha(0.4f);

        if (State != null) {
            int TabIndex = State.getInt("TabIndex", 0);
            if (TabIndex != 0) {
                Tabs.SelectTab(TabIndex);
            }
            String InputPath = State.getString("InputPath");
            if (InputPath != null && new File(InputPath).isFile()) {
                InputApk = new File(InputPath);
                InputName.setText(State.getString("InputName", "Selected APK"));
                InputDetails.setText(State.getString("InputDetails", "Ready to build"));
                BuildButton.setEnabled(true);
                BuildButton.setAlpha(1f);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle State) {
        super.onSaveInstanceState(State);
        State.putInt("TabIndex", Tabs.GetSelectedIndex());
        if (InputApk != null) {
            State.putString("InputPath", InputApk.getAbsolutePath());
            State.putString("InputName", InputName.getText().toString());
            State.putString("InputDetails", InputDetails.getText().toString());
        }
    }

    private void ShowTab(int Index) {
        View Incoming = Index == 0 ? BuildPage : AboutPage;
        View Outgoing = Index == 0 ? AboutPage : BuildPage;
        Outgoing.animate().cancel();
        Outgoing.setVisibility(View.GONE);
        Incoming.animate().cancel();
        Incoming.setAlpha(0f);
        Incoming.setVisibility(View.VISIBLE);
        Tabs.SetBackdropSource(Incoming);
        Incoming.animate().alpha(1f).setDuration(180).start();
    }

    private void Open(String Url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Url)));
    }

    @Override
    public void onBackPressed() {
        if (Tabs.GetSelectedIndex() == 1) {
            Tabs.SelectTab(0);
        } else {
            super.onBackPressed();
        }
    }

    private void ChooseApk() {
        Intent Request = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        Request.addCategory(Intent.CATEGORY_OPENABLE);
        Request.setType("*/*");
        startActivityForResult(Request, SelectRequest);
    }

    private void ShowFilePicker() {
        Dialog Picker = new Dialog(this);
        Picker.setContentView(R.layout.dialog_file_picker);
        JSONArray Recent = GetRecent();
        LinearLayout List = Picker.findViewById(R.id.RecentList);
        Picker.findViewById(R.id.RecentTitle).setVisibility(
                Recent.length() == 0 ? View.GONE : View.VISIBLE);

        for (int Index = 0; Index < Recent.length(); Index++) {
            JSONObject Record = Recent.optJSONObject(Index);
            if (Record == null) {
                continue;
            }
            String UriText = Record.optString("uri");
            String Name = Record.optString("name", "APK file");
            View Row = getLayoutInflater().inflate(R.layout.recent_file_row, List, false);
            ((TextView) Row.findViewById(R.id.RecentName)).setText(Name);
            Row.setOnClickListener(View -> {
                Picker.dismiss();
                ReadInput(Uri.parse(UriText));
            });
            List.addView(Row);
        }

        Picker.findViewById(R.id.BrowseFilesButton).setOnClickListener(View -> {
            Picker.dismiss();
            ChooseApk();
        });
        Picker.findViewById(R.id.CancelButton).setOnClickListener(View -> Picker.dismiss());
        Picker.show();
        Window Popup = Picker.getWindow();
        if (Popup != null) {
            Popup.setBackgroundDrawableResource(android.R.color.transparent);
            Popup.setGravity(Gravity.BOTTOM);
            Popup.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private JSONArray GetRecent() {
        String Stored = getPreferences(MODE_PRIVATE).getString(RecentKey, "[]");
        try {
            return new JSONArray(Stored);
        } catch (JSONException Error) {
            return new JSONArray();
        }
    }

    private void Remember(Uri Selected, String Name) {
        try {
            getContentResolver().takePersistableUriPermission(
                    Selected, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException Error) {
            return;
        }
        try {
            JSONArray Old = GetRecent();
            JSONArray Updated = new JSONArray();
            JSONObject Current = new JSONObject();
            Current.put("uri", Selected.toString());
            Current.put("name", Name);
            Updated.put(Current);
            for (int Index = 0; Index < Old.length() && Updated.length() < 5; Index++) {
                JSONObject Record = Old.optJSONObject(Index);
                if (Record != null && !Selected.toString().equals(Record.optString("uri"))) {
                    Updated.put(Record);
                }
            }
            getPreferences(MODE_PRIVATE).edit().putString(RecentKey, Updated.toString()).apply();
        } catch (JSONException Error) {
            return;
        }
    }

    private void BuildApk() {
        if (InputApk == null || !InputApk.isFile()) {
            ShowError("Choose an APK first");
            return;
        }
        SetBusy(true, "Building the unsigned APK…");
        Worker.execute(() -> {
            try (InputStream Payload = getAssets().open("pandora/payload.apk")) {
                File Result = new File(getCacheDir(), "pandora-output.apk");
                ApkPatcher.Result Details = new ApkPatcher().Patch(
                        InputApk, Payload, Result, getPackageManager());
                OutputApk = Result;
                runOnUiThread(() -> {
                    SetBusy(false, "Ready to save " + Details.PackageName);
                    Intent Request = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    Request.addCategory(Intent.CATEGORY_OPENABLE);
                    Request.setType(ArchiveType);
                    Request.putExtra(Intent.EXTRA_TITLE,
                            Details.PackageName + "-pandora-unsigned.apk");
                    startActivityForResult(Request, SaveRequest);
                });
            } catch (Exception Error) {
                runOnUiThread(() -> ShowError(Error.getMessage()));
            }
        });
    }

    @Override
    protected void onActivityResult(int RequestCode, int ResultCode, Intent Data) {
        super.onActivityResult(RequestCode, ResultCode, Data);
        if (ResultCode != RESULT_OK || Data == null || Data.getData() == null) {
            return;
        }
        Uri Selected = Data.getData();
        if (RequestCode == SelectRequest) {
            ReadInput(Selected);
        } else if (RequestCode == SaveRequest) {
            SaveOutput(Selected);
        }
    }

    private void ReadInput(Uri Selected) {
        String Name = ReadDisplayName(Selected);
        SetBusy(true, "Reading the selected APK…");
        Worker.execute(() -> {
            try {
                File Candidate = new File(getCacheDir(), "pandora-input.apk");
                try (InputStream Input = getContentResolver().openInputStream(Selected)) {
                    if (Input == null) {
                        throw new IOException("The selected file could not be opened");
                    }
                    Files.copy(Input, Candidate.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                PackageInfo Package = getPackageManager().getPackageArchiveInfo(
                        Candidate.getAbsolutePath(), 0);
                if (Package == null || Package.packageName == null) {
                    throw new IOException("The selected file is not a readable APK");
                }
                InputApk = Candidate;
                Remember(Selected, Name);
                String Details = Package.packageName + "  ·  " +
                        String.format(Locale.ROOT, "%.1f MB", Candidate.length() / 1048576.0);
                runOnUiThread(() -> {
                    InputName.setText(Name);
                    InputDetails.setText(Details);
                    SetBusy(false, "Ready to build");
                });
            } catch (Exception Error) {
                runOnUiThread(() -> ShowError(Error.getMessage()));
            }
        });
    }

    private void SaveOutput(Uri Destination) {
        if (OutputApk == null || !OutputApk.isFile()) {
            ShowError("Build the APK before saving");
            return;
        }
        SetBusy(true, "Saving the unsigned APK…");
        Worker.execute(() -> {
            try (OutputStream Output = getContentResolver().openOutputStream(Destination, "w")) {
                if (Output == null) {
                    throw new IOException("The destination could not be opened");
                }
                Files.copy(OutputApk.toPath(), Output);
                runOnUiThread(() -> SetBusy(false, "Unsigned APK saved"));
            } catch (Exception Error) {
                runOnUiThread(() -> ShowError(Error.getMessage()));
            }
        });
    }

    private String ReadDisplayName(Uri Selected) {
        try (Cursor Result = getContentResolver().query(Selected,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (Result != null && Result.moveToFirst()) {
                return Result.getString(0);
            }
        } catch (RuntimeException Error) {
            return "Selected APK";
        }
        return "Selected APK";
    }

    private void ShowError(String Message) {
        SetBusy(false, Message == null ? "Operation failed" : Message);
    }

    private void SetBusy(boolean Busy, String Message) {
        Progress.setVisibility(Busy ? View.VISIBLE : View.GONE);
        ChooseButton.setEnabled(!Busy);
        BuildButton.setEnabled(!Busy && InputApk != null && InputApk.isFile());
        BuildButton.setAlpha(BuildButton.isEnabled() ? 1f : 0.4f);
        Status.setText(Message);
    }

    @Override
    protected void onDestroy() {
        Worker.shutdownNow();
        super.onDestroy();
    }
}
