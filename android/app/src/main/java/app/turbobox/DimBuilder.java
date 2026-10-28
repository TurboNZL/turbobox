package app.turbobox;

import android.app.AlertDialog;
import android.content.Context;

/** An alert dialog that gets the app's brightness veil ({@link Dimmer}) when it is shown. */
final class DimBuilder extends AlertDialog.Builder {
    private final Context ctx;

    DimBuilder(Context context) {
        super(context);
        ctx = context;
    }

    @Override
    public AlertDialog show() {
        AlertDialog d = create();
        d.show();
        Dimmer.apply(ctx, d.getWindow());
        return d;
    }
}
