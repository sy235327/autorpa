#!/usr/bin/env python3
"""Unified FIRERPA automation CLI."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path
from typing import Any, Optional, Sequence

from lamda.client import Bound, Device, Keys, Point, Selector


def _device_kwargs(args=None) -> dict[str, Any]:
    kwargs: dict[str, Any] = {}
    cert = getattr(args, "certificate", None) or os.environ.get("CERTIFICATE")
    if cert:
        kwargs["certificate"] = cert
    port = getattr(args, "port", None) or os.environ.get("PORT")
    if port:
        kwargs["port"] = int(port)
    return kwargs


def _connection_target(host: str, args=None) -> str:
    port = str(getattr(args, "port", None) or os.environ.get("PORT", "65000"))
    scheme = "grpcs" if (getattr(args, "certificate", None) or os.environ.get("CERTIFICATE")) else "grpc"
    return f"{scheme}://{host}:{port}"


def _print_missing_host_help() -> None:
    print(
        "error: device host is required.\n"
        "\n"
        "Provide it explicitly, for example:\n"
        "  python3 firerpa.py dump --host 192.168.0.2\n"
        "\n"
        "Ask the user for the FIRERPA device IP/hostname (from app Console URL\n"
        "or remote desktop address). Do not guess.",
        file=sys.stderr,
    )


def _print_connection_help(host: str, exc: BaseException, args=None) -> None:
    target = _connection_target(host, args)
    cert = getattr(args, "certificate", None) or os.environ.get("CERTIFICATE")
    port = str(getattr(args, "port", None) or os.environ.get("PORT", "65000"))
    msg = str(exc).lower()

    print(f"error: cannot connect to {target}", file=sys.stderr)
    print(f"detail: {exc}", file=sys.stderr)
    print("\nhints:", file=sys.stderr)

    if not cert and any(k in msg for k in ("ssl", "tls", "certificate", "handshake", "cert")):
        print(
            "  - Server may require TLS. Provide the lamda.pem path explicitly:\n"
            "      --certificate /path/to/lamda.pem",
            file=sys.stderr,
        )

    if cert and any(k in msg for k in ("ssl", "tls", "handshake", "verify")):
        print(
            "  - CERTIFICATE may be wrong or expired. Confirm lamda.pem matches\n"
            "    the device server certificate.",
            file=sys.stderr,
        )

    if port == "65000":
        print(
            "  - Default port is 65000. If the server uses another port:\n"
            "      --port <port>",
            file=sys.stderr,
        )
    else:
        print(
            f"  - Current port is {port}. Confirm it matches the FIRERPA service port.",
            file=sys.stderr,
        )

    if host in ("127.0.0.1", "localhost") or "forward" in msg:
        print(
            "  - For ADB port forward, use localhost (not 127.0.0.1):\n"
            "      adb forward tcp:65000 tcp:65000\n"
            "      --host localhost",
            file=sys.stderr,
        )

    print(
        "  - Confirm FIRERPA service is running and PC can reach the device\n"
        "    (same LAN, or correct frp/Hub forward).\n"
        "  - Ask the user to verify host, port, and whether certificate auth is enabled.",
        file=sys.stderr,
    )


def _connect(host: str, args=None) -> Device:
    d = Device(host, **_device_kwargs(args))
    try:
        d.server_info()
    except Exception as exc:
        _print_connection_help(host, exc, args)
        sys.exit(1)
    return d


def _require_host(args) -> str:
    host = getattr(args, "host", None) or os.environ.get("DEVICE")
    if not host:
        _print_missing_host_help()
        sys.exit(2)
    return host


def _resolve_app(d, args):
    if args.package:
        return d.application(args.package, user=args.user)
    if args.user is not None:
        print("error: --user requires --package", file=sys.stderr)
        sys.exit(2)
    return d.get_application_by_name(args.name)


def _user_apps(d, user: int = 0) -> list[Any]:
    apps = d.enumerate_installed_apps(user=user)
    return sorted(
        [app for app in apps if not app.system],
        key=lambda app: ((app.label or "").lower(), app.packageName.lower()),
    )


def _match_user_apps(d, package: Optional[str], name: Optional[str], user: int = 0) -> list[Any]:
    apps = _user_apps(d, user=user)
    if package:
        return [app for app in apps if app.packageName == package]
    if name:
        lowered = name.lower()
        exact = [app for app in apps if (app.label or "").lower() == lowered]
        if exact:
            return exact
        return [app for app in apps if lowered in (app.label or "").lower()]
    return apps


def _print_app_reinstall_hint(target: str, expected_version: Optional[str] = None) -> None:
    print(f"error: target app not ready: {target}", file=sys.stderr)
    if expected_version:
        print(
            f"hint: install or reinstall the expected app version: {expected_version}",
            file=sys.stderr,
        )
    else:
        print("hint: install or reinstall the target app before continuing", file=sys.stderr)


def _add_app_target(group: argparse._MutuallyExclusiveGroup) -> None:
    group.add_argument("-p", "--package", help="Application package name")
    group.add_argument("-n", "--name", help="Application display name")


def _add_selector_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--text", help="Selector: text")
    parser.add_argument(
        "--text-contains",
        "--textContains",
        dest="text_contains",
        help="Selector: textContains",
    )
    parser.add_argument(
        "--text-starts-with",
        "--textStartsWith",
        dest="text_starts_with",
        help="Selector: textStartsWith",
    )
    parser.add_argument(
        "--text-matches",
        "--textMatches",
        dest="text_matches",
        help="Selector: textMatches",
    )
    parser.add_argument("--resource-id", dest="resource_id", help="Selector: resourceId")
    parser.add_argument(
        "--resource-id-matches",
        "--resourceIdMatches",
        dest="resource_id_matches",
        help="Selector: resourceIdMatches",
    )
    parser.add_argument("--description", help="Selector: content-desc / description")
    parser.add_argument(
        "--description-contains",
        "--descriptionContains",
        dest="description_contains",
        help="Selector: descriptionContains",
    )
    parser.add_argument(
        "--description-starts-with",
        "--descriptionStartsWith",
        dest="description_starts_with",
        help="Selector: descriptionStartsWith",
    )
    parser.add_argument(
        "--description-matches",
        "--descriptionMatches",
        dest="description_matches",
        help="Selector: descriptionMatches",
    )
    parser.add_argument("--class-name", dest="class_name", help="Selector: className")
    parser.add_argument(
        "--class-name-matches",
        "--classNameMatches",
        dest="class_name_matches",
        help="Selector: classNameMatches",
    )
    parser.add_argument("--package-name", dest="package_name", help="Selector: packageName")
    parser.add_argument(
        "--package-name-matches",
        "--packageNameMatches",
        dest="package_name_matches",
        help="Selector: packageNameMatches",
    )
    parser.add_argument("--clickable", choices=("true", "false"), help="Selector: clickable")
    parser.add_argument("--enabled", choices=("true", "false"), help="Selector: enabled")
    parser.add_argument("--focused", choices=("true", "false"), help="Selector: focused")
    parser.add_argument("--selected", choices=("true", "false"), help="Selector: selected")


def _selector_from_args(args) -> dict:
    fields = {}
    if args.text:
        fields["text"] = args.text
    if args.text_contains:
        fields["textContains"] = args.text_contains
    if args.text_starts_with:
        fields["textStartsWith"] = args.text_starts_with
    if args.text_matches:
        fields["textMatches"] = args.text_matches
    if args.resource_id:
        fields["resourceId"] = args.resource_id
    if args.resource_id_matches:
        fields["resourceIdMatches"] = args.resource_id_matches
    if args.description:
        fields["description"] = args.description
    if args.description_contains:
        fields["descriptionContains"] = args.description_contains
    if args.description_starts_with:
        fields["descriptionStartsWith"] = args.description_starts_with
    if args.description_matches:
        fields["descriptionMatches"] = args.description_matches
    if args.class_name:
        fields["className"] = args.class_name
    if args.class_name_matches:
        fields["classNameMatches"] = args.class_name_matches
    if args.package_name:
        fields["packageName"] = args.package_name
    if args.package_name_matches:
        fields["packageNameMatches"] = args.package_name_matches
    for arg_name, field_name in (
        ("clickable", "clickable"),
        ("enabled", "enabled"),
        ("focused", "focused"),
        ("selected", "selected"),
    ):
        value = getattr(args, arg_name, None)
        if value is not None:
            fields[field_name] = value == "true"
    if not fields:
        print(
            "error: specify at least one selector flag such as --text, --textContains, --resource-id, --description, or --class-name",
            file=sys.stderr,
        )
        sys.exit(2)
    return fields


def _selector_object(d: Device, args):
    return d(**_selector_from_args(args))


def _wait_for_selector(el, timeout_ms: int) -> bool:
    return el.wait_for_exists(timeout_ms)


def _bound_center(bound) -> Point:
    return Point(
        x=int(bound.left + (bound.right - bound.left) / 2),
        y=int(bound.top + (bound.bottom - bound.top) / 2),
    )


def _format_bound(prefix: str, bound) -> list[str]:
    return [
        f"{prefix}_left={bound.left}",
        f"{prefix}_top={bound.top}",
        f"{prefix}_right={bound.right}",
        f"{prefix}_bottom={bound.bottom}",
        f"{prefix}_width={bound.right - bound.left}",
        f"{prefix}_height={bound.bottom - bound.top}",
        f"{prefix}_center_x={int(bound.left + (bound.right - bound.left) / 2)}",
        f"{prefix}_center_y={int(bound.top + (bound.bottom - bound.top) / 2)}",
    ]


def _print_selector_info(info, count: int) -> None:
    lines = [
        f"count={count}",
        f"text={getattr(info, 'text', '')}",
        f"description={getattr(info, 'description', '')}",
        f"className={getattr(info, 'className', '')}",
        f"packageName={getattr(info, 'packageName', '')}",
        f"resourceName={getattr(info, 'resourceName', '')}",
        f"clickable={getattr(info, 'clickable', False)}",
        f"enabled={getattr(info, 'enabled', False)}",
        f"focused={getattr(info, 'focused', False)}",
        f"selected={getattr(info, 'selected', False)}",
    ]
    for prefix, bound in (("bounds", getattr(info, "bounds", None)), ("visible", getattr(info, "visibleBounds", None))):
        if bound is not None:
            lines.extend(_format_bound(prefix, bound))
    print("\n".join(lines))


def _validate_args(args) -> None:
    if args.command in {"app-start", "app-stop"} and args.name and args.user is not None:
        print("error: --user requires --package", file=sys.stderr)
        sys.exit(2)


def _parent_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(add_help=False)
    _add_host_arg(p)
    return p


def _add_host_arg(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--host",
        default=None,
        help="Device IP/hostname (default: DEVICE env var)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=None,
        help="Device service port (default: PORT env var or 65000)",
    )
    parser.add_argument(
        "--certificate",
        default=None,
        help="Path to lamda.pem when TLS is enabled (default: CERTIFICATE env var)",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="firerpa",
        description="FIRERPA device automation CLI",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
examples:
  %(prog)s dump -o ui.xml
  %(prog)s dump -o ui.xml --host 192.168.0.2
  %(prog)s screenshot -o screen.jpg --host 192.168.0.2
  %(prog)s app-start -p com.example.app --host 192.168.0.2
  %(prog)s app-stop -p com.example.app --host 192.168.0.2
  %(prog)s device-info --host 192.168.0.2
  %(prog)s click --host 192.168.0.2 --text "Continue"
  %(prog)s selector-info --host 192.168.0.2 --resource-id "com.example:id/title"
  %(prog)s get-text --host 192.168.0.2 --resource-id "com.example:id/input"
  %(prog)s set-text --host 192.168.0.2 --resource-id "com.example:id/input" --value "hello"
  %(prog)s assert-app-current --host 192.168.0.2 --package com.example.app
  %(prog)s shell --host 192.168.0.2 "id"
  %(prog)s server-info --host 192.168.0.2 --port 65001 --certificate /path/to/lamda.pem
        """.strip(),
    )
    parent = _parent_parser()
    sub = parser.add_subparsers(dest="command", required=True)

    p_dump = sub.add_parser("dump", parents=[parent], help="Dump UI hierarchy XML")
    p_dump.add_argument("-o", "--output", help="Output file (default: stdout)")
    p_dump.add_argument("-z", "--compressed", action="store_true")

    p_shot = sub.add_parser("screenshot", parents=[parent], help="Capture screenshot")
    p_shot.add_argument("-o", "--output", default="screenshot.jpg")
    p_shot.add_argument("-q", "--quality", type=int, default=60)
    p_shot.add_argument("--left", type=int)
    p_shot.add_argument("--top", type=int)
    p_shot.add_argument("--right", type=int)
    p_shot.add_argument("--bottom", type=int)

    for name, help_text in (
        ("app-start", "Start an application"),
        ("app-stop", "Force-stop an application"),
    ):
        p = sub.add_parser(name, parents=[parent], help=help_text)
        g = p.add_mutually_exclusive_group(required=True)
        _add_app_target(g)
        p.add_argument("-u", "--user", type=int)

    p_app_list = sub.add_parser("app-list", parents=[parent], help="List installed user apps")
    p_app_list.add_argument("-u", "--user", type=int, default=0)

    p_app_check = sub.add_parser(
        "app-check", parents=[parent], help="Check whether a user app is installed and version-matched"
    )
    g = p_app_check.add_mutually_exclusive_group(required=True)
    _add_app_target(g)
    p_app_check.add_argument("-u", "--user", type=int, default=0)
    p_app_check.add_argument(
        "--version",
        help="Expected versionName; mismatch should prompt install/reinstall",
    )

    sub.add_parser("device-info", parents=[parent], help="Show device info")
    sub.add_parser("app-current", parents=[parent], help="Show foreground app")
    sub.add_parser("server-info", parents=[parent], help="Show FIRERPA server info")
    sub.add_parser("wake", parents=[parent], help="Wake screen")
    sub.add_parser("sleep", parents=[parent], help="Turn screen off")

    p_press = sub.add_parser("press", parents=[parent], help="Press hardware key")
    p_press.add_argument(
        "key",
        choices=sorted(Keys.keys()),
        help="Key name, e.g. KEY_BACK KEY_HOME",
    )

    p_shell = sub.add_parser("shell", parents=[parent], help="Run shell command")
    p_shell.add_argument("script", help="Shell command to execute")
    p_shell.add_argument("-t", "--timeout", type=int, default=60)

    p_wait = sub.add_parser("wait-idle", parents=[parent], help="Wait for UI idle")
    p_wait.add_argument(
        "timeout",
        nargs="?",
        type=int,
        default=3000,
        help="Timeout in ms (default: 3000)",
    )

    p_wait_exists = sub.add_parser("wait-exists", parents=[parent], help="Wait for element to appear")
    _add_selector_args(p_wait_exists)
    p_wait_exists.add_argument(
        "--timeout",
        type=int,
        default=10_000,
        help="Wait timeout ms before failing (default: 10000)",
    )

    p_wait_gone = sub.add_parser("wait-gone", parents=[parent], help="Wait for element to disappear")
    _add_selector_args(p_wait_gone)
    p_wait_gone.add_argument(
        "--timeout",
        type=int,
        default=10_000,
        help="Wait timeout ms before failing (default: 10000)",
    )

    p_selector_info = sub.add_parser(
        "selector-info",
        aliases=["info"],
        parents=[parent],
        help="Show count and first-match info for a selector",
    )
    _add_selector_args(p_selector_info)
    p_selector_info.add_argument(
        "--timeout",
        type=int,
        default=0,
        help="Optional wait timeout ms before reading info (default: 0)",
    )

    p_get_text = sub.add_parser("get-text", parents=[parent], help="Read text from a selected element")
    _add_selector_args(p_get_text)
    p_get_text.add_argument(
        "--timeout",
        type=int,
        default=10_000,
        help="Wait timeout ms before failing (default: 10000)",
    )

    p_set_text = sub.add_parser("set-text", parents=[parent], help="Fill text into a selected element")
    _add_selector_args(p_set_text)
    p_set_text.add_argument("--value", required=True, help="Text value to fill")
    p_set_text.add_argument(
        "--timeout",
        type=int,
        default=10_000,
        help="Wait timeout ms before failing (default: 10000)",
    )

    p_clear_text = sub.add_parser("clear-text", parents=[parent], help="Clear text in a selected element")
    _add_selector_args(p_clear_text)
    p_clear_text.add_argument(
        "--timeout",
        type=int,
        default=10_000,
        help="Wait timeout ms before failing (default: 10000)",
    )

    p_tap_center = sub.add_parser(
        "tap-bounds-center",
        parents=[parent],
        help="Tap the center of the first matched element's bounds",
    )
    _add_selector_args(p_tap_center)
    p_tap_center.add_argument(
        "--timeout",
        type=int,
        default=10_000,
        help="Wait timeout ms before failing (default: 10000)",
    )

    p_assert_current = sub.add_parser(
        "assert-app-current",
        parents=[parent],
        help="Assert the current foreground app package",
    )
    p_assert_current.add_argument("--package", required=True, help="Expected foreground package name")

    for name, help_text in (
        ("click", "Click element matching selector"),
        ("exists", "Check if element exists (exit 0/1)"),
    ):
        p = sub.add_parser(name, parents=[parent], help=help_text)
        _add_selector_args(p)
        if name == "click":
            p.add_argument(
                "--timeout",
                type=int,
                default=10_000,
                help="Wait timeout ms before click (default: 10000)",
            )

    p_scroll = sub.add_parser(
        "scroll-to", parents=[parent], help="Scroll until target selector matches"
    )
    p_scroll.add_argument("--scrollable", action="store_true")
    _add_selector_args(p_scroll)
    p_scroll.add_argument("--horizontal", action="store_true")

    return parser


