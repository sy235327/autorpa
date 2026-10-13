# FIRERPA API Reference

**Source of truth:** `../client/client.py` in this repo (mirrors published `lamda` package). If this file conflicts with memory, read the source. Full prose docs: `llms-full.txt`.

## Install & Connect

```bash
pip3 install -U 'lamda[full]'
```

```python
from lamda.client import *

d = Device("192.168.0.2")
d = Device("192.168.0.2", certificate="/path/to/lamda.pem", port=65000)
```

| Env var | Purpose |
|---------|---------|
| `DEVICE` | Default host for skill scripts |
| `CERTIFICATE` | PEM when server cert enabled |
| `PORT` | Service port (default 65000) |

## Device — UiAutomator & Screen

```python
d.device_info()
d.server_info()
d.wake_up() / d.sleep()
d.is_screen_on() / d.is_screen_locked()
d.show_toast(text, duration=ToastDuration.TD_SHORT)
d.get_clipboard() / d.set_clipboard(text)
d.press_key(Keys.KEY_BACK)
d.press_keycode(code, meta=0)
d.screenshot(quality, bound=None).save("out.png")   # alias: take_screenshot
d.dump_window_hierarchy(compressed=False)           # BytesIO XML
d.wait_for_idle(timeout)                            # ms
d.get_last_toast()
d.click(Point) / d.long_click(Point, timeout=0)
d.drag(A, B, step=32) / d.swipe(A, B, step=32) / d.swipe_points(*points, step=32)
d.open_notification() / d.open_quick_settings()
d.freeze_rotation(freeze=True)
d.set_orientation(orien=Orientation.ORIEN_NATURE)
d.find_similar_image(data, threshold=0.0, distance=250, scale=1.0,
                       area=FindImageArea.FIA_WHOLE_SCREEN,
                       method=FindImageMethod.FIM_TEMPLATE)  # returns bounds
```

Coordinates: origin top-left. `Point(x=, y=)`, `Bound(top=, left=, right=, bottom=)`.

## Device — Selectors

`d(**kwargs)` returns `ObjectUiAutomatorOpStub` (element handle).

**Selector keys** (chain on stub before calling `child`/`sibling`):

`text`, `textContains`, `textStartsWith`, `textMatches`, `resourceId`, `resourceIdMatches`, `description`, `descriptionContains`, `descriptionStartsWith`, `descriptionMatches`, `packageName`, `packageNameMatches`, `className`, `classNameMatches`, `checkable`, `clickable`, `focusable`, `scrollable`, `longClickable`, `enabled`, `checked`, `focused`, `selected`, `index`, `instance`

```python
el = d(text="同意")
el = d(resourceId="com.example:id/btn")
form = d(resourceId="login_form")
form.child(index=1)
form.child(index=1).sibling()
form.sibling(textContains="找回密码")
```

## Element — Interaction

```python
el.click(corner=Corner.COR_CENTER)
el.click_exists(corner=Corner.COR_CENTER)
el.long_click(corner=Corner.COR_CENTER, timeout=0)
el.exists()
el.info()                    # proto: text, bounds, className, ...
el.get_text()
el.set_text("你好世界")       # EditText only; click field first to show IME
el.clear_text_field()
el.screenshot(quality).save("el.png")
el.wait_for_exists(timeout)  # ms
el.wait_until_gone(timeout)
el.get(idx) / el.count() / for x in el: ...
el.drag_to(Selector(text="目标"))
el.swipe(direction=Direction.DIR_UP, step=32)
el.pinch_in(percent, step=16) / el.pinch_out(percent, step=16)
el.scroll_to(Selector(text="目标"), is_vertical=True)
el.fling_from_top_to_bottom()
el.fling_from_bottom_to_top()
el.fling_from_left_to_right() / el.fling_from_right_to_left()
el.fling_from_*_to_end(max_swipes)
el.scroll_from_top_to_bottom(step)
el.scroll_from_bottom_to_top(step)
el.scroll_from_left_to_right(step) / el.scroll_from_right_to_left(step)
el.scroll_from_*_to_end(max_swipes, step)
```

**Empty selector** (whole screen / default scrollable):

```python
d().swipe(direction=Direction.DIR_UP, step=32)
d().fling_from_bottom_to_top()
d().scroll_from_top_to_bottom(step=60)
d().scroll_from_bottom_to_top_to_end(max_swipes=32, step=60)
```

## Device — Watcher

