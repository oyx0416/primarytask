"""Create an activation code in a selected Ji backend database."""

import argparse
import secrets
import string
import sys
from pathlib import Path


ALPHABET = string.ascii_uppercase + string.digits
DEFAULT_BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend"


def grouped_code() -> str:
    raw = "".join(secrets.choice(ALPHABET) for _ in range(16))
    return "-".join(raw[index : index + 4] for index in range(0, len(raw), 4))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Create an activation code in a Ji backend database.")
    parser.add_argument(
        "--backend-dir",
        type=Path,
        default=DEFAULT_BACKEND_DIR,
        help="Backend source/database directory (defaults to this checkout's backend directory).",
    )
    parser.add_argument("--plan", required=True, choices=("free", "pro"))
    parser.add_argument("--duration-days", required=True, type=int)
    args = parser.parse_args()
    if args.duration_days < 1:
        parser.error("--duration-days must be at least 1")
    return args


def main() -> None:
    args = parse_args()
    backend_dir = args.backend_dir.resolve()
    if not backend_dir.is_dir() or not (backend_dir / "auth_store.py").is_file():
        raise SystemExit("Backend directory must contain auth_store.py")

    sys.path.insert(0, str(backend_dir))
    from auth_store import AuthStore

    code = grouped_code()
    AuthStore().create_activation_code(
        code=code,
        plan=args.plan,
        duration_days=args.duration_days,
    )
    print(code)


if __name__ == "__main__":
    main()