def run_command(args) -> int:
    _validate_args(args)
    host = _require_host(args)
    d = _connect(host, args)
    cmd = args.command

    if cmd == "dump":
        xml = d.dump_window_hierarchy(compressed=args.compressed).getvalue()
        if args.output:
            Path(args.output).write_bytes(xml)
            print(args.output)
        else:
            sys.stdout.buffer.write(xml)
        return 0

    if cmd == "screenshot":
        region = [args.left, args.top, args.right, args.bottom]
        if any(v is not None for v in region) and not all(v is not None for v in region):
            print("error: region requires --left --top --right --bottom", file=sys.stderr)
            return 2
        if all(v is not None for v in region):
            bound = Bound(top=args.top, left=args.left, right=args.right, bottom=args.bottom)
            d.screenshot(args.quality, bound=bound).save(args.output)
        else:
            d.screenshot(args.quality).save(args.output)
        print(args.output)
        return 0

    if cmd == "app-start":
        app = _resolve_app(d, args)
        app.start()
        print(app.info().packageName)
        return 0

    if cmd == "app-stop":
        app = _resolve_app(d, args)
        app.stop()
        print(app.info().packageName)
        return 0

    if cmd == "app-list":
        for app in _user_apps(d, user=args.user):
            label = app.label or ""
            version = app.versionName or ""
            print(
                f"package={app.packageName}\tlabel={label}\tversion={version}\tenabled={app.enabled}"
            )
        return 0

    if cmd == "app-check":
        matched = _match_user_apps(d, package=args.package, name=args.name, user=args.user)
        target = args.package or args.name or "<unknown>"
        if not matched:
            _print_app_reinstall_hint(target, expected_version=args.version)
            return 1
        if len(matched) > 1 and not args.package:
            print(f"error: multiple user apps matched name: {args.name}", file=sys.stderr)
            for app in matched:
                print(
                    f"candidate: package={app.packageName} label={app.label or ''} version={app.versionName or ''}",
                    file=sys.stderr,
                )
            print("hint: rerun app-check with --package for an exact target", file=sys.stderr)
            return 1
        app = matched[0]
        if args.version and app.versionName != args.version:
            print(
                f"error: installed app version mismatch: package={app.packageName} installed={app.versionName or '<empty>'} expected={args.version}",
                file=sys.stderr,
            )
            print("hint: install or reinstall the expected app version", file=sys.stderr)
            return 1
        print(f"package={app.packageName}")
        print(f"label={app.label or ''}")
        print(f"version={app.versionName or ''}")
        print(f"enabled={app.enabled}")
        print(f"user={app.user}")
        return 0

    if cmd == "device-info":
        info = d.device_info()
        print(f"package={info.currentPackageName}")
        print(f"display={info.displayWidth}x{info.displayHeight}")
        print(f"screen_on={info.screenOn}")
        print(f"orientation_natural={info.naturalOrientation}")
        return 0

    if cmd == "app-current":
        print(d.current_application().info().packageName)
        return 0

    if cmd == "assert-app-current":
        current = d.current_application().info().packageName
        if current != args.package:
            print(
                f"error: foreground app mismatch: current={current} expected={args.package}",
                file=sys.stderr,
            )
            return 1
        print(current)
        return 0

    if cmd == "server-info":
        info = d.server_info()
        print(f"version={info.version}")
        print(f"unique_id={info.uniqueId}")
        print(f"architecture={info.architecture}")
        print(f"secure={info.secure}")
        return 0

    if cmd == "wake":
        d.wake_up()
        return 0

    if cmd == "sleep":
        d.sleep()
        return 0

    if cmd == "press":
        d.press_key(getattr(Keys, args.key))
        return 0

    if cmd == "shell":
        r = d.execute_script(args.script, timeout=args.timeout)
        if r.stdout:
            sys.stdout.buffer.write(r.stdout)
        if r.stderr:
            sys.stderr.buffer.write(r.stderr)
        return r.exitstatus

    if cmd == "wait-idle":
        d.wait_for_idle(args.timeout)
        return 0

    if cmd in {"wait-exists", "wait-gone", "selector-info", "info", "get-text", "set-text", "clear-text", "tap-bounds-center"}:
        el = _selector_object(d, args)

        if cmd == "wait-exists":
            ok = el.wait_for_exists(args.timeout)
            print("ok" if ok else "timeout")
            return 0 if ok else 1

        if cmd == "wait-gone":
            ok = el.wait_until_gone(args.timeout)
            print("ok" if ok else "timeout")
            return 0 if ok else 1

        if getattr(args, "timeout", 0) > 0 and not _wait_for_selector(el, args.timeout):
            print("error: element not found within timeout", file=sys.stderr)
            return 1

        if cmd in {"selector-info", "info"}:
            count = el.count()
            if count == 0:
                print("count=0")
                return 1
            _print_selector_info(el.info(), count)
            return 0

        if cmd == "get-text":
            print(el.get_text())
            return 0

        if cmd == "set-text":
            ok = el.set_text(args.value)
            print("ok" if ok else "failed")
            return 0 if ok else 1

        if cmd == "clear-text":
            ok = el.clear_text_field()
            print("ok" if ok else "failed")
            return 0 if ok else 1

        if cmd == "tap-bounds-center":
            info = el.info()
            target_bound = getattr(info, "visibleBounds", None) or getattr(info, "bounds", None)
            if target_bound is None:
                print("error: matched element has no bounds", file=sys.stderr)
                return 1
            d.click(_bound_center(target_bound))
            return 0

    if cmd in ("click", "exists"):
        el = d(**_selector_from_args(args))
        if cmd == "click":
            if not el.wait_for_exists(args.timeout):
                print("error: element not found within timeout", file=sys.stderr)
                return 1
            el.click()
            return 0
        return 0 if el.exists() else 1

    if cmd == "scroll-to":
        target = Selector(**_selector_from_args(args))
        scrollable = d(scrollable=True) if args.scrollable else d()
        ok = scrollable.scroll_to(target, is_vertical=not args.horizontal)
        print("ok" if ok else "failed")
        return 0 if ok else 1

    print(f"error: unknown command {cmd}", file=sys.stderr)
    return 2


def main(argv: Optional[Sequence[str]] = None) -> int:
    argv = list(argv if argv is not None else sys.argv[1:])
    if not argv:
        build_parser().print_help()
        return 0
    args = build_parser().parse_args(argv)
    try:
        return run_command(args)
    except SystemExit:
        raise
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
