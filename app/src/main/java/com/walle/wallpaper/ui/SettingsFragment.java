package com.walle.wallpaper.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;

import com.walle.wallpaper.R;
import com.walle.wallpaper.util.SettingsManager;

public class SettingsFragment extends Fragment {

    // Fill these in once real destinations exist; rows using them show a placeholder
    // toast until then instead of guessing at a fake email/URL.
    private static final String SUPPORT_EMAIL = "wallpapershere01@gmail.com";
    private static final String SUPPORT_US_LINK = "";
    private static final String PRIVACY_POLICY_URL = "https://sites.google.com/view/walleprivacy/home";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ── Clock toggles ───────────────────────────────────────────────
        SwitchCompat swLock = view.findViewById(R.id.switch_lock_clock);
        SwitchCompat swHome = view.findViewById(R.id.switch_home_clock);
        SwitchCompat sw24 = view.findViewById(R.id.switch_24hour);

        swLock.setChecked(SettingsManager.isLockClockEnabled(requireContext()));
        swHome.setChecked(SettingsManager.isHomeClockEnabled(requireContext()));
        sw24.setChecked(SettingsManager.is24Hour(requireContext()));

        swLock.setOnCheckedChangeListener((b, v) -> {
            SettingsManager.setLockClockEnabled(requireContext(), v);
            broadcast();
        });
        swHome.setOnCheckedChangeListener((b, v) -> {
            SettingsManager.setHomeClockEnabled(requireContext(), v);
            broadcast();
        });
        sw24.setOnCheckedChangeListener((b, v) -> {
            SettingsManager.set24Hour(requireContext(), v);
            broadcast();
        });

        // ── Clock animation ─────────────────────────────────────────────
        SwitchCompat swAnim = view.findViewById(R.id.switch_clock_anim);
        LinearLayout layoutAnim = view.findViewById(R.id.layout_anim_style);
        RadioGroup radioStyle = view.findViewById(R.id.radio_anim_style);
        SeekBar seekSpeed = view.findViewById(R.id.seek_anim_speed);
        TextView tvSpeedValue = view.findViewById(R.id.tv_anim_speed_value);

        boolean animEnabled = SettingsManager.isClockAnimationEnabled(requireContext());
        swAnim.setChecked(animEnabled);
        layoutAnim.setVisibility(animEnabled ? View.VISIBLE : View.GONE);

        int curStyle = SettingsManager.getClockAnimationStyle(requireContext());
        selectAnimStyle(view, curStyle);

        swAnim.setOnCheckedChangeListener((b, isChecked) -> {
            SettingsManager.setClockAnimationEnabled(requireContext(), isChecked);
            layoutAnim.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            broadcast();
        });

        radioStyle.setOnCheckedChangeListener((g, checkedId) -> {
            int style = styleIndexFromId(checkedId);
            if (style >= 0) {
                SettingsManager.setClockAnimationStyle(requireContext(), style);
                broadcast();
            }
        });

