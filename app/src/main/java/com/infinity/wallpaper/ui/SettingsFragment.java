package com.infinity.wallpaper.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.infinity.wallpaper.R;
import com.infinity.wallpaper.util.SettingsManager;

public class SettingsFragment extends Fragment {

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
        SwitchCompat sw24   = view.findViewById(R.id.switch_24hour);

        swLock.setChecked(SettingsManager.isLockClockEnabled(requireContext()));
        swHome.setChecked(SettingsManager.isHomeClockEnabled(requireContext()));
        sw24.setChecked(SettingsManager.is24Hour(requireContext()));

        swLock.setOnCheckedChangeListener((b, v) -> { SettingsManager.setLockClockEnabled(requireContext(), v); broadcast(); });
        swHome.setOnCheckedChangeListener((b, v) -> { SettingsManager.setHomeClockEnabled(requireContext(), v); broadcast(); });
        sw24.setOnCheckedChangeListener((b, v)   -> { SettingsManager.set24Hour(requireContext(), v); broadcast(); });

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
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                SettingsManager.setClockAnimationSpeed(requireContext(), p);
                tvSpeedValue.setText(speedLabel(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { broadcast(); }
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
        if (mode == 0) rbTilt.setChecked(true); else rbShift.setChecked(true);

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
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int val = p + 40;
                SettingsManager.setMotionSensitivity(requireContext(), val);
                tvSensValue.setText(String.valueOf(val));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { broadcast(); }
        });

        seekAmount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int val = p + 40;
                SettingsManager.setMotionAmount(requireContext(), val);
                tvAmtValue.setText(String.valueOf(val));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { broadcast(); }
        });

        // ── Admin panel row ──────────────────────────────────────────────
        View adminRow = view.findViewById(R.id.row_open_admin);
        if (adminRow != null) {
            adminRow.setOnClickListener(v -> {
                requireActivity().getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.nav_host_fragment, new AdminFragment())
                        .addToBackStack(null)
                        .commit();
            });
        }
    }

    private void broadcast() {
        requireContext().sendBroadcast(new Intent(SettingsManager.ACTION_SETTINGS_CHANGED));
    }

    private String speedLabel(int progress) {
        if (progress < 20)  return "Very Slow";
        if (progress < 40)  return "Slow";
        if (progress < 65)  return "Normal";
        if (progress < 85)  return "Fast";
        return "Very Fast";
    }

    private void selectAnimStyle(View root, int style) {
        int[] ids = { R.id.anim_style_0, R.id.anim_style_1, R.id.anim_style_2, R.id.anim_style_3,
                R.id.anim_style_4, R.id.anim_style_5, R.id.anim_style_6, R.id.anim_style_7 };
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
