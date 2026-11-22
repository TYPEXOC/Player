package com.tyranor.runtime.easyrpg;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.tyranor.runtime.diagnostics.TynRuntimeDiagnostics;
import org.easyrpg.player.R;

import java.io.File;

/**
 * Module info page: the launcher entry of the APK. Shows module identity,
 * storage permission state and the diagnostics log export. Games are never
 * launched from here.
 */
public class ModuleInfoActivity extends Activity {
    private static final String TAG = "TYN-EasyRPG[Info]";

    private TextView storageStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.tyn_module_name);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.tyn_info_subtitle);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        TextView hint = new TextView(this);
        hint.setText(R.string.tyn_info_hint);
        hint.setTextSize(13);
        hint.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(hint);

        root.addView(infoLine(getString(R.string.tyn_info_package), getPackageName()));
        root.addView(infoLine(getString(R.string.tyn_info_version), versionName()));
        storageStatus = infoLine(getString(R.string.tyn_info_storage), "");
        root.addView(storageStatus);

        root.addView(button(getString(R.string.tyn_info_grant_storage), this::requestStorageAccess));
        root.addView(button(getString(R.string.tyn_info_export_logs), this::exportDiagnostics));

        setContentView(root, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStorageStatus();
    }

    private TextView infoLine(String label, String value) {
        TextView view = new TextView(this);
        view.setText(label + ": " + (value == null || value.isEmpty()
            ? getString(R.string.tyn_info_storage_denied) : value));
        view.setTextSize(14);
        view.setPadding(0, 8, 0, 8);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "?";
        }
    }

    private void refreshStorageStatus() {
        if (storageStatus == null) return;
        boolean granted = hasStorageAccess();
        storageStatus.setText(getString(R.string.tyn_info_storage) + ": " + getString(
            granted ? R.string.tyn_info_storage_granted : R.string.tyn_info_storage_denied));
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        if (hasStorageAccess()) {
            Toast.makeText(this, R.string.tyn_info_storage_granted, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not open storage permission settings", error);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception fallbackError) {
                Log.e(TAG, "No storage permission settings page available", fallbackError);
            }
        }
    }

    private void exportDiagnostics() {
        Toast.makeText(this, R.string.tyn_info_exporting, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File archive = TynRuntimeDiagnostics.createArchive(this);
                if (archive == null) {
                    toast(R.string.tyn_info_no_logs);
                    return;
                }
                runOnUiThread(() -> {
                    try {
                        TynRuntimeDiagnostics.share(this, archive, getString(R.string.tyn_info_share_title));
                    } catch (Exception error) {
                        Log.e(TAG, "Could not share diagnostics archive", error);
                        toast(R.string.tyn_info_export_failed);
                    }
                });
            } catch (Exception error) {
                Log.e(TAG, "Could not export diagnostics", error);
                runOnUiThread(() -> toast(R.string.tyn_info_export_failed));
            }
        }, "tyn-easyrpg-diagnostics-export").start();
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }
}
