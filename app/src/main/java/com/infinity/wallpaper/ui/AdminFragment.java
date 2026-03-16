package com.infinity.wallpaper.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.gson.Gson;
import com.infinity.wallpaper.R;
import com.infinity.wallpaper.data.WallpaperItem;
import com.infinity.wallpaper.data.upload.R2DirectUploader;
import com.infinity.wallpaper.util.DebugSecrets;
import com.infinity.wallpaper.util.StudioManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AdminFragment extends Fragment {

    private static final String TAG = "AdminFragment";
    private static final String PREFS = "wallpaper_prefs";
    private static final String KEY_PENDING_ADMIN = "admin_pending";

    // UI
    private ViewFlipper viewFlipper;
    private TextView tabList, tabCreate;

    // List tab
    private RecyclerView recyclerWallpapers;
    private TextView emptyView;
    private ProgressBar listLoading;
    private AdminWallpaperListAdapter listAdapter;

    // Create/Edit tab
    private Uri bgUri, maskUri, previewUri;
    private Uri theme1PreviewUri, theme2PreviewUri;
    private String existingTheme1PreviewUrl, existingTheme2PreviewUrl;
    private ImageView imgBg, imgMask, imgPreview;
    private ImageView imgTheme1Preview, imgTheme2Preview;
    private View theme2PreviewContainer;
    private EditText etName, etCategory;
    private SwitchCompat swPremium;
    private View editBanner;
    private TextView btnCancelEdit, btnUpload;
    private TextView btnTheme1, btnTheme2; // theme count toggles

    // Edit state
    private String editingDocId = null;      // non-null when editing existing wallpaper
    private String existingBgUrl = null;
    private String existingMaskUrl = null;
    private String existingPreviewUrl = null;
    private int themeCount = 1;              // 1 or 2 themes to create
    private String pendingTheme2Json = null; // theme2 JSON when themeCount==2

    // Image pickers
    private final ActivityResultLauncher<Intent> pickBg = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null) {
                    Uri u = res.getData().getData();
                    if (u != null) {
                        bgUri = u;
                        tryTakePersist(u);
                        imgBg.setImageURI(u);
                    }
                }
            });

    private final ActivityResultLauncher<Intent> pickMask = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null) {
                    Uri u = res.getData().getData();
                    if (u != null) {
                        maskUri = u;
                        tryTakePersist(u);
                        imgMask.setImageURI(u);
                    }
                }
            });

    private final ActivityResultLauncher<Intent> pickPreview = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null) {
                    Uri u = res.getData().getData();
                    if (u != null) {
                        previewUri = u;
                        tryTakePersist(u);
                        imgPreview.setImageURI(u);
                    }
                }
            });

    private final ActivityResultLauncher<Intent> pickTheme1Preview = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null) {
                    Uri u = res.getData().getData();
                    if (u != null) {
                        theme1PreviewUri = u;
                        tryTakePersist(u);
                        if (imgTheme1Preview != null) imgTheme1Preview.setImageURI(u);
                    }
                }
            });

    private final ActivityResultLauncher<Intent> pickTheme2Preview = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null) {
                    Uri u = res.getData().getData();
                    if (u != null) {
                        theme2PreviewUri = u;
                        tryTakePersist(u);
                        if (imgTheme2Preview != null) imgTheme2Preview.setImageURI(u);
                    }
                }
            });

    private void tryTakePersist(Uri u) {
        try {
            requireContext().getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_admin, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ── Header back ──
        view.findViewById(R.id.admin_btn_back).setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());

        // ── Tabs ──
        viewFlipper = view.findViewById(R.id.admin_view_flipper);
        tabList     = view.findViewById(R.id.admin_tab_list);
        tabCreate   = view.findViewById(R.id.admin_tab_create);

        tabList.setOnClickListener(v -> switchTab(0));
        tabCreate.setOnClickListener(v -> {
            clearPendingAdmin();
            StudioManager.clearAll(requireContext());
            clearEditState();
            switchTab(1);
        });

        // ── List tab views ──
        recyclerWallpapers = view.findViewById(R.id.admin_recycler_wallpapers);
        emptyView          = view.findViewById(R.id.admin_list_empty);
        listLoading        = view.findViewById(R.id.admin_list_loading);

        listAdapter = new AdminWallpaperListAdapter(
                        this::showFullscreenPreview,
                        this::enterEditMode,
                        this::confirmDelete
        );
        recyclerWallpapers.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerWallpapers.setAdapter(listAdapter);
        // ── Create/Edit tab views ──
        imgBg      = view.findViewById(R.id.admin_img_bg);
        imgMask    = view.findViewById(R.id.admin_img_mask);
        imgPreview = view.findViewById(R.id.admin_img_preview);
        etName     = view.findViewById(R.id.admin_et_name);
        etCategory = view.findViewById(R.id.admin_et_category);
        swPremium  = view.findViewById(R.id.admin_sw_premium);
        editBanner = view.findViewById(R.id.admin_edit_banner);

        btnCancelEdit = view.findViewById(R.id.admin_btn_cancel_edit);
        btnUpload  = view.findViewById(R.id.admin_btn_upload);

        view.findViewById(R.id.admin_btn_pick_bg).setOnClickListener(v -> pickBg.launch(makePickIntent()));
        view.findViewById(R.id.admin_btn_pick_mask).setOnClickListener(v -> pickMask.launch(makePickIntent()));
        view.findViewById(R.id.admin_btn_pick_preview).setOnClickListener(v -> pickPreview.launch(makePickIntent()));

        // Theme preview image pickers
        imgTheme1Preview = view.findViewById(R.id.admin_img_theme1_preview);
        imgTheme2Preview = view.findViewById(R.id.admin_img_theme2_preview);
        theme2PreviewContainer = view.findViewById(R.id.admin_theme2_preview_container);
        view.findViewById(R.id.admin_btn_pick_theme1_preview).setOnClickListener(v -> pickTheme1Preview.launch(makePickIntent()));
        view.findViewById(R.id.admin_btn_pick_theme2_preview).setOnClickListener(v -> pickTheme2Preview.launch(makePickIntent()));

        btnCancelEdit.setOnClickListener(v -> {
            clearPendingAdmin();
            StudioManager.clearAll(requireContext());
            clearEditState();
            switchTab(0);
        });

        view.findViewById(R.id.admin_btn_open_studio).setOnClickListener(v -> openStudio());
        btnUpload.setOnClickListener(v -> performUpload());

        // Theme count toggles
        btnTheme1 = view.findViewById(R.id.admin_theme_count_1);
        btnTheme2 = view.findViewById(R.id.admin_theme_count_2);
        btnTheme1.setOnClickListener(v -> setThemeCount(1));
        btnTheme2.setOnClickListener(v -> setThemeCount(2));

        // Restore pending admin state (came back from Studio)
        restorePendingState();

        // Load wallpaper list
        loadWallpaperList();
    }

    // ── Tab switching ──────────────────────────────────────────────────────────

    private void switchTab(int idx) {
        viewFlipper.setDisplayedChild(idx);
        if (idx == 0) {
            tabList.setTextColor(requireContext().getColor(R.color.accent));
            tabList.setTypeface(null, android.graphics.Typeface.BOLD);
            tabCreate.setTextColor(0x88EEEEEE);
            tabCreate.setTypeface(null, android.graphics.Typeface.NORMAL);
            loadWallpaperList(); // refresh
        } else {
            tabCreate.setTextColor(requireContext().getColor(R.color.accent));
            tabCreate.setTypeface(null, android.graphics.Typeface.BOLD);
            tabList.setTextColor(0x88EEEEEE);
            tabList.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    // ── List loading ───────────────────────────────────────────────────────────

    private void loadWallpaperList() {
        listLoading.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        FirebaseFirestore.getInstance().collection("wallpapers")
                .get()
                .addOnSuccessListener(snap -> {
                    if (!isAdded()) return;
                    List<AdminWallpaperItem> items = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snap) {
                        try {
                            WallpaperItem w = doc.toObject(WallpaperItem.class);
                            w.id = doc.getId();
                            long applyCount = 0;
                            Object ac = doc.get("applyCount");
                            if (ac instanceof Long) applyCount = (Long) ac;
                            else if (ac instanceof Double) applyCount = ((Double) ac).longValue();
                            items.add(new AdminWallpaperItem(w, applyCount));
                        } catch (Exception e) {
                            Log.e(TAG, "parse error for doc " + doc.getId(), e);
                        }
                    }
                    // Sort by name in memory
                    items.sort((a, b) -> {
                        String na = a.item.name != null ? a.item.name : a.item.id;
                        String nb = b.item.name != null ? b.item.name : b.item.id;
                        return na.compareToIgnoreCase(nb);
                    });
                    listLoading.setVisibility(View.GONE);
                    if (items.isEmpty()) emptyView.setVisibility(View.VISIBLE);
                    listAdapter.setItems(items);
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    listLoading.setVisibility(View.GONE);
                    toast("Failed to load: " + e.getMessage());
                });
    }

    // ── Edit mode ──────────────────────────────────────────────────────────────

    private void enterEditMode(AdminWallpaperItem adminItem) {
        WallpaperItem w = adminItem.item;
        editingDocId = w.id;
        existingBgUrl = w.bgUrl;
        existingMaskUrl = w.maskUrl;
        existingPreviewUrl = w.previewUrl;

        // Restore existing per-theme preview URLs
        existingTheme1PreviewUrl = getThemePreviewUrlFromItem(w, "theme1");
        existingTheme2PreviewUrl = getThemePreviewUrlFromItem(w, "theme2");

        // Detect theme count from existing data
        int storedThemeCount = (w.themes != null && w.themes.size() >= 2) ? 2 : 1;
        setThemeCount(storedThemeCount);

        etName.setText(w.name != null ? w.name : "");
        etName.setEnabled(false); // don't allow changing the doc ID
        etCategory.setText(w.category != null ? w.category : "");
        swPremium.setChecked(w.isPremium);

        // Load this wallpaper's theme1 as the Studio base theme so Studio preview shows it correctly
        loadWallpaperThemeIntoPrefs(w);

        // Show existing images via Glide
        bgUri = null; maskUri = null; previewUri = null;
        theme1PreviewUri = null; theme2PreviewUri = null;
        if (w.bgUrl != null) Glide.with(this).load(w.bgUrl).into(imgBg);
        if (w.maskUrl != null) Glide.with(this).load(w.maskUrl).into(imgMask);
        if (w.previewUrl != null) Glide.with(this).load(w.previewUrl).into(imgPreview);
        if (existingTheme1PreviewUrl != null && imgTheme1Preview != null)
            Glide.with(this).load(existingTheme1PreviewUrl).into(imgTheme1Preview);
        if (existingTheme2PreviewUrl != null && imgTheme2Preview != null)
            Glide.with(this).load(existingTheme2PreviewUrl).into(imgTheme2Preview);

        editBanner.setVisibility(View.VISIBLE);
        View note = requireView().findViewById(R.id.admin_edit_images_note);
        if (note != null) note.setVisibility(View.VISIBLE);
        btnUpload.setText(R.string.admin_update_wallpaper);

        switchTab(1);
    }

    /**
     * Writes the wallpaper's theme1 JSON into shared prefs as "theme_json" (the Studio base theme)
     * and clears overrides so Studio starts fresh with this wallpaper's configuration.
     */
    private void loadWallpaperThemeIntoPrefs(WallpaperItem w) {
        try {
            // Build theme1 JSON from the WallpaperItem themes map
            String theme1Json = null;
            if (w.themes != null) {
                Object t1 = w.themes.getOrDefault("theme1", null);
                if (t1 == null && !w.themes.isEmpty()) {
                    t1 = w.themes.values().iterator().next();
                }
                if (t1 != null) {
                    theme1Json = new com.google.gson.Gson().toJson(t1);
                }
            }
            if (theme1Json == null || theme1Json.isEmpty()) return;

            // Save as base theme and clear overrides so Studio shows this wallpaper
            requireContext().getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("theme_json", theme1Json)
                    .putString("studio_overrides", "{}")
                    .apply();
            Log.d(TAG, "Loaded wallpaper theme1 into prefs for edit: " + theme1Json);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load wallpaper theme into prefs: " + e.getMessage());
        }
    }

    private void clearEditState() {
        editingDocId = null;
        existingBgUrl = existingMaskUrl = existingPreviewUrl = null;
        existingTheme1PreviewUrl = existingTheme2PreviewUrl = null;
        bgUri = maskUri = previewUri = null;
        theme1PreviewUri = theme2PreviewUri = null;
        themeCount = 1;
        pendingTheme2Json = null;
        requireContext().getSharedPreferences("wallpaper_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("admin_theme1_json", "").apply();
        if (etName != null) { etName.setText(""); etName.setEnabled(true); }
        if (etCategory != null) etCategory.setText("");
        if (swPremium != null) swPremium.setChecked(false);
        if (imgBg != null) imgBg.setImageDrawable(null);
        if (imgMask != null) imgMask.setImageDrawable(null);
        if (imgPreview != null) imgPreview.setImageDrawable(null);
        if (imgTheme1Preview != null) imgTheme1Preview.setImageDrawable(null);
        if (imgTheme2Preview != null) imgTheme2Preview.setImageDrawable(null);
        if (editBanner != null) editBanner.setVisibility(View.GONE);
        if (btnUpload != null) btnUpload.setText(R.string.admin_upload);
        setThemeCount(1);
        View note = getView() != null ? getView().findViewById(R.id.admin_edit_images_note) : null;
        if (note != null) note.setVisibility(View.GONE);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private void confirmDelete(AdminWallpaperItem adminItem) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Wallpaper")
                .setMessage("Delete \"" + adminItem.item.name + "\"? This removes it from Firestore (R2 images are NOT deleted).")
                .setPositiveButton("Delete", (d, w2) -> performDelete(adminItem))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performDelete(AdminWallpaperItem adminItem) {
        listLoading.setVisibility(View.VISIBLE);
        FirebaseFirestore.getInstance().collection("wallpapers")
                .document(adminItem.item.id)
                .delete()
                .addOnSuccessListener(v -> {
                    toast("Deleted ✅");
                    loadWallpaperList();
                })
                .addOnFailureListener(e -> {
                    listLoading.setVisibility(View.GONE);
                    toast("Delete failed: " + e.getMessage());
                });
    }

    // ── Fullscreen Preview ───────────────────────────────────────────────────

    private void showFullscreenPreview(AdminWallpaperItem adminItem) {
        WallpaperItem w = adminItem.item;
        // Use the real composited preview dialog (bg + mask + time/date rendered)
        // Admin preview is view-only — no apply action needed, so pass null listener
        com.infinity.wallpaper.ui.common.WallpaperPreviewDialog.show(requireContext(), w, null);
    }

    // ── Upload / Update ───────────────────────────────────────────────────────

    private void performUpload() {
        String name     = etName.getText() != null ? etName.getText().toString().trim() : "";
        String category = etCategory.getText() != null ? etCategory.getText().toString().trim() : "";
        boolean premium = swPremium.isChecked();

        if (TextUtils.isEmpty(name))     { toast("Name is required"); return; }
        if (TextUtils.isEmpty(category)) { toast("Category is required"); return; }

        boolean isEdit = editingDocId != null;
        if (!isEdit) {
            // Creating: all images required
            if (bgUri == null || maskUri == null || previewUri == null) {
                toast("Pick BG, Mask and Preview images");
                return;
            }
        }

        // Get studio theme JSON — always read from effective (base + overrides)
        JSONObject theme1 = null;
        try {
            String eff = StudioManager.getEffectiveThemeJson(requireContext());
            if (!eff.isEmpty()) theme1 = new JSONObject(eff);
        } catch (Exception ex) {
            Log.w(TAG, "Could not read Studio theme: " + ex.getMessage());
        }

        // Only redirect to Studio if theme has no time AND user has not already been to Studio
        // (If pending state exists it means we already saved state before going to Studio)
        boolean studioVisited = loadPendingAdmin() != null;
        if (!studioVisited && (theme1 == null || !theme1.has("time"))) {
            // Save state and open studio for the first time (Theme 1)
            savePendingAdmin(name, category, premium, isEdit ? editingDocId : null);
            toast("Opening Studio – configure Theme 1 then tap Upload");
            openStudio();
            return;
        }

        // For 2 themes: if theme1 is done but theme2 not yet configured, open Studio for theme2
        JSONObject theme2 = null;
        if (themeCount >= 2) {
            if (pendingTheme2Json != null && !pendingTheme2Json.isEmpty()) {
                try { theme2 = new JSONObject(pendingTheme2Json); } catch (Exception ignored) {}
            }
            if (theme2 == null || !theme2.has("time")) {
                // Save theme1 as pendingTheme2Json=PENDING signal, then open Studio fresh for theme2
                String theme1JsonStr = theme1 != null ? theme1.toString() : "{}";
                savePendingAdmin(name, category, premium, isEdit ? editingDocId : null);
                // Store theme1 in prefs so we can retrieve it after Studio
                requireContext().getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                        .edit().putString("admin_theme1_json", theme1JsonStr).apply();
                // Clear overrides so Studio starts fresh for theme2
                StudioManager.clearAll(requireContext());
                toast("Now configure Theme 2 in Studio, then tap Upload");
                openStudio();
                return;
            }
        }

        final JSONObject finalTheme = theme1;
        final JSONObject finalTheme2 = theme2;
        final String docId = isEdit ? editingDocId : safeId(name);

        // Disable button to prevent double-tap
        btnUpload.setEnabled(false);
        btnUpload.setText(R.string.admin_uploading);

        new Thread(() -> {
            try {
                DebugSecrets.R2Keys keys = DebugSecrets.loadR2Keys(requireContext());
                if (keys == null) throw new IllegalStateException("R2 keys not configured");

                requireActivity().runOnUiThread(() -> toast("Uploading images…"));

                String bgUrl = existingBgUrl;
                String maskUrl = existingMaskUrl;
                String previewUrl = existingPreviewUrl;

                // Upload new images if picked
                if (bgUri != null) {
                    String t = guessContentType(requireContext(), bgUri);
                    bgUrl = R2DirectUploader.upload(requireContext(), bgUri, "wallpapers/" + docId + "/bg" + guessExt(t), t, keys.accessKeyId, keys.secretAccessKey, null);
                }
                if (maskUri != null) {
                    String t = guessContentType(requireContext(), maskUri);
                    maskUrl = R2DirectUploader.upload(requireContext(), maskUri, "wallpapers/" + docId + "/mask" + guessExt(t), t, keys.accessKeyId, keys.secretAccessKey, null);
                }
                if (previewUri != null) {
                    String t = guessContentType(requireContext(), previewUri);
                    previewUrl = R2DirectUploader.upload(requireContext(), previewUri, "wallpapers/" + docId + "/preview" + guessExt(t), t, keys.accessKeyId, keys.secretAccessKey, null);
                }

                // Upload theme preview images if picked
                String theme1PrevUrl = existingTheme1PreviewUrl;
                String theme2PrevUrl = existingTheme2PreviewUrl;
                if (theme1PreviewUri != null) {
                    String t = guessContentType(requireContext(), theme1PreviewUri);
                    theme1PrevUrl = R2DirectUploader.upload(requireContext(), theme1PreviewUri,
                            "wallpapers/" + docId + "/theme1_preview" + guessExt(t), t,
                            keys.accessKeyId, keys.secretAccessKey, null);
                }
                if (theme2PreviewUri != null) {
                    String t = guessContentType(requireContext(), theme2PreviewUri);
                    theme2PrevUrl = R2DirectUploader.upload(requireContext(), theme2PreviewUri,
                            "wallpapers/" + docId + "/theme2_preview" + guessExt(t), t,
                            keys.accessKeyId, keys.secretAccessKey, null);
                }

                Map<String, Object> doc = new HashMap<>();
                doc.put("name", name);
                doc.put("category", category);
                doc.put("bgUrl", bgUrl);
                doc.put("maskUrl", maskUrl);
                doc.put("previewUrl", previewUrl);
                doc.put("isPremium", premium);

                if (finalTheme != null && finalTheme.has("time")) {
                    Map<String, Object> themes = new HashMap<>();
                    // Embed previewUrl into theme1 map
                    Map<String, Object> theme1Map = jsonToMap(finalTheme);
                    if (theme1PrevUrl != null && !theme1PrevUrl.isEmpty())
                        theme1Map.put("previewUrl", theme1PrevUrl);
                    themes.put("theme1", theme1Map);

                    if (themeCount >= 2 && finalTheme2 != null && finalTheme2.has("time")) {
                        Map<String, Object> theme2Map = jsonToMap(finalTheme2);
                        if (theme2PrevUrl != null && !theme2PrevUrl.isEmpty())
                            theme2Map.put("previewUrl", theme2PrevUrl);
                        themes.put("theme2", theme2Map);
                    }
                    doc.put("themes", themes);
                }

                requireActivity().runOnUiThread(() -> toast("Saving to Firestore…"));

                com.google.android.gms.tasks.Task<Void> task = isEdit
                        ? FirebaseFirestore.getInstance().collection("wallpapers").document(docId).update(doc)
                        : FirebaseFirestore.getInstance().collection("wallpapers").document(docId).set(doc);

                task.addOnSuccessListener(unused -> {
                    if (!isAdded()) return;
                    String action = isEdit ? "Updated" : "Uploaded";
                    toast(action + " \"" + name + "\" ✅");
                    Log.d(TAG, action + " wallpaper id=" + docId);
                    clearPendingAdmin();
                    StudioManager.clearAll(requireContext()); // reset overrides for next session
                    clearEditState();
                    requireActivity().runOnUiThread(() -> {
                        btnUpload.setEnabled(true);
                        btnUpload.setText(R.string.admin_upload);
                        switchTab(0);
                    });
                }).addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    toast("Firestore write failed: " + e.getMessage());
                    requireActivity().runOnUiThread(() -> {
                        btnUpload.setEnabled(true);
                        btnUpload.setText(isEdit ? R.string.admin_update_wallpaper : R.string.admin_upload);
                    });
                });

            } catch (Exception e) {
                Log.e(TAG, "Upload failed", e);
                requireActivity().runOnUiThread(() -> {
                    toast("Upload failed: " + e.getMessage());
                    if (btnUpload != null) {
                        btnUpload.setEnabled(true);
                        btnUpload.setText(editingDocId != null ? "Update Wallpaper" : "Upload");
                    }
                });
            }
        }).start();
    }

    private String getThemePreviewUrlFromItem(WallpaperItem item, String themeKey) {
        if (item.themes == null) return null;
        Object val = item.themes.get(themeKey);
        if (val == null) return null;
        try {
            String json = val instanceof String ? (String) val : new Gson().toJson(val);
            return com.infinity.wallpaper.ui.common.ThemePickerSheet.getThemePreviewUrl(json);
        } catch (Exception e) { return null; }
    }

    // ── Theme count ────────────────────────────────────────────────────────────
    private void setThemeCount(int count) {
        themeCount = count;
        // Toggle buttons
        if (btnTheme1 != null && btnTheme2 != null) {
            android.content.res.Resources.Theme theme = requireContext().getTheme();
            int accentColor  = requireContext().getResources().getColor(R.color.accent, theme);
            int dimText      = 0x88EEEEEE;
            int black        = requireContext().getResources().getColor(R.color.black, theme);
            int surfaceColor = requireContext().getResources().getColor(R.color.dark_surface, theme);
            if (count == 1) {
                btnTheme1.setBackgroundColor(accentColor);
                btnTheme1.setTextColor(black);
                btnTheme2.setBackgroundColor(surfaceColor);
                btnTheme2.setTextColor(dimText);
            } else {
                btnTheme2.setBackgroundColor(accentColor);
                btnTheme2.setTextColor(black);
                btnTheme1.setBackgroundColor(surfaceColor);
                btnTheme1.setTextColor(dimText);
            }
        }
        // Show theme2 preview picker only when 2 themes
        if (theme2PreviewContainer != null) {
            theme2PreviewContainer.setVisibility(count >= 2 ? View.VISIBLE : View.GONE);
        }
    }

    // ── Studio ─────────────────────────────────────────────────────────────────

    private void openStudio() {
        // Save all current form state to prefs BEFORE navigating away
        // so it can be fully restored when back is pressed from Studio
        String name     = etName.getText() != null ? etName.getText().toString().trim() : "";
        String category = etCategory.getText() != null ? etCategory.getText().toString().trim() : "";
        boolean premium = swPremium != null && swPremium.isChecked();
        savePendingAdmin(name, category, premium, editingDocId);

        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(requireContext().getFilesDir(), "wallpaper");
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "Failed to create wallpaper dir: " + dir.getAbsolutePath());
                }

                // Copy newly-picked local images first
                if (bgUri != null)   copyUriToFile(bgUri,   new java.io.File(dir, "bg.png"));
                if (maskUri != null) copyUriToFile(maskUri, new java.io.File(dir, "mask.png"));

                // If no new local images but existing remote URLs are set, download them
                // so Studio shows THIS wallpaper's images, not whatever is currently applied
                com.infinity.wallpaper.util.DownloadWithProgress dl = new com.infinity.wallpaper.util.DownloadWithProgress();
                if (bgUri == null && existingBgUrl != null && !existingBgUrl.isEmpty()) {
                    Log.d(TAG, "Downloading existing bgUrl for Studio: " + existingBgUrl);
                    dl.download(existingBgUrl, new java.io.File(dir, "bg.png"), null);
                }
                if (maskUri == null && existingMaskUrl != null && !existingMaskUrl.isEmpty()) {
                    Log.d(TAG, "Downloading existing maskUrl for Studio: " + existingMaskUrl);
                    dl.download(existingMaskUrl, new java.io.File(dir, "mask.png"), null);
                }

                // Only clear Studio overrides on FIRST open (no existing overrides yet).
                // If overrides already exist the user already visited Studio — keep their edits.
                String existingOverrides = StudioManager.getOverridesJson(requireContext());
                boolean hasOverrides = !existingOverrides.equals("{}") && !existingOverrides.isEmpty();
                if (!hasOverrides) {
                    StudioManager.clearAll(requireContext());
                }

                requireActivity().runOnUiThread(this::navigateToStudio);
            } catch (Exception e) {
                Log.e(TAG, "openStudio failed: " + e.getMessage(), e);
                requireActivity().runOnUiThread(() -> toast("Failed to prepare images: " + e.getMessage()));
            }
        }).start();
    }

    private void navigateToStudio() {
        try {
            requireActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.nav_host_fragment, new StudioFragment())
                    .addToBackStack(null)
                    .commit();
        } catch (Exception e) {
            toast("Can't open Studio: " + e.getMessage());
        }
    }


    private void copyUriToFile(Uri uri, java.io.File dest) throws Exception {
        try (java.io.InputStream is = requireContext().getContentResolver().openInputStream(uri);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
            if (is == null) throw new Exception("Cannot open stream: " + uri);
            byte[] buf = new byte[8192]; int len;
            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
        }
    }

    // ── Pending state ─────────────────────────────────────────────────────────

    private void savePendingAdmin(String name, String cat, boolean premium, @Nullable String editId) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("category", cat);
            o.put("premium", premium);
            if (editId != null) o.put("editId", editId);
            o.put("themeCount", themeCount);
            o.put("bgUri",  bgUri != null ? bgUri.toString() : "");
            o.put("maskUri",  maskUri != null ? maskUri.toString() : "");
            o.put("previewUri", previewUri != null ? previewUri.toString() : "");
            if (existingBgUrl != null) o.put("existingBgUrl", existingBgUrl);
            if (existingMaskUrl != null) o.put("existingMaskUrl", existingMaskUrl);
            if (existingPreviewUrl != null) o.put("existingPreviewUrl", existingPreviewUrl);
            requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PENDING_ADMIN, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void restorePendingState() {
        try {
            String s = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PENDING_ADMIN, "");
            if (s.isEmpty()) return;
            JSONObject o = new JSONObject(s);
            String name   = o.optString("name", "");
            String cat    = o.optString("category", "");
            boolean prem  = o.optBoolean("premium", false);
            String editId = o.optString("editId", "");

            if (!name.isEmpty()) etName.setText(name);
            if (!cat.isEmpty())  etCategory.setText(cat);
            swPremium.setChecked(prem);

            // Restore editing state
            if (!editId.isEmpty()) {
                editingDocId = editId;
                etName.setEnabled(false);
                editBanner.setVisibility(View.VISIBLE);
                View note = getView() != null ? getView().findViewById(R.id.admin_edit_images_note) : null;
                if (note != null) note.setVisibility(View.VISIBLE);
            }

            // Restore newly-picked local URIs
            String b = o.optString("bgUri", "");
            String m = o.optString("maskUri", "");
            String p = o.optString("previewUri", "");
            if (!b.isEmpty()) { bgUri = Uri.parse(b); imgBg.setImageURI(bgUri); }
            if (!m.isEmpty()) { maskUri = Uri.parse(m); imgMask.setImageURI(maskUri); }
            if (!p.isEmpty()) { previewUri = Uri.parse(p); imgPreview.setImageURI(previewUri); }

            // Restore existing remote URLs
            String existBg   = o.optString("existingBgUrl", "");
            String existMask = o.optString("existingMaskUrl", "");
            String existPrev = o.optString("existingPreviewUrl", "");
            existingBgUrl      = existBg.isEmpty()   ? null : existBg;
            existingMaskUrl    = existMask.isEmpty()  ? null : existMask;
            existingPreviewUrl = existPrev.isEmpty()  ? null : existPrev;

            // Show existing remote images if no new local ones picked
            if (b.isEmpty() && !existBg.isEmpty())   Glide.with(this).load(existBg).into(imgBg);
            if (m.isEmpty() && !existMask.isEmpty())  Glide.with(this).load(existMask).into(imgMask);
            if (p.isEmpty() && !existPrev.isEmpty())  Glide.with(this).load(existPrev).into(imgPreview);

            // Restore theme count
            int savedThemeCount = o.optInt("themeCount", 1);
            themeCount = savedThemeCount;
            setThemeCount(savedThemeCount);

            // Detect if we just came back from theme2 Studio session
            // (admin_theme1_json is set means we were configuring theme2)
            String savedTheme1Json = requireContext().getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                    .getString("admin_theme1_json", "");
            if (!savedTheme1Json.isEmpty()) {
                // We just finished configuring theme2 in Studio
                // Current studio overrides = theme2, saved theme1 = first theme
                try {
                    String currentOverrides = StudioManager.getEffectiveThemeJson(requireContext());
                    JSONObject theme2Candidate = new JSONObject(currentOverrides);
                    if (theme2Candidate.has("time")) {
                        pendingTheme2Json = currentOverrides;
                    }
                    // Restore theme1 as the active Studio base for preview
                    requireContext().getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("theme_json", savedTheme1Json)
                            .putString("admin_theme1_json", "") // clear signal
                            .apply();
                    StudioManager.clearAll(requireContext());
                    toast("Theme 2 captured ✓  — tap Upload to save");
                } catch (Exception ex) {
                    Log.w(TAG, "Could not capture theme2: " + ex.getMessage());
                }
            }

            // Switch to create/edit tab
            switchTab(1);
            btnUpload.setText(!editId.isEmpty() ? "Update Wallpaper" : "Upload Now");
        } catch (Exception e) {
            Log.w(TAG, "restorePendingState failed: " + e.getMessage());
        }
    }

    private JSONObject loadPendingAdmin() {
        try {
            String s = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PENDING_ADMIN, "");
            return s.isEmpty() ? null : new JSONObject(s);
        } catch (Exception e) { return null; }
    }

    private void clearPendingAdmin() {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_ADMIN).apply();
    }

    // ── Static method: increment apply count (called from wallpaper apply path) ─

    /**
     * Call this when a wallpaper is applied to increment its applyCount in Firestore.
     */
    public static void incrementApplyCount(String wallpaperId) {
        if (wallpaperId == null || wallpaperId.isEmpty()) return;
        FirebaseFirestore.getInstance().collection("wallpapers")
                .document(wallpaperId)
                .update("applyCount", FieldValue.increment(1))
                .addOnFailureListener(e -> Log.w("AdminFragment", "Failed to increment applyCount for " + wallpaperId + ": " + e.getMessage()));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Intent makePickIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return i;
    }

    private void toast(String s) {
        if (isAdded()) Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
    }

    private String safeId(String name) {
        String id = name.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return id.isEmpty() ? "wallpaper" : id;
    }

    private static String guessContentType(Context ctx, Uri uri) {
        try {
            String t = ctx.getContentResolver().getType(uri);
            if (!TextUtils.isEmpty(t)) return t;
        } catch (Exception ignored) {}
        return "image/jpeg";
    }

    private static String guessExt(String ct) {
        if (ct == null) return ".jpg";
        ct = ct.toLowerCase();
        if (ct.contains("png")) return ".png";
        if (ct.contains("webp")) return ".webp";
        return ".jpg";
    }

    private Map<String, Object> jsonToMap(JSONObject obj) {
        Map<String, Object> m = new HashMap<>();
        try {
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next(); Object v = obj.get(k);
                m.put(k, v instanceof JSONObject ? jsonToMap((JSONObject) v) : v);
            }
        } catch (Exception ignored) {}
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner data class
    // ─────────────────────────────────────────────────────────────────────────

    public static class AdminWallpaperItem {
        public final WallpaperItem item;
        public final long applyCount;
        public AdminWallpaperItem(WallpaperItem item, long applyCount) {
            this.item = item; this.applyCount = applyCount;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecyclerView Adapter for wallpaper list
    // ─────────────────────────────────────────────────────────────────────────

    private static class AdminWallpaperListAdapter extends RecyclerView.Adapter<AdminWallpaperListAdapter.VH> {

        interface ItemCallback { void on(AdminWallpaperItem item); }

        private final List<AdminWallpaperItem> items = new ArrayList<>();
        private final ItemCallback onPreview, onEdit, onDelete;

        AdminWallpaperListAdapter(ItemCallback onPreview, ItemCallback onEdit, ItemCallback onDelete) {
            this.onPreview = onPreview; this.onEdit = onEdit; this.onDelete = onDelete;
        }

        void setItems(List<AdminWallpaperItem> list) {
            int oldSize = items.size();
            items.clear();
            if (oldSize > 0) notifyItemRangeRemoved(0, oldSize);
            if (list != null) {
                items.addAll(list);
                notifyItemRangeInserted(0, items.size());
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_admin_wallpaper, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            AdminWallpaperItem ai = items.get(pos);
            WallpaperItem w = ai.item;

            h.tvName.setText(w.name != null ? w.name : w.id);
            h.tvCategory.setText(w.category != null ? w.category : "—");
            h.tvApplyCount.setText(h.itemView.getContext().getString(R.string.admin_apply_count, ai.applyCount));
            h.tvPremium.setVisibility(w.isPremium ? View.VISIBLE : View.GONE);

            String url = w.previewUrl != null && !w.previewUrl.isEmpty() ? w.previewUrl : w.bgUrl;
            if (url != null) {
                Glide.with(h.itemView.getContext()).load(url).centerCrop().into(h.imgPreview);
            } else {
                h.imgPreview.setImageDrawable(null);
            }

            h.btnPreview.setOnClickListener(v -> onPreview.on(ai));
            h.btnEdit.setOnClickListener(v -> onEdit.on(ai));
            h.btnDelete.setOnClickListener(v -> onDelete.on(ai));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView imgPreview;
            TextView tvName, tvCategory, tvPremium, tvApplyCount;
            View btnPreview, btnEdit, btnDelete;
            VH(@NonNull View v) {
                super(v);
                imgPreview   = v.findViewById(R.id.admin_item_preview);
                tvName       = v.findViewById(R.id.admin_item_name);
                tvCategory   = v.findViewById(R.id.admin_item_category);
                tvPremium    = v.findViewById(R.id.admin_item_premium);
                tvApplyCount = v.findViewById(R.id.admin_item_apply_count);
                btnPreview   = v.findViewById(R.id.admin_item_btn_preview);
                btnEdit      = v.findViewById(R.id.admin_item_btn_edit);
                btnDelete    = v.findViewById(R.id.admin_item_btn_delete);
            }
        }
    }
}