        int savedSpeed = SettingsManager.getClockAnimationSpeed(requireContext());
        seekSpeed.setProgress(savedSpeed);
        tvSpeedValue.setText(speedLabel(savedSpeed));
        seekSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                SettingsManager.setClockAnimationSpeed(requireContext(), p);
                tvSpeedValue.setText(speedLabel(p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                broadcast();
            }
        });

        // ── Gyroscope ───────────────────────────────────────────────────
        SwitchCompat swGyro = view.findViewById(R.id.switch_gyro_enabled);
        LinearLayout layoutGyro = view.findViewById(R.id.layout_gyro_controls);
        RadioGroup radioMode = view.findViewById(R.id.radio_motion_mode);
        RadioButton rbTilt = view.findViewById(R.id.radio_motion_tilt);
        RadioButton rbShift = view.findViewById(R.id.radio_motion_shift);
        SeekBar seekSens = view.findViewById(R.id.seek_motion_sensitivity);
        TextView tvSensValue = view.findViewById(R.id.tv_sens_value);
        SeekBar seekAmount = view.findViewById(R.id.seek_motion_amount);
        TextView tvAmtValue = view.findViewById(R.id.tv_amount_value);

        boolean gyroOn = SettingsManager.isGyroEnabled(requireContext());
        swGyro.setChecked(gyroOn);
        layoutGyro.setVisibility(gyroOn ? View.VISIBLE : View.GONE);

        int mode = SettingsManager.getMotionMode(requireContext());
        if (mode == 0) rbTilt.setChecked(true);
        else rbShift.setChecked(true);

        int sens = SettingsManager.getMotionSensitivity(requireContext());
        seekSens.setProgress(Math.max(0, sens - 40));
        tvSensValue.setText(String.valueOf(sens));

        int amount = SettingsManager.getMotionAmount(requireContext());
        seekAmount.setProgress(Math.max(0, amount - 40));
        tvAmtValue.setText(String.valueOf(amount));

        swGyro.setOnCheckedChangeListener((b, isChecked) -> {
            SettingsManager.setGyroEnabled(requireContext(), isChecked);
            layoutGyro.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            broadcast();
        });

        radioMode.setOnCheckedChangeListener((g, checkedId) -> {
            SettingsManager.setMotionMode(requireContext(), checkedId == R.id.radio_motion_tilt ? 0 : 1);
            broadcast();
        });

        seekSens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                int val = p + 40;
                SettingsManager.setMotionSensitivity(requireContext(), val);
                tvSensValue.setText(String.valueOf(val));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                broadcast();
            }
        });

        seekAmount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                int val = p + 40;
                SettingsManager.setMotionAmount(requireContext(), val);
                tvAmtValue.setText(String.valueOf(val));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                broadcast();
            }
        });

        // ── App & Permission ──────────────────────────────────────────────
        setupAccordion(view, R.id.header_app_permission, R.id.body_app_permission, R.id.chevron_app_permission, false);
        setupAccordion(view, R.id.header_support_about, R.id.body_support_about, R.id.chevron_support_about, false);

        TextView tvVersion = view.findViewById(R.id.tv_app_version);
        if (tvVersion != null) {
            try {
                String versionName = requireContext().getPackageManager()
                        .getPackageInfo(requireContext().getPackageName(), 0).versionName;
                tvVersion.setText("Version " + versionName);
            } catch (Exception e) {
                tvVersion.setText("Version");
            }
        }

        clickRow(view, R.id.row_whats_new, v -> new AlertDialog.Builder(requireContext())
                .setTitle("What's New")
                .setMessage("You're on the latest version. Recent improvements include a smoother "
                        + "Studio editor, a redesigned navigation bar, and a favorites list for your wallpapers.")
                .setPositiveButton("OK", null)
                .show());

        setupNotificationsRow(view);

        clickRow(view, R.id.row_clear_cache, v -> clearAppCache());

        clickRow(view, R.id.row_help_faq, v -> new AlertDialog.Builder(requireContext())
                .setTitle("Help & FAQ")
                .setMessage("• To apply a wallpaper, open a wallpaper's preview and tap Apply.\n\n"
                        + "• To customize the clock or date, open the Editor tab.\n\n"
                        + "• Enable/disable the lock screen and home screen clock from Clock Display above.\n\n"
                        + "• Save wallpapers you like using the heart icon so they show up in Favorites.")
                .setPositiveButton("OK", null)
                .show());

        clickRow(view, R.id.row_contact_us, v -> {
            if (SUPPORT_EMAIL.isEmpty()) {
                Toast.makeText(requireContext(), "Support email not configured yet", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + SUPPORT_EMAIL));
            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(requireContext(), "No email app found", Toast.LENGTH_SHORT).show();
            }
        });

        clickRow(view, R.id.row_support_us, v -> {
            if (SUPPORT_US_LINK.isEmpty()) {
                Toast.makeText(requireContext(), "Support link not configured yet", Toast.LENGTH_SHORT).show();
                return;
            }
            openUrl(SUPPORT_US_LINK);
        });

        clickRow(view, R.id.row_rate_us, v -> {
            String pkg = requireContext().getPackageName();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
            } catch (Exception e) {
                openUrl("https://play.google.com/store/apps/details?id=" + pkg);
            }
        });

        clickRow(view, R.id.row_team_story, v -> new AlertDialog.Builder(requireContext())
                .setTitle("Our Team & Story")
                .setMessage("We're a small team that loves building beautiful, personal wallpapers. "
                        + "This app started as a way to make your lock and home screens feel truly yours — "
                        + "thanks for being part of the journey!")
                .setPositiveButton("OK", null)
                .show());

        clickRow(view, R.id.row_privacy_policy, v -> {
            if (PRIVACY_POLICY_URL.isEmpty()) {
                Toast.makeText(requireContext(), "Privacy policy link not configured yet", Toast.LENGTH_SHORT).show();
                return;
            }
            openUrl(PRIVACY_POLICY_URL);
        });

