"""Apply explicitly requested key/value changes to a selected env file."""

import argparse
import re
from pathlib import Path


KEY_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
AUTH_SETTINGS = {"JI_AUTH_REQUIRED", "JI_ENVIRONMENT"}


def parse_assignment(value: str) -> tuple[str, str]:
    key, separator, setting = value.partition("=")
    if not separator or not KEY_PATTERN.fullmatch(key):
        raise argparse.ArgumentTypeError("settings must use KEY=VALUE with a valid environment variable name")
    if key in AUTH_SETTINGS:
        raise argparse.ArgumentTypeError(f"{key} is intentionally not managed by this utility")
    return key, setting


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Update explicitly selected settings in an env file.")
    parser.add_argument("--env-file", required=True, type=Path, help="Path to the env file to update.")
    parser.add_argument("--set", dest="settings", action="append", type=parse_assignment, default=[], metavar="KEY=VALUE")
    parser.add_argument("--unset", dest="unset_keys", action="append", default=[], metavar="KEY")
    args = parser.parse_args()

    if not args.settings and not args.unset_keys:
        parser.error("provide at least one --set or --unset operation")
    if any(not KEY_PATTERN.fullmatch(key) for key in args.unset_keys):
        parser.error("--unset values must be valid environment variable names")
    if any(key in AUTH_SETTINGS for key in args.unset_keys):
        parser.error("authentication and environment selection settings are not managed by this utility")

    updated_keys = [key for key, _ in args.settings]
    if len(updated_keys) != len(set(updated_keys)):
        parser.error("each key may be set only once per invocation")
    if set(updated_keys) & set(args.unset_keys):
        parser.error("a key cannot be both set and unset")
    return args


def main() -> None:
    args = parse_args()
    env_path = args.env_file.expanduser().resolve()
    if not env_path.is_file():
        raise SystemExit("The selected env file must already exist")

    settings = dict(args.settings)
    unset_keys = set(args.unset_keys)
    seen: set[str] = set()
    output: list[str] = []

    for line in env_path.read_text(encoding="utf-8").splitlines():
        key = line.partition("=")[0].strip() if "=" in line and not line.lstrip().startswith("#") else ""
        if key in settings:
            if key not in seen:
                output.append(f"{key}={settings[key]}")
                seen.add(key)
        elif key in unset_keys:
            continue
        else:
            output.append(line)

    for key, value in args.settings:
        if key not in seen:
            output.append(f"{key}={value}")
    env_path.write_text("\n".join(output) + "\n", encoding="utf-8")
    print("env-updated")


if __name__ == "__main__":
    main()
