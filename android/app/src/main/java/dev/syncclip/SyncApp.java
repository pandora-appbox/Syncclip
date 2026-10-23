package dev.syncclip;

import android.app.Application;

public final class SyncApp extends Application {
  public ClipStore store;
  public DeviceClient devices;
  public SyncEngine engine;
  public ImageTransfer images;

  @Override
  public void onCreate() {
    super.onCreate();
    store = new ClipStore(this);
    devices = new DeviceClient(store);
    images = new ImageTransfer(this, store);
    engine = new SyncEngine(this, store);
  }
}