//        // ── Admin panel row ──────────────────────────────────────────────
//
//        View adminRow = view.findViewById(R.id.row_open_admin);
//        if (adminRow != null) {
//            adminRow.setOnClickListener(v -> requireActivity().getSupportFragmentManager()
//                    .beginTransaction()
//                    // Replace nav_host_fragment so it completely swaps the main content
//                    .replace(R.id.nav_host_fragment, new AdminFragment())
//                    .addToBackStack("admin")
//                    .commit());
//        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Reflect the current permission state in case the user changed it in system
        // settings and returned (e.g. via the Notifications row).
        if (getView() != null) updateNotificationsStatus(getView());
    }

    /** Wires a section header to show/hide its body and flip its chevron. */
    private void setupAccordion(View root, int headerId, int bodyId, int chevronId, boolean startExpanded) {
        View header = root.findViewById(headerId);
        View body = root.findViewById(bodyId);
        TextView chevron = root.findViewById(chevronId);
        if (header == null || body == null) return;

        body.setVisibility(startExpanded ? View.VISIBLE : View.GONE);
        if (chevron != null) chevron.setRotation(startExpanded ? 180f : 0f);

        header.setOnClickListener(v -> {
            boolean expanded = body.getVisibility() == View.VISIBLE;
            body.setVisibility(expanded ? View.GONE : View.VISIBLE);
            if (chevron != null) chevron.animate().rotation(expanded ? 0f : 180f).setDuration(180).start();
        });
    }

    private void clickRow(View root, int rowId, View.OnClickListener listener) {
        View row = root.findViewById(rowId);
        if (row != null) row.setOnClickListener(listener);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Couldn't open link", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupNotificationsRow(View root) {
        updateNotificationsStatus(root);
        clickRow(root, R.id.row_notifications, v -> {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
            try {
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + requireContext().getPackageName())));
            }
        });
    }

    private void updateNotificationsStatus(View root) {
        TextView tv = root.findViewById(R.id.tv_notifications_status);
        if (tv == null) return;
        boolean enabled = NotificationManagerCompat.from(requireContext()).areNotificationsEnabled();
        tv.setText(enabled ? "Enabled" : "Disabled");
    }

    private void clearAppCache() {
        new Thread(() -> {
            // Delete the cache dir's CONTENTS only — code elsewhere assumes getCacheDir()
            // itself always exists and may write to it without calling mkdirs() first.
            long freedBytes = 0;
            java.io.File[] children = requireContext().getCacheDir().listFiles();
            if (children != null) {
                for (java.io.File child : children) freedBytes += deleteRecursive(child);
            }
            try {
                com.bumptech.glide.Glide.get(requireContext()).clearDiskCache();
            } catch (Exception ignored) {
            }
            if (!isAdded()) return;
            long finalFreedBytes = freedBytes;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                com.bumptech.glide.Glide.get(requireContext()).clearMemory();
                long mb = finalFreedBytes / (1024 * 1024);
                Toast.makeText(requireContext(),
                        mb > 0 ? "Cache cleared (" + mb + " MB freed)" : "Cache cleared",
                        Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private long deleteRecursive(java.io.File file) {
        long size = 0;
        if (file == null || !file.exists()) return 0;
        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();
            if (children != null) {
                for (java.io.File child : children) size += deleteRecursive(child);
            }
        } else {
            size = file.length();
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return size;
    }

    private void broadcast() {
        requireContext().sendBroadcast(new Intent(SettingsManager.ACTION_SETTINGS_CHANGED));
    }

    private String speedLabel(int progress) {
        if (progress < 20) return "Very Slow";
        if (progress < 40) return "Slow";
        if (progress < 65) return "Normal";
        if (progress < 85) return "Fast";
        return "Very Fast";
    }

    private void selectAnimStyle(View root, int style) {
        int[] ids = {R.id.anim_style_0, R.id.anim_style_1, R.id.anim_style_2, R.id.anim_style_3,
                R.id.anim_style_4, R.id.anim_style_5, R.id.anim_style_6, R.id.anim_style_7};
        for (int i = 0; i < ids.length; i++) {
            RadioButton rb = root.findViewById(ids[i]);
            if (rb != null) rb.setChecked(i == style);
        }
    }

    private int styleIndexFromId(int id) {
        if (id == R.id.anim_style_0) return 0;
        if (id == R.id.anim_style_1) return 1;
        if (id == R.id.anim_style_2) return 2;
        if (id == R.id.anim_style_3) return 3;
        if (id == R.id.anim_style_4) return 4;
        if (id == R.id.anim_style_5) return 5;
        if (id == R.id.anim_style_6) return 6;
        if (id == R.id.anim_style_7) return 7;
        return -1;
    }
}
