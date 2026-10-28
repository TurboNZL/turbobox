package app.turbobox;

import android.app.Application;

public final class TurboBoxApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Dimmer.install(this);
    }
}