```python
d.remove_all_watchers()
d.set_watcher_loop_enabled(True)
d.register_click_target_selector_watcher(name, [Selector(...), ...], Selector(...))
d.register_press_key_watcher(name, [Selector(...)], key)
d.register_none_op_watcher(name, [Selector(...)])
d.set_watcher_enabled(name, enable)
d.get_watchers() / d.get_enabled_watchers()
d.get_watcher_triggered_count(name) / d.reset_watcher_triggered_count(name)
d.remove_watcher(name)
```

## Device — Application

```python
d.enumerate_installed_apps(user=0)
d.enumerate_running_processes()
d.get_application_by_name(name)
app = d.application("com.tencent.mm", user=0)
app = d.current_application()
d.start_activity(**activity)
d.install_local_file(fpath, user=0)

app.start() / app.stop()
app.is_foreground() / app.is_installed()
app.info() / app.permissions()
app.grant(permission, mode=GrantType.GRANT_ALLOW)
app.revoke(permission)
app.is_permission_granted(permission)
app.clear_cache() / app.reset()          # reset = clear app data
app.enable() / app.disable() / app.uninstall()
app.query_launch_activity()
app.attach_script(...) / app.detach_script()
```

## Device — Shell

```python
result = d.execute_script("id", alias=None, timeout=60)
result.stdout / result.stderr / result.exitstatus

sid = d.execute_background_script(script, alias=None)
d.is_background_script_finished(sid)
d.kill_background_script(sid)
```

No `d.shell()` — use `execute_script`.

## Device — File

```python
d.upload_file(local_fpath, remote_dest)
d.download_file(remote_fpath, local_dest)
d.delete_file(fpath)
d.file_chmod(fpath, mode=0o644)
d.file_stat(fpath)
d.upload_fd(fd, dest) / d.download_fd(fpath, fd)
```

No `listdir` on Device — use `execute_script("ls ...")` or download known paths.

## Device — Virtual Display

```python
vd = d.create_virtual_display(width=None, height=None, densityDpi=None, name=None)
with d.create_virtual_display() as vd:
    vd.application("com.example.app").start()
    vd(text="按钮").click()
vd.release_virtual_display()   # or context manager __exit__
```

Use `vd` like `d` for automation on background display.

## Device — OCR

Setup once:

```python
d.setup_ocr_backend("paddleocr", quality=80, use_gpu=True)
d.setup_ocr_backend("easyocr", ["ch_sim", "en"], quality=80)
d.setup_ocr_backend(CustomClass, *args, **kwargs)
```

Selectors (only `text`, `textContains`, `textMatches`):

```python
el = d.ocr(text="我的")
el = d.ocr(textContains="开始")
el.exists() / el.click() / el.click_exists()
el.screenshot(quality) / el.info()
```

## Device — Other Shortcuts

```python
d.beep()
d.reboot() / d.shutdown() / d.reload(clean=False) / d.exit()
d.setprop(name, value) / d.getprop(name)
d.hex_patch(pattern, replacement, path, maxreplace=-1, dryrun=False)
d.touch()  # MultiTouchOpStub — multi-finger gestures
d.frida    # property, requires frida package
d.setup_ocr_backend(...)
d.set_debug_log_enabled(enable)
with d: ...  # acquire API lock
```

Settings / status / wifi / proxy / selinux / storage — via `d.stub("Settings")` etc.; see `llms-full.txt` and `../client/client.py` Device shortcuts.

## Common Patterns

```python
# Text input
el = d(className="android.widget.EditText")
el.click()
el.set_text("hello")

# Scroll until target visible
list_el = d(scrollable=True)
list_el.scroll_to(Selector(text="目标项"))

# Image match → click center
with open("tpl.png", "rb") as f:
    bounds = d.find_similar_image(f.read(), threshold=0.85)
d.click(bounds.center())

# Debug on failure
from pathlib import Path

d.screenshot(80).save("debug.png")
Path("ui.xml").write_bytes(d.dump_window_hierarchy().getvalue())
```

## Doc Index (llms-full.txt)

| Topic | Section |
|-------|---------|
| UI basics | ui-basics.md |
| UI advanced (selectors, scroll) | ui-advanced.md |
| Watcher | ui-watcher.md |
| App ops | app-ops.md |
| OCR | ocr.md |
| Image match | image-match.md |
| Virtual display | virtual-display.md |
| Shell | command-exec.md |
| Files | file-io.md |
