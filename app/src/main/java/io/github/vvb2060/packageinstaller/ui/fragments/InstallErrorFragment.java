package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.InstallAborted;
import rikka.shizuku.Shizuku;

public class InstallErrorFragment extends BaseDialogFragment {

    private final InstallAborted mAborted;

    public InstallErrorFragment(InstallAborted aborted) {
        super(aborted);
        mAborted = aborted;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        var code = mAborted.getAbortReason();
        var context = requireContext();
        return new AlertDialog.Builder(requireContext())
            .setTitle(code == InstallAborted.ABORT_INFO ? R.string.app_name : R.string.error_title)
            .setIcon(R.drawable.ic_app_icon)
            .setMessage(getErrorMessage(context, code))
            .setPositiveButton(android.R.string.ok,
                (dialog, which) -> {
                    cleanAndFinish();
                    if (code == InstallAborted.ABORT_SHIZUKU) {
                        checkShizuku(context);
                    }
                })
            .create();
    }

    private void checkShizuku(Context context) {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(1);
            return;
        }
        var intent = mAborted.getIntent();
        if (intent == null) {
            Uri web = Uri.parse(context.getString(R.string.shizuku_url));
            intent = new Intent(Intent.ACTION_VIEW, web);
        }
        requireActivity().startActivity(intent);
    }

    private String getErrorMessage(Context context, int code) {
        switch (code) {
            case InstallAborted.ABORT_SHIZUKU -> {
                if (!Shizuku.pingBinder()) {
                    if (mAborted.getIntent() == null) {
                        return context.getString(R.string.error_shizuku_notfound);
                    } else {
                        return context.getString(R.string.error_shizuku_notrunning);
                    }
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    return context.getString(R.string.error_shizuku_notpermitted);
                } else {
                    return context.getString(R.string.error_shizuku_unavailable);
                }
            }
            case InstallAborted.ABORT_INFO -> {
                return getString(R.string.error_info,
                    context.getString(R.string.license)) + "\n\n" +
                    context.getString(R.string.copyright);
            }
            case InstallAborted.ABORT_ROOT -> {
                return context.getString(R.string.error_root_unavailable);
            }
        }
        var id = switch (code) {
            case InstallAborted.ABORT_PARSE -> R.string.error_parse;
            case InstallAborted.ABORT_SPLIT -> R.string.error_split;
            case InstallAborted.ABORT_NOTFOUND -> R.string.error_notfound;
            case InstallAborted.ABORT_CREATE -> R.string.error_create;
            case InstallAborted.ABORT_WRITE -> R.string.error_write;
            case InstallAborted.ABORT_ROOT -> R.string.error_root_unavailable;
            default -> R.string.error_title;
        };
        return context.getString(id);
    }
}
