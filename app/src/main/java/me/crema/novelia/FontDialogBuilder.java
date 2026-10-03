package me.crema.novelia;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import me.crema.novelia.ui.InkUi;

final class FontDialogBuilder extends AlertDialog.Builder {
    FontDialogBuilder(Context context) { super(context); }

    @Override public AlertDialog show() {
        AlertDialog dialog = super.show();
        decorate(dialog);
        return dialog;
    }

    static void decorate(AlertDialog dialog) {
        if (dialog.getWindow() == null) return;
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button target = dialog.getButton(which);
            if (target == null) continue;
            Button style = InkUi.button(dialog.getContext(), "", null);
            if (which == AlertDialog.BUTTON_POSITIVE) InkUi.primary(style);
            target.setBackground(style.getBackground());
            target.setTextColor(style.getTextColors());
            target.setAllCaps(false);
            target.setMinHeight(InkUi.dp(dialog.getContext(), 48));
            target.setTextSize(15);
        }
        int dividerId = dialog.getContext().getResources().getIdentifier("titleDivider", "id", "android");
        View divider = dialog.findViewById(dividerId);
        if (divider != null) divider.setBackgroundColor(Color.BLACK);
        int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        View title = dialog.findViewById(titleId);
        if (title instanceof android.widget.TextView) ((android.widget.TextView) title).setTextColor(Color.BLACK);
        AppFont.install(dialog.getWindow().getDecorView());
    }
}
