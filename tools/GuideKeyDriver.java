import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.lang.reflect.Method;

public final class GuideKeyDriver {
  public static void main(String[] args) throws Exception {
    int gap = Integer.parseInt(args[0]);
    // Android 14 moved the context-free shell singleton to InputManagerGlobal.
    Class<?> type;
    try { type = Class.forName("android.hardware.input.InputManagerGlobal"); }
    catch (ClassNotFoundException oldAndroid) { type = Class.forName("android.hardware.input.InputManager"); }
    Object manager = type.getDeclaredMethod("getInstance").invoke(null);
    Method inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
    long start = SystemClock.uptimeMillis();
    for (int i = 0; i < 50; i++) {
      int code = i < 25 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
      long now = SystemClock.uptimeMillis();
      for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
        KeyEvent event = new KeyEvent(now, SystemClock.uptimeMillis(), action, code, 0, 0,
          KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD);
        if (!((Boolean) inject.invoke(manager, event, 2))) throw new IllegalStateException("Input rejected");
      }
      long next = start + (i + 1) * gap;
      SystemClock.sleep(Math.max(0, next - SystemClock.uptimeMillis()));
    }
    System.out.println("keys=50 elapsedMs=" + (SystemClock.uptimeMillis() - start));
  }
}
